/*
 * Copyright (c) 2002-2020 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
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
package org.neo4j.index.internal.gbptree;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.neo4j.io.fs.DefaultFileSystemAbstraction;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.io.pagecache.impl.muninn.StandalonePageCacheFactory;
import org.neo4j.test.extension.Inject;
import org.neo4j.test.extension.RandomExtension;
import org.neo4j.test.extension.testdirectory.TestDirectoryExtension;
import org.neo4j.test.rule.RandomRule;
import org.neo4j.test.rule.TestDirectory;
import org.neo4j.test.scheduler.ThreadPoolJobScheduler;

import static java.lang.String.format;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.neo4j.io.pagecache.tracing.cursor.PageCursorTracer.NULL;

@TestDirectoryExtension
@ExtendWith( RandomExtension.class )
abstract class GBPTreeReadWriteTestBase<KEY,VALUE>
{
    private static final int PAGE_SIZE_8k = 8192;
    private static final int PAGE_SIZE_16k = 16384;
    private static final int PAGE_SIZE_32k = 32768;
    private static final int PAGE_SIZE_64k = 65536;
    private static final int PAGE_SIZE_4M = 4194304;
    @Inject
    private TestDirectory testDirectory;
    @Inject
    private DefaultFileSystemAbstraction fs;
    @Inject
    private RandomRule random;

    private PageCache pageCache;
    private TestLayout<KEY,VALUE> layout;
    private File indexFile;

    @AfterEach
    private void tearDown()
    {
        if ( pageCache != null )
        {
            pageCache.close();
            pageCache = null;
        }
    }

    abstract TestLayout<KEY,VALUE> getLayout( RandomRule random, int pageSize );

    @ParameterizedTest
    @ValueSource( ints = {PAGE_SIZE_8k, PAGE_SIZE_16k, PAGE_SIZE_32k, PAGE_SIZE_64k, PAGE_SIZE_4M} )
    void shouldSeeSimpleInsertions( int pageSize ) throws Exception
    {
        setupTest( pageSize );
        try ( GBPTree<KEY,VALUE> index = index() )
        {
            int count = 1000;
            try ( Writer<KEY,VALUE> writer = index.writer( NULL ) )
            {
                for ( int i = 0; i < count; i++ )
                {
                    writer.put( key( i ), value( i ) );
                }
            }

            try ( Seeker<KEY,VALUE> cursor = index.seek( key( 0 ), key( Long.MAX_VALUE ), NULL ) )
            {
                for ( int i = 0; i < count; i++ )
                {
                    assertTrue( cursor.next() );
                    assertEqualsKey( key( i ), cursor.key() );
                }
                assertFalse( cursor.next() );
            }
        }
    }

    @ParameterizedTest
    @ValueSource( ints = {PAGE_SIZE_8k, PAGE_SIZE_16k, PAGE_SIZE_32k, PAGE_SIZE_64k, PAGE_SIZE_4M} )
    void shouldSeeSimpleInsertionsWithExactMatch( int pageSize ) throws Exception
    {
        setupTest( pageSize );
        try ( GBPTree<KEY,VALUE> index = index() )
        {
            int count = 1000;
            try ( Writer<KEY,VALUE> writer = index.writer( NULL ) )
            {
                for ( int i = 0; i < count; i++ )
                {
                    writer.put( key( i ), value( i ) );
                }
            }

            for ( int i = 0; i < count; i++ )
            {
                try ( Seeker<KEY,VALUE> cursor = index.seek( key( i ), key( i ), NULL ) )
                {
                    assertTrue( cursor.next() );
                    assertEqualsKey( key( i ), cursor.key() );
                    assertFalse( cursor.next() );
                }
            }
        }
    }

    /* Randomized tests */

    @ParameterizedTest
    @ValueSource( ints = {PAGE_SIZE_8k, PAGE_SIZE_16k, PAGE_SIZE_32k, PAGE_SIZE_64k, PAGE_SIZE_4M} )
    void shouldSplitCorrectly( int pageSize ) throws Exception
    {
        setupTest( pageSize );
        // GIVEN
        try ( GBPTree<KEY,VALUE> index = index() )
        {
            // WHEN
            int count = 1_000;
            List<KEY> seen = new ArrayList<>( count );
            try ( Writer<KEY,VALUE> writer = index.writer( NULL ) )
            {
                for ( int i = 0; i < count; i++ )
                {
                    KEY key;
                    do
                    {
                        key = key( random.nextInt( 100_000 ) );
                    }
                    while ( listContains( seen, key ) );
                    VALUE value = value( i );
                    writer.put( key, value );
                    seen.add( key );
                }
            }

            // THEN
            try ( Seeker<KEY,VALUE> cursor = index.seek( key( 0 ), key( Long.MAX_VALUE ), NULL ) )
            {
                long prev = -1;
                while ( cursor.next() )
                {
                    KEY hit = cursor.key();
                    long hitSeed = layout.keySeed( hit );
                    if ( hitSeed < prev )
                    {
                        fail( hit + " smaller than prev " + prev );
                    }
                    prev = hitSeed;
                    assertTrue( removeFromList( seen, hit ) );
                }

                if ( !seen.isEmpty() )
                {
                    fail( "expected hits " + seen );
                }
            }
        }
    }

    private void setupTest( int pageSize )
    {
        indexFile = testDirectory.file( "index" );
        pageCache = StandalonePageCacheFactory.createPageCache( fs, new ThreadPoolJobScheduler(), pageSize );
        layout = getLayout( random, pageCache.pageSize() );
    }

    private GBPTree<KEY,VALUE> index()
    {
        return new GBPTreeBuilder<>( pageCache, indexFile, layout ).build();
    }

    private boolean removeFromList( List<KEY> list, KEY item )
    {
        for ( int i = 0; i < list.size(); i++ )
        {
            if ( layout.compare( list.get( i ), item ) == 0 )
            {
                list.remove( i );
                return true;
            }
        }
        return false;
    }

    private boolean listContains( List<KEY> list, KEY item )
    {
        for ( KEY key : list )
        {
            if ( layout.compare( key, item ) == 0 )
            {
                return true;
            }
        }
        return false;
    }

    private VALUE value( long seed )
    {
        return layout.value( seed );
    }

    private KEY key( long seed )
    {
        return layout.key( seed );
    }

    private void assertEqualsKey( KEY expected, KEY actual )
    {
        assertEquals( 0, layout.compare( expected, actual ),
                format( "expected equal, expected=%s, actual=%s", expected, actual ) );
    }
}
