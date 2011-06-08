/**
 *  Copyright 2003-2010 Terracotta, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */


package net.sf.ehcache.pool.impl;

import net.sf.ehcache.pool.PoolEvictor;
import net.sf.ehcache.pool.PoolableStore;

import java.util.Collection;

/**
 * Pool evictor which always evicts from the stores in round-robin fashion.

 * @author Ludovic Orban
 */
public class RoundRobinOnDiskPoolEvictor implements PoolEvictor<PoolableStore> {

    /**
     * {@inheritDoc}
     */
    public boolean freeSpace(Collection<PoolableStore> from, long bytes) {
        long remaining = bytes;

        while (true) {
            for (PoolableStore poolableStore : from) {
                long beforeEvictionSize = poolableStore.getOnDiskSizeInBytes();
                if (!poolableStore.evictFromOnDisk(1, bytes)) {
                    return false;
                }
                long afterEvictionSize = poolableStore.getOnDiskSizeInBytes();

                remaining -= (beforeEvictionSize - afterEvictionSize);
                if (remaining <= 0L) {
                    return true;
                }
            }
        }
    }

}
