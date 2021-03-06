/**
 * Copyright (c) 2002-2014 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.impl.nioneo.store;

import static java.lang.String.format;
import static org.neo4j.io.pagecache.PagedFile.PF_EXCLUSIVE_LOCK;
import static org.neo4j.io.pagecache.PagedFile.PF_SHARED_LOCK;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

import org.neo4j.graphdb.config.Setting;
import org.neo4j.graphdb.factory.GraphDatabaseSettings;
import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.io.fs.StoreChannel;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.io.pagecache.PageCursor;
import org.neo4j.kernel.IdGeneratorFactory;
import org.neo4j.kernel.IdType;
import org.neo4j.kernel.configuration.Config;
import org.neo4j.kernel.impl.transaction.xaframework.LogVersionRepository;
import org.neo4j.kernel.impl.util.Bits;
import org.neo4j.kernel.impl.util.StringLogger;
import org.neo4j.kernel.monitoring.Monitors;

/**
 * This class contains the references to the "NodeStore,RelationshipStore,
 * PropertyStore and RelationshipTypeStore". NeoStore doesn't actually "store"
 * anything but extends the AbstractStore for the "type and version" validation
 * performed in there.
 */
public class NeoStore extends AbstractStore implements TransactionIdStore, LogVersionRepository
{
    public static abstract class Configuration
        extends AbstractStore.Configuration
    {
        public static final Setting<Integer> relationship_grab_size = GraphDatabaseSettings.relationship_grab_size;
        public static final Setting<Integer> dense_node_threshold = GraphDatabaseSettings.dense_node_threshold;
    }

    public static final String TYPE_DESCRIPTOR = "NeoStore";
    // This value means the field has not been refreshed from the store. Normally, this should happen only once
    public static final long FIELD_NOT_INITIALIZED = Long.MIN_VALUE;
    /*
     *  7 longs in header (long + in use), time | random | version | txid | store version | graph next prop | latest constraint tx
     */
    public static final int RECORD_SIZE = 9;
    public static final String DEFAULT_NAME = "neostore";
    // Positions of meta-data records
    private static final int TIME_POSITION = 0;
    private static final int RANDOM_POSITION = 1;
    private static final int VERSION_POSITION = 2;
    private static final int LATEST_TX_POSITION = 3;
    private static final int STORE_VERSION_POSITION = 4;
    private static final int NEXT_GRAPH_PROP_POSITION = 5;
    private static final int LATEST_CONSTRAINT_TX_POSITION = 6;
    // NOTE: When adding new constants, remember to update the fields bellow,
    // and the apply() method!

    public static boolean isStorePresent( FileSystemAbstraction fs, Config config )
    {
        File neoStore = config.get( org.neo4j.kernel.impl.nioneo.store.CommonAbstractStore.Configuration.neo_store );
        return fs.fileExists( neoStore );
    }

    private NodeStore nodeStore;
    private PropertyStore propStore;
    private RelationshipStore relStore;
    private RelationshipTypeTokenStore relTypeStore;
    private LabelTokenStore labelTokenStore;
    private SchemaStore schemaStore;
    private RelationshipGroupStore relGroupStore;

    // Fields the neostore keeps cached and must be initialized on startup
    private volatile long creationTimeField = FIELD_NOT_INITIALIZED;
    private volatile long randomNumberField = FIELD_NOT_INITIALIZED;
    private volatile long versionField = FIELD_NOT_INITIALIZED;
    // This is an atomic long since we, when incrementing last tx id, won't set the record in the page,
    // we do that when flushing, which is more performant and fine from a recovery POV.
    private final AtomicLong lastCommittedTxField = new AtomicLong( FIELD_NOT_INITIALIZED );
    private volatile long storeVersionField = FIELD_NOT_INITIALIZED;
    private volatile long graphNextPropField = FIELD_NOT_INITIALIZED;
    private volatile long latestConstraintIntroducingTxField = FIELD_NOT_INITIALIZED;

    // This is not a field in the store, but something keeping track of which of the committed
    // transactions have been closed. Useful in rotation and shutdown.
    private final OutOfOrderSequence lastClosedTx = new ArrayQueueOutOfOrderSequence( -1, 200 );

    private final int relGrabSize;

    public NeoStore( File fileName, Config conf, IdGeneratorFactory idGeneratorFactory, PageCache pageCache,
            FileSystemAbstraction fileSystemAbstraction, StringLogger stringLogger,
            RelationshipTypeTokenStore relTypeStore, LabelTokenStore labelTokenStore, PropertyStore propStore,
            RelationshipStore relStore, NodeStore nodeStore, SchemaStore schemaStore,
            RelationshipGroupStore relGroupStore, StoreVersionMismatchHandler versionMismatchHandler, Monitors monitors )
    {
        super( fileName, conf, IdType.NEOSTORE_BLOCK, idGeneratorFactory, pageCache, fileSystemAbstraction,
                stringLogger, versionMismatchHandler, monitors );
        this.relTypeStore = relTypeStore;
        this.labelTokenStore = labelTokenStore;
        this.propStore = propStore;
        this.relStore = relStore;
        this.nodeStore = nodeStore;
        this.schemaStore = schemaStore;
        this.relGroupStore = relGroupStore;
        relGrabSize = conf.get( Configuration.relationship_grab_size );
        /* [MP:2012-01-03] Fix for the problem in 1.5.M02 where store version got upgraded but
         * corresponding store version record was not added. That record was added in the release
         * thereafter so this missing record doesn't trigger an upgrade of the neostore file and so any
         * unclean shutdown on such a db with 1.5.M02 < neo4j version <= 1.6.M02 would make that
         * db unable to start for that version with a "Mismatching store version found" exception.
         *
         * This will make a cleanly shut down 1.5.M02, then started and cleanly shut down with 1.6.M03 (or higher)
         * successfully add the missing record.
         */
        setRecovered();
        initialiseFields();
        try
        {
            if ( getCreationTime() != 0 /*Store that wasn't just now created*/&& getStoreVersion() == 0 /*Store is missing the store version record*/)
            {
                setStoreVersion( versionStringToLong( CommonAbstractStore.ALL_STORES_VERSION ) );
                updateHighId();
            }
        }
        finally
        {
            unsetRecovered();
        }
    }

    @Override
    protected void checkVersion()
    {
        try
        {
            verifyCorrectTypeDescriptorAndVersion();
            /*
             * If the trailing version string check returns normally, either
             * the store is not ok and needs recovery or everything is fine. The
             * latter is boring. The first case however is interesting. If we
             * need recovery we have no idea what the store version is - we erase
             * that information on startup and write it back out on clean shutdown.
             * So, if the above passes and the store is not ok, we check the
             * version field in our store vs the expected one. If it is the same,
             * we can recover and proceed, otherwise we are allowed to die a horrible death.
             */
            if ( !getStoreOk() )
            {
                /*
                 * Could we check that before? Well, yes. But. When we would read in the store version
                 * field it could very well overshoot and read in the version descriptor if the
                 * store is cleanly shutdown. If we are here though the store is not ok, so no
                 * version descriptor so the file is actually smaller than expected so we won't read
                 * in garbage.
                 * Yes, this has to be fixed to be prettier.
                 */
                String foundVersion = versionLongToString( getStoreVersion( fileSystemAbstraction,
                        configuration
                                .get( org.neo4j.kernel.impl.nioneo.store.CommonAbstractStore.Configuration.neo_store ) ) );
                if ( !CommonAbstractStore.ALL_STORES_VERSION.equals( foundVersion ) )
                {
                    throw new IllegalStateException(
                            format( "Mismatching store version found (%s while expecting %s). The store cannot be automatically upgraded since it isn't cleanly shutdown."
                                    + " Recover by starting the database using the previous Neo4j version, followed by a clean shutdown. Then start with this version again.",
                                    foundVersion, CommonAbstractStore.ALL_STORES_VERSION ) );
                }
            }
        }
        catch ( IOException e )
        {
            throw new UnderlyingStorageException( "Unable to check version " + getStorageFileName(), e );
        }
    }

    @Override
    protected void verifyFileSizeAndTruncate() throws IOException
    {
        super.verifyFileSizeAndTruncate();
        /* MP: 2011-11-23
         * A little silent upgrade for the "next prop" record. It adds one record last to the neostore file.
         * It's backwards compatible, that's why it can be a silent and automatic upgrade.
         */
        if ( getFileChannel().size() == RECORD_SIZE * 5 )
        {
            insertRecord( NEXT_GRAPH_PROP_POSITION, -1 );
            registerIdFromUpdateRecord( NEXT_GRAPH_PROP_POSITION );
        }
        /* Silent upgrade for latest constraint introducing tx
         */
        if ( getFileChannel().size() == RECORD_SIZE * 6 )
        {
            insertRecord( LATEST_CONSTRAINT_TX_POSITION, 0 );
            registerIdFromUpdateRecord( LATEST_CONSTRAINT_TX_POSITION );
        }
    }

    /**
     * This runs as part of verifyFileSizeAndTruncate, which runs before the store file has been
     * mapped in the page cache. It is therefore okay for it to access the file channel directly.
     */
    private void insertRecord( int recordPosition, long value ) throws IOException
    {
        StoreChannel channel = getFileChannel();
        long previousPosition = channel.position();
        channel.position( RECORD_SIZE * recordPosition );
        int trail = (int) (channel.size() - channel.position());
        ByteBuffer trailBuffer = null;
        if ( trail > 0 )
        {
            trailBuffer = ByteBuffer.allocate( trail );
            channel.read( trailBuffer );
            trailBuffer.flip();
        }
        ByteBuffer buffer = ByteBuffer.allocate( RECORD_SIZE );
        buffer.put( Record.IN_USE.byteValue() );
        buffer.putLong( value );
        buffer.flip();
        channel.position( RECORD_SIZE * recordPosition );
        channel.write( buffer );
        if ( trail > 0 )
        {
            channel.write( trailBuffer );
        }
        channel.position( previousPosition );
    }

    /**
     * Closes the node,relationship,property and relationship type stores.
     */
    @Override
    protected void closeStorage()
    {
        if ( relTypeStore != null )
        {
            relTypeStore.close();
            relTypeStore = null;
        }
        if ( labelTokenStore != null )
        {
            labelTokenStore.close();
            labelTokenStore = null;
        }
        if ( propStore != null )
        {
            propStore.close();
            propStore = null;
        }
        if ( relStore != null )
        {
            relStore.close();
            relStore = null;
        }
        if ( nodeStore != null )
        {
            nodeStore.close();
            nodeStore = null;
        }
        if ( schemaStore != null )
        {
            schemaStore.close();
            schemaStore = null;
        }
        if ( relGroupStore != null )
        {
            relGroupStore.close();
            relGroupStore = null;
        }
    }

    @Override
    public void flush()
    {
        flushNeoStoreOnly();
        try
        {
            pageCache.flush();
        }
        catch ( IOException e )
        {
            throw new UnderlyingStorageException( "Failed to flush", e );
        }
    }

    public void flushNeoStoreOnly()
    {
        getLastCommittingTransactionId(); // ensures that field is read
        setRecord( LATEST_TX_POSITION, lastCommittedTxField.get() );
        try
        {
            storeFile.flush();
        }
        catch ( IOException e )
        {
            throw new UnderlyingStorageException( "Failed to flush and force the NeoStore", e );
        }
    }

    @Override
    public String getTypeDescriptor()
    {
        return TYPE_DESCRIPTOR;
    }

    @Override
    public int getRecordSize()
    {
        return RECORD_SIZE;
    }

    /**
     * Sets the version for the given {@code neoStore} file.
     * @param neoStore the NeoStore file.
     * @param version the version to set.
     * @return the previous version before writing.
     */
    public static long setVersion( FileSystemAbstraction fileSystem, File neoStore, long version )
    {
        return setRecord( fileSystem, neoStore, VERSION_POSITION, version );
    }

    /**
     * Sets the store version for the given {@code neoStore} file.
     * @param neoStore the NeoStore file.
     * @param storeVersion the version to set.
     * @return the previous version before writing.
     */
    public static long setStoreVersion( FileSystemAbstraction fileSystem, File neoStore, long storeVersion )
    {
        return setRecord( fileSystem, neoStore, STORE_VERSION_POSITION, storeVersion );
    }

    private static long setRecord( FileSystemAbstraction fileSystem, File neoStore, int position, long value )
    {
        try ( StoreChannel channel = fileSystem.open( neoStore, "rw" ) )
        {
            channel.position( RECORD_SIZE * position + 1/*inUse*/);
            ByteBuffer buffer = ByteBuffer.allocate( 8 );
            channel.read( buffer );
            buffer.flip();
            long previous = buffer.getLong();
            channel.position( RECORD_SIZE * position + 1/*inUse*/);
            buffer.clear();
            buffer.putLong( value ).flip();
            channel.write( buffer );
            return previous;
        }
        catch ( IOException e )
        {
            throw new RuntimeException( e );
        }
    }

    /**
     * Warning: This method only works for stores where there is no database running!
     */
    public static long getStoreVersion( FileSystemAbstraction fs, File neoStore )
    {
        return getRecord( fs, neoStore, STORE_VERSION_POSITION );
    }

    /**
     * Warning: This method only works for stores where there is no database running!
     */
    public static long getTxId( FileSystemAbstraction fs, File neoStore )
    {
        return getRecord( fs, neoStore, LATEST_TX_POSITION );
    }

    private static long getRecord( FileSystemAbstraction fs, File neoStore, int recordPosition )
    {
        try ( StoreChannel channel = fs.open( neoStore, "r" ) )
        {
            /*
             * We have to check size, because the store version
             * field was introduced with 1.5, so if there is a non-clean
             * shutdown we may have a buffer underflow.
             */
            if ( recordPosition > 3 && channel.size() < RECORD_SIZE * 5 )
            {
                return -1;
            }
            channel.position( RECORD_SIZE * recordPosition + 1/*inUse*/);
            ByteBuffer buffer = ByteBuffer.allocate( 8 );
            channel.read( buffer );
            buffer.flip();
            return buffer.getLong();
        }
        catch ( IOException e )
        {
            throw new RuntimeException( e );
        }
    }

    public void setRecoveredStatus( boolean status )
    {
        if ( status )
        {
            setRecovered();
            nodeStore.setRecovered();
            propStore.setRecovered();
            relStore.setRecovered();
            relTypeStore.setRecovered();
            labelTokenStore.setRecovered();
            schemaStore.setRecovered();
            relGroupStore.setRecovered();
        }
        else
        {
            unsetRecovered();
            nodeStore.unsetRecovered();
            propStore.unsetRecovered();
            relStore.unsetRecovered();
            relTypeStore.unsetRecovered();
            labelTokenStore.unsetRecovered();
            schemaStore.unsetRecovered();
            relGroupStore.unsetRecovered();
        }
    }

    public StoreId getStoreId()
    {
        return new StoreId( getCreationTime(), getRandomNumber() );
    }

    public long getCreationTime()
    {
        checkInitialized( creationTimeField );
        return creationTimeField;
    }

    public synchronized void setCreationTime( long time )
    {
        setRecord( TIME_POSITION, time );
        creationTimeField = time;
    }

    public long getRandomNumber()
    {
        checkInitialized( randomNumberField );
        return randomNumberField;
    }

    public synchronized void setRandomNumber( long nr )
    {
        setRecord( RANDOM_POSITION, nr );
        randomNumberField = nr;
    }

    @Override
    public long getCurrentLogVersion()
    {
        checkInitialized( versionField );
        return versionField;
    }

    public void setCurrentLogVersion( long version )
    {
        setRecord( VERSION_POSITION, version );
        versionField = version;
    }

    @Override
    public long incrementAndGetVersion()
    {
        // This method can expect synchronisation at a higher level,
        // and be effectively single-threaded.
        // The call to getVersion() will most likely optimise to a volatile-read.
        long pageId = pageIdForRecord( VERSION_POSITION );
        try ( PageCursor cursor = storeFile.io( pageId, PF_EXCLUSIVE_LOCK ) )
        {
            if ( cursor.next() )
            {
                incrementVersion( cursor );
            }
            return versionField;
        }
        catch ( IOException e )
        {
            throw new UnderlyingStorageException( e );
        }
    }

    @Override
    public void incrementVersion()
    {
        incrementAndGetVersion();
    }

    public long getStoreVersion()
    {
        checkInitialized( storeVersionField );
        return storeVersionField;

    }

    public void setStoreVersion( long version )
    {
        setRecord( STORE_VERSION_POSITION, version );
        storeVersionField = version;
    }

    public long getGraphNextProp()
    {
        checkInitialized( graphNextPropField );
        return graphNextPropField;
    }

    public void setGraphNextProp( long propId )
    {
        setRecord( NEXT_GRAPH_PROP_POSITION, propId );
        graphNextPropField = propId;
    }

    public long getLatestConstraintIntroducingTx()
    {
        checkInitialized( latestConstraintIntroducingTxField );
        return latestConstraintIntroducingTxField;
    }

    public void setLatestConstraintIntroducingTx( long latestConstraintIntroducingTx )
    {
        setRecord( LATEST_CONSTRAINT_TX_POSITION, latestConstraintIntroducingTx );
        latestConstraintIntroducingTxField = latestConstraintIntroducingTx;
    }

    private void readAllFields( PageCursor cursor )
    {
        do
        {
            creationTimeField = getRecordValue( cursor, TIME_POSITION );
            randomNumberField = getRecordValue( cursor, RANDOM_POSITION );
            versionField = getRecordValue( cursor, VERSION_POSITION );
            long lastCommittedTxId = getRecordValue( cursor, LATEST_TX_POSITION );
            lastCommittedTxField.set( lastCommittedTxId );
            storeVersionField = getRecordValue( cursor, STORE_VERSION_POSITION );
            graphNextPropField = getRecordValue( cursor, NEXT_GRAPH_PROP_POSITION );
            latestConstraintIntroducingTxField = getRecordValue( cursor, LATEST_CONSTRAINT_TX_POSITION );
            lastClosedTx.set( lastCommittedTxId );
        } while ( cursor.retry() );
    }

    private long getRecordValue( PageCursor cursor, int position )
    {
        // The "+ 1" to skip over the inUse byte.
        int offset = position * getEffectiveRecordSize() + 1;
        cursor.setOffset( offset );
        return cursor.getLong();
    }

    private void incrementVersion( PageCursor cursor )
    {
        int offset = VERSION_POSITION * getEffectiveRecordSize();
        long value;
        do
        {
            cursor.setOffset( offset + 1 ); // +1 to skip the inUse byte
            value = cursor.getLong() + 1;
            cursor.setOffset( offset + 1 ); // +1 to skip the inUse byte
            cursor.putLong( value );
        } while ( cursor.retry() );
        versionField = value;
    }

    private void refreshFields()
    {
        scanAllFields( PF_SHARED_LOCK );
    }

    private void initialiseFields()
    {
        scanAllFields( PF_EXCLUSIVE_LOCK );
    }

    private void scanAllFields( int pf_flags )
    {
        try ( PageCursor cursor = storeFile.io( 0, pf_flags ) )
        {
            if ( cursor.next() )
            {
                readAllFields( cursor );
            }
        }
        catch ( IOException e )
        {
            throw new UnderlyingStorageException( e );
        }
    }

    private void setRecord( long id, long value )
    {
        long pageId = pageIdForRecord( id );
        try ( PageCursor cursor = storeFile.io( pageId, PF_EXCLUSIVE_LOCK ) )
        {
            if ( cursor.next() )
            {
                int offset = offsetForId( id );
                do
                {
                    cursor.setOffset( offset );
                    cursor.putByte( Record.IN_USE.byteValue() );
                    cursor.putLong( value );
                } while ( cursor.retry() );
            }
        }
        catch ( IOException e )
        {
            throw new UnderlyingStorageException( e );
        }
    }

    /**
     * Returns the node store.
     *
     * @return The node store
     */
    public NodeStore getNodeStore()
    {
        return nodeStore;
    }

    /**
     * @return the schema store.
     */
    public SchemaStore getSchemaStore()
    {
        return schemaStore;
    }

    /**
     * The relationship store.
     *
     * @return The relationship store
     */
    public RelationshipStore getRelationshipStore()
    {
        return relStore;
    }

    /**
     * Returns the relationship type store.
     *
     * @return The relationship type store
     */
    public RelationshipTypeTokenStore getRelationshipTypeTokenStore()
    {
        return relTypeStore;
    }

    /**
     * Returns the label store.
     *
     * @return The label store
     */
    public LabelTokenStore getLabelTokenStore()
    {
        return labelTokenStore;
    }

    /**
     * Returns the property store.
     *
     * @return The property store
     */
    public PropertyStore getPropertyStore()
    {
        return propStore;
    }

    /**
     * @return the {@link PropertyKeyTokenStore}
     */
    public PropertyKeyTokenStore getPropertyKeyTokenStore()
    {
        return propStore.getPropertyKeyTokenStore();
    }

    /**
     * @return the {@link RelationshipGroupStore}
     */
    public RelationshipGroupStore getRelationshipGroupStore()
    {
        return relGroupStore;
    }

    @Override
    public void makeStoreOk()
    {
        relTypeStore.makeStoreOk();
        labelTokenStore.makeStoreOk();
        propStore.makeStoreOk();
        relStore.makeStoreOk();
        nodeStore.makeStoreOk();
        schemaStore.makeStoreOk();
        relGroupStore.makeStoreOk();
        super.makeStoreOk();
    }

    @Override
    public void rebuildIdGenerators()
    {
        relTypeStore.rebuildIdGenerators();
        labelTokenStore.rebuildIdGenerators();
        propStore.rebuildIdGenerators();
        relStore.rebuildIdGenerators();
        nodeStore.rebuildIdGenerators();
        schemaStore.rebuildIdGenerators();
        relGroupStore.rebuildIdGenerators();
        super.rebuildIdGenerators();
    }

    public void updateIdGenerators()
    {
        this.updateHighId();
        relTypeStore.updateIdGenerators();
        labelTokenStore.updateIdGenerators();
        propStore.updateIdGenerators();
        relStore.updateHighId();
        nodeStore.updateIdGenerators();
        schemaStore.updateHighId();
        relGroupStore.updateHighId();
    }

    public int getRelationshipGrabSize()
    {
        return relGrabSize;
    }

    public boolean isStoreOk()
    {
        return getStoreOk() && relTypeStore.getStoreOk() && labelTokenStore.getStoreOk() && propStore.getStoreOk()
                && relStore.getStoreOk() && nodeStore.getStoreOk() && schemaStore.getStoreOk()
                && relGroupStore.getStoreOk();
    }

    @Override
    public void logVersions( StringLogger.LineLogger msgLog )
    {
        msgLog.logLine( "Store versions:" );
        super.logVersions( msgLog );
        schemaStore.logVersions( msgLog );
        nodeStore.logVersions( msgLog );
        relStore.logVersions( msgLog );
        relTypeStore.logVersions( msgLog );
        labelTokenStore.logVersions( msgLog );
        propStore.logVersions( msgLog );
        relGroupStore.logVersions( msgLog );
        stringLogger.flush();
    }

    @Override
    public void logIdUsage( StringLogger.LineLogger msgLog )
    {
        msgLog.logLine( "Id usage:" );
        schemaStore.logIdUsage( msgLog );
        nodeStore.logIdUsage( msgLog );
        relStore.logIdUsage( msgLog );
        relTypeStore.logIdUsage( msgLog );
        labelTokenStore.logIdUsage( msgLog );
        propStore.logIdUsage( msgLog );
        relGroupStore.logIdUsage( msgLog );
        stringLogger.flush();
    }

    public NeoStoreRecord asRecord()
    {
        NeoStoreRecord result = new NeoStoreRecord();
        result.setNextProp( getGraphNextProp() );
        return result;
    }

    /*
     * The following two methods encode and decode a string that is presumably
     * the store version into a long via Latin1 encoding. This leaves room for
     * 7 characters and 1 byte for the length. Current string is
     * 0.A.0 which is 5 chars, so we have room for expansion. When that
     * becomes a problem we will be in a yacht, sipping alcoholic
     * beverages of our choice. Or taking turns crashing golden
     * helicopters. Anyway, it should suffice for some time and by then
     * it should have become SEP.
     */
    public static long versionStringToLong( String storeVersion )
    {
        if ( CommonAbstractStore.UNKNOWN_VERSION.equals( storeVersion ) )
        {
            return -1;
        }
        Bits bits = Bits.bits( 8 );
        int length = storeVersion.length();
        if ( length == 0 || length > 7 )
        {
            throw new IllegalArgumentException( String.format(
                    "The given string %s is not of proper size for a store version string", storeVersion ) );
        }
        bits.put( length, 8 );
        for ( int i = 0; i < length; i++ )
        {
            char c = storeVersion.charAt( i );
            if ( c < 0 || c >= 256 )
            {
                throw new IllegalArgumentException( String.format(
                        "Store version strings should be encode-able as Latin1 - %s is not", storeVersion ) );
            }
            bits.put( c, 8 ); // Just the lower byte
        }
        return bits.getLong();
    }

    public static String versionLongToString( long storeVersion )
    {
        if ( storeVersion == -1 )
        {
            return CommonAbstractStore.UNKNOWN_VERSION;
        }
        Bits bits = Bits.bitsFromLongs( new long[] { storeVersion } );
        int length = bits.getShort( 8 );
        if ( length == 0 || length > 7 )
        {
            throw new IllegalArgumentException( String.format( "The read version string length %d is not proper.",
                    length ) );
        }
        char[] result = new char[length];
        for ( int i = 0; i < length; i++ )
        {
            result[i] = (char) bits.getShort( 8 );
        }
        return new String( result );
    }

    public int getDenseNodeThreshold()
    {
        return getRelationshipGroupStore().getDenseNodeThreshold();
    }

    @Override
    public long nextCommittingTransactionId()
    {
        getLastCommittingTransactionId(); // this ensures that the field is initialized
        return lastCommittedTxField.incrementAndGet();
    }

    @Override
    public long getLastCommittingTransactionId()
    {
        checkInitialized( lastCommittedTxField.get() );
        return lastCommittedTxField.get();
    }

    // Ensures that all fields are read from the store, by checking the initial value of the field in question
    private void checkInitialized( long field )
    {
        if ( field == FIELD_NOT_INITIALIZED )
        {
            refreshFields();
        }
    }

    @Override
    public void setLastCommittingAndClosedTransactionId( long transactionId )
    {
        getLastCommittingTransactionId(); // this ensures that the field is initialized
        lastCommittedTxField.set( transactionId );
        lastClosedTx.set( transactionId );
    }

    @Override
    public void transactionClosed( long transactionId )
    {
        lastClosedTx.offer( transactionId );
    }

    @Override
    public boolean closedTransactionIdIsOnParWithCommittingTransactionId()
    {
        return lastClosedTx.get() == lastCommittedTxField.get();
    }
}
