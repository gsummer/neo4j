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
package org.neo4j.kernel.impl.api.state;

import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import org.neo4j.collection.primitive.Primitive;
import org.neo4j.collection.primitive.PrimitiveIntCollections;
import org.neo4j.collection.primitive.PrimitiveIntIterator;
import org.neo4j.collection.primitive.PrimitiveIntSet;
import org.neo4j.collection.primitive.PrimitiveLongIterator;
import org.neo4j.graphdb.Direction;
import org.neo4j.helpers.Function;
import org.neo4j.helpers.collection.PrefetchingIterator;
import org.neo4j.kernel.impl.util.VersionedHashMap;

import static org.neo4j.collection.primitive.PrimitiveIntCollections.iterator;

/**
 * Maintains relationships that have been added for a specific node.
 *
 * This class is not a trustworthy source of information unless you are careful - it does not, for instance, remove
 * rels if they are added and then removed in the same tx. It trusts wrapping data structures for that filtering.
 */
public class RelationshipChangesForNode
{
    /**
     * Allows this data structure to work both for tracking removals and additions.
     */
    public enum DiffStrategy
    {
        REMOVE
                {
                    @Override
                    int augmentDegree( int degree, int diff )
                    {
                        return degree - diff;
                    }

                    @Override
                    PrimitiveLongIterator augmentPrimitiveIterator( PrimitiveLongIterator original,
                                                                    Iterator<Set<Long>> diff )
                    {
                        throw new UnsupportedOperationException();
                    }
                },
        ADD
                {
                    @Override
                    int augmentDegree( int degree, int diff )
                    {
                        return degree + diff;
                    }

                    @Override
                    PrimitiveLongIterator augmentPrimitiveIterator( final PrimitiveLongIterator original,
                                                                    final Iterator<Set<Long>> diff )
                    {
                        if(!diff.hasNext())
                        {
                            return original;
                        }

                        return new PrimitiveLongIterator()
                        {
                            private Iterator<Long> currentSetOfAddedRels;

                            @Override
                            public boolean hasNext()
                            {
                                return original.hasNext() || (currentSetOfAddedRels().hasNext());
                            }

                            private Iterator<Long> currentSetOfAddedRels()
                            {
                                while(diff.hasNext() && (currentSetOfAddedRels == null || !currentSetOfAddedRels.hasNext()))
                                {
                                    currentSetOfAddedRels = diff.next().iterator();
                                }
                                return currentSetOfAddedRels;
                            }

                            @Override
                            public long next()
                            {
                                if(original.hasNext())
                                {
                                    long next = original.next();
                                    return next;
                                }
                                else
                                {
                                    return currentSetOfAddedRels().next();
                                }
                            }
                        };
                    }
                };

        abstract int augmentDegree( int degree, int diff );
        abstract PrimitiveLongIterator augmentPrimitiveIterator( PrimitiveLongIterator original, Iterator<Set<Long>> diff );

    }

    private final DiffStrategy diffStrategy;

    private Map<Integer /* Type */, Set<Long /* Id */>> outgoing;
    private Map<Integer /* Type */, Set<Long /* Id */>> incoming;
    private Map<Integer /* Type */, Set<Long /* Id */>> loops;
    private PrimitiveIntSet typesChanged;

    private int totalOutgoing = 0;
    private int totalIncoming = 0;
    private int totalLoops = 0;

    public RelationshipChangesForNode( DiffStrategy diffStrategy )
    {
        this.diffStrategy = diffStrategy;
    }

    public void addRelationship( long relId, int typeId, Direction direction )
    {
        Map<Integer, Set<Long>> relTypeToRelsMap = getTypeToRelMapForDirection( direction );
        typeChanged( typeId );
        Set<Long> rels = relTypeToRelsMap.get( typeId );
        if(rels == null)
        {
            rels = Collections.newSetFromMap(new VersionedHashMap<Long, Boolean>());
            relTypeToRelsMap.put( typeId, rels );
        }

        rels.add( relId );

        switch ( direction )
        {
            case INCOMING:
                totalIncoming++;
                break;
            case OUTGOING:
                totalOutgoing++;
                break;
            case BOTH:
                totalLoops++;
                break;
        }
    }

    private void typeChanged( int type )
    {
        if ( typesChanged == null )
        {
            typesChanged = Primitive.intSet();
        }
        typesChanged.add( type );
    }

    public boolean removeRelationship( long relId, int typeId, Direction direction)
    {
        Map<Integer, Set<Long>> relTypeToRelsMap = getTypeToRelMapForDirection( direction );
        typeChanged( typeId );
        Set<Long> rels = relTypeToRelsMap.get( typeId );
        if(rels != null)
        {
            if(rels.remove( relId ))
            {
                if(rels.size() == 0)
                {
                    relTypeToRelsMap.remove( typeId );
                }

                switch ( direction )
                {
                    case INCOMING:
                        totalIncoming--;
                        break;
                    case OUTGOING:
                        totalOutgoing--;
                        break;
                    case BOTH:
                        totalLoops--;
                        break;
                }
                return true;
            }
        }
        return false;
    }

    public PrimitiveLongIterator augmentRelationships( Direction direction, PrimitiveLongIterator rels )
    {
        return augmentRelationships( direction, rels, ALL_TYPES );
    }

    public PrimitiveLongIterator augmentRelationships( Direction direction, int[] types, PrimitiveLongIterator rels )
    {
        return augmentRelationships( direction, rels, typeFilter(types) );
    }

    public PrimitiveLongIterator augmentRelationships( Direction direction, PrimitiveLongIterator rels,
                                                       Function<Map<Integer, Set<Long>>, Iterator<Set<Long>>> typeFilter )
    {
        switch ( direction )
        {
            case INCOMING:
                if(incoming != null)
                {
                    rels = diffStrategy.augmentPrimitiveIterator( rels, typeFilter.apply( incoming ) );
                }
                break;
            case OUTGOING:
                if(outgoing != null)
                {
                    rels = diffStrategy.augmentPrimitiveIterator( rels, typeFilter.apply( outgoing ) );
                }
                break;
            case BOTH:
                if(outgoing != null)
                {
                    rels = diffStrategy.augmentPrimitiveIterator( rels, typeFilter.apply( outgoing ) );
                }
                if(incoming != null)
                {
                    rels = diffStrategy.augmentPrimitiveIterator( rels, typeFilter.apply( incoming ) );
                }
                break;
        }

        // Loops are always included
        if(loops != null)
        {
            rels = diffStrategy.augmentPrimitiveIterator( rels, loops.values().iterator() );
        }

        return rels;
    }

    public int augmentDegree( Direction direction, int degree )
    {
        switch ( direction )
        {
            case INCOMING:
                return diffStrategy.augmentDegree(degree, totalIncoming + totalLoops);
            case OUTGOING:
                return diffStrategy.augmentDegree(degree, totalOutgoing + totalLoops);
            default:
                return diffStrategy.augmentDegree(degree, totalIncoming + totalOutgoing + totalLoops);
        }
    }

    public int augmentDegree( Direction direction, int degree, int typeId )
    {
        switch ( direction )
        {
            case INCOMING:
                if(incoming != null && incoming.containsKey( typeId ))
                {
                    degree = diffStrategy.augmentDegree( degree, incoming.get( typeId ).size() );
                }
                break;
            case OUTGOING:
                if(outgoing != null && outgoing.containsKey( typeId ))
                {
                    degree = diffStrategy.augmentDegree( degree, outgoing.get( typeId ).size() );
                }
                break;
            case BOTH:
                if(outgoing != null && outgoing.containsKey( typeId ))
                {
                    degree = diffStrategy.augmentDegree( degree, outgoing.get( typeId ).size() );
                }
                if(incoming != null && incoming.containsKey( typeId ))
                {
                    degree = diffStrategy.augmentDegree( degree, incoming.get( typeId ).size() );
                }
                break;
        }

        // Loops are always included
        if(loops != null && loops.containsKey( typeId ))
        {
            degree = diffStrategy.augmentDegree( degree, loops.get( typeId ).size() );
        }
        return degree;
    }

    public PrimitiveIntIterator relationshipTypes()
    {
        Set<Integer> types = new HashSet<>();
        if(outgoing != null && !outgoing.isEmpty())
        {
            types.addAll(outgoing.keySet());
        }
        if(incoming != null && !incoming.isEmpty())
        {
            types.addAll(incoming.keySet());
        }
        if(loops != null && !loops.isEmpty())
        {
            types.addAll(loops.keySet());
        }
        return PrimitiveIntCollections.toPrimitiveIterator( types.iterator() );
    }

    public void clear()
    {
        if ( outgoing != null )
        {
            outgoing.clear();
        }
        if ( incoming != null )
        {
            incoming.clear();
        }
        if ( loops != null )
        {
            loops.clear();
        }
    }

    private Map<Integer /* Type */, Set<Long /* Id */>> outgoing()
    {
        if ( outgoing == null )
        {
            outgoing = new VersionedHashMap<>();
        }
        return outgoing;
    }

    private Iterator<Long> iteratorOrNull( Set<Long> set )
    {
        return set != null ? set.iterator() : null;
    }

    /**
     * TODO Should perhaps be a visitor of some sort instead?
     */
    public Iterator<Long> outgoingChanges( int type )
    {
        return outgoing != null ? iteratorOrNull( outgoing.get( type ) ) : null;
    }

    /**
     * TODO Should perhaps be a visitor of some sort instead?
     */
    public Iterator<Long> incomingChanges( int type )
    {
        return incoming != null ? iteratorOrNull( incoming.get( type ) ) : null;
    }

    /**
     * TODO Should perhaps be a visitor of some sort instead?
     */
    public Iterator<Long> loopsChanges( int type )
    {
        return loops != null ? iteratorOrNull( loops.get( type ) ) : null;
    }

    private Map<Integer /* Type */, Set<Long /* Id */>> incoming()
    {
        if ( incoming == null )
        {
            incoming = new VersionedHashMap<>();
        }
        return incoming;
    }

    private Map<Integer /* Type */, Set<Long /* Id */>> loops()
    {
        if ( loops == null )
        {
            loops = new VersionedHashMap<>();
        }
        return loops;
    }

    private Map<Integer, Set<Long>> getTypeToRelMapForDirection( Direction direction )
    {
        Map<Integer /* Type */, Set<Long /* Id */>> relTypeToRelsMap = null;
        switch ( direction )
        {
            case INCOMING:
                relTypeToRelsMap = incoming();
                break;
            case OUTGOING:
                relTypeToRelsMap = outgoing();
                break;
            case BOTH:
                relTypeToRelsMap = loops();
                break;
        }
        return relTypeToRelsMap;
    }

    public PrimitiveIntIterator getTypesChanged()
    {
        return typesChanged != null ? typesChanged.iterator() : PrimitiveIntCollections.emptyIterator();
    }

    private Function<Map<Integer, Set<Long>>, Iterator<Set<Long>>> typeFilter( final int[] types )
    {
        return new Function<Map<Integer, Set<Long>>, Iterator<Set<Long>>>()
        {
            @Override
            public Iterator<Set<Long>> apply( final Map<Integer, Set<Long>> relationshipsByType )
            {
                return new PrefetchingIterator<Set<Long>>()
                {
                    private final PrimitiveIntIterator iterTypes = iterator( types );

                    @Override
                    protected Set<Long> fetchNextOrNull()
                    {
                        while(iterTypes.hasNext())
                        {
                            Set<Long> relsByType = relationshipsByType.get(iterTypes.next());
                            if(relsByType != null)
                            {
                                return relsByType;
                            }
                        }
                        return null;
                    }
                };
            }
        };
    }

    private static final Function<Map<Integer, Set<Long>>, Iterator<Set<Long>>> ALL_TYPES
            = new Function<Map<Integer, Set<Long>>, Iterator<Set<Long>>>()
    {
        @Override
        public Iterator<Set<Long>> apply( Map<Integer, Set<Long>> integerSetMap )
        {
            return integerSetMap.values().iterator();
        }
    };
}
