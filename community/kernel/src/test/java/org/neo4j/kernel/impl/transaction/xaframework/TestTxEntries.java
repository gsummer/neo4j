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
package org.neo4j.kernel.impl.transaction.xaframework;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.mock;
import static org.neo4j.test.EphemeralFileSystemRule.shutdownDb;

import java.io.File;
import java.util.Random;

import javax.transaction.xa.Xid;

import org.junit.Rule;
import org.junit.Test;
import org.neo4j.graphdb.DynamicRelationshipType;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Transaction;
import org.neo4j.kernel.impl.transaction.XidImpl;
import org.neo4j.kernel.impl.transaction.xaframework.LogEntry.Start;
import org.neo4j.test.EphemeralFileSystemRule;
import org.neo4j.test.TestGraphDatabaseFactory;
import org.neo4j.graphdb.mockfs.EphemeralFileSystemAbstraction;

public class TestTxEntries
{
    private final Random random = new Random();
    private final long refTime = System.currentTimeMillis();
    private final int refId = 1;
    private final int refMaster = 1;
    private final int refMe = 1;
    private final long startPosition = 1000;
    private final String storeDir = new File("dir").getAbsolutePath();
    @Rule public EphemeralFileSystemRule fs = new EphemeralFileSystemRule();

    /*
     * Starts a JVM, executes a tx that fails on prepare and rollbacks,
     * triggering a bug where an extra start entry for that tx is written
     * in the xa log.
     */
    @Test
    public void testStartEntryWrittenOnceOnRollback() throws Exception
    {
        final GraphDatabaseService db = new TestGraphDatabaseFactory().setFileSystem( fs.get() ).newImpermanentDatabase( storeDir );
        createSomeTransactions( db );
        EphemeralFileSystemAbstraction snapshot = fs.snapshot( shutdownDb( db ) );

        new TestGraphDatabaseFactory().setFileSystem( snapshot ).newImpermanentDatabase( storeDir ).shutdown();
    }

    @Test
    public void startEntryShouldBeUniqueIfEitherValueChanges() throws Exception
    {
        // Positive Xid hashcode
        assertCorrectChecksumEquality( randomXid( Boolean.TRUE ) );

        // Negative Xid hashcode
        assertCorrectChecksumEquality( randomXid( Boolean.FALSE ) );
    }

    private void assertCorrectChecksumEquality( Xid refXid )
    {
        /*
         * public Start( int masterId, int myId, long timeWritten,
                      long lastCommittedTxWhenTransactionStarted, byte[] additionalHeader,
                      LogPosition startPosition )
         */
        Start ref = new Start( refMaster, refMe,refTime, 0l, refXid.getBranchQualifier(), mock( LogPosition.class) );
        assertChecksumsEquals( ref, new Start( refMaster, refMe,refTime, 0l, refXid.getBranchQualifier(), mock( LogPosition.class) ) );

        // Different Xids
        assertChecksumsNotEqual( ref, new Start( refMaster, refMe, refTime, 0l, randomXid( null ).getBranchQualifier(), mock( LogPosition.class) ) );

        // Different master
        assertChecksumsNotEqual( ref, new Start( refMaster+1, refMe, refTime, 0l, randomXid( null ).getBranchQualifier(), mock( LogPosition.class) ) );

        // Different me
        assertChecksumsNotEqual( ref, new Start( refMaster, refMe+1, refTime, 0l, randomXid( null ).getBranchQualifier(), mock( LogPosition.class) ) );
    }

    private void assertChecksumsNotEqual( Start ref, Start other )
    {
        assertFalse( LogEntry.Start.checksum( ref ) == LogEntry.Start.checksum( other ) );
    }

    private void assertChecksumsEquals( Start ref, Start other )
    {
        assertEquals( LogEntry.Start.checksum( ref ), LogEntry.Start.checksum( other ) );
    }

    private Xid randomXid( Boolean trueForPositive )
    {
        while ( true )
        {
            Xid xid = new XidImpl( randomBytes(), randomBytes() );
            if ( trueForPositive == null || xid.hashCode() > 0 == trueForPositive.booleanValue() )
            {
                return xid;
            }
        }
    }

    private byte[] randomBytes()
    {
        byte[] bytes = new byte[random.nextInt( 10 )+5];
        for ( int i = 0; i < bytes.length; i++ )
        {
            bytes[i] = (byte) random.nextInt( 255 );
        }
        return bytes;
    }

    private void createSomeTransactions( GraphDatabaseService db )
    {
        Transaction tx = db.beginTx();
        Node node1 = db.createNode();
        Node node2 = db.createNode();
        node1.createRelationshipTo( node2,
                DynamicRelationshipType.withName( "relType1" ) );
        tx.success();
        tx.finish();

        tx = db.beginTx();
        node1.delete();
        tx.success();
        try
        {
            // Will throw exception, causing the tx to be rolledback.
            tx.finish();
        }
        catch ( Exception nothingToSeeHereMoveAlong )
        {
            // InvalidRecordException coming, node1 has rels
        }
        /*
         *  The damage has already been done. The following just makes sure
         *  the corrupting tx is flushed to disk, since we will exit
         *  uncleanly.
         */
        tx = db.beginTx();
        node1.setProperty( "foo", "bar" );
        tx.success();
        tx.finish();
    }
}
