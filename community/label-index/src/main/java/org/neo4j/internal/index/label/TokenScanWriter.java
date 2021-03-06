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
package org.neo4j.internal.index.label;

import java.io.Closeable;
import java.io.IOException;

import org.neo4j.storageengine.api.EntityTokenUpdate;

public interface TokenScanWriter extends Closeable
{
    /**
     * Store a {@link EntityTokenUpdate}. Calls to this method MUST be ordered by ascending entity id.
     *
     * @param update entity token update to store
     * @throws IOException some kind of I/O exception has occurred
     */
    void write( EntityTokenUpdate update ) throws IOException;

    /**
     * Close this writer and flush pending changes to the store.
     */
    @Override
    void close() throws IOException;

    TokenScanWriter EMPTY_WRITER = new TokenScanWriter()
    {
        @Override
        public void write( EntityTokenUpdate update )
        {   // no-op
        }

        @Override
        public void close()
        {   // no-op
        }
    };
}
