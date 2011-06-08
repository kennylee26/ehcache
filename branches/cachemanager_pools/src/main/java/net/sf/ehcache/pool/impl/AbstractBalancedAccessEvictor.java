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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import net.sf.ehcache.pool.PoolEvictor;

/**
 * Abstract implementation of a global 'cache value' maximizing pool eviction algorithm.
 * <p>
 *
 * @author Chris Dennis
 *
 * @param <T> type of store handled by this evictor
 */
public abstract class AbstractBalancedAccessEvictor<T> implements PoolEvictor<T> {

    private static final double ALPHA = 1.0;

    private class EvictionCostComparator implements Comparator<T> {

        final long unloadedSize;

        public EvictionCostComparator(long unloadedSize) {
            this.unloadedSize = unloadedSize;
        }

        public int compare(T s1, T s2) {
            return Float.compare(evictionCost(s1, unloadedSize), evictionCost(s2, unloadedSize));
        }
    }

    protected abstract boolean evict(T store, int count, long bytes);

    protected abstract float hitRate(T store);

    protected abstract float missRate(T store);

    protected abstract long countSize(T store);

    protected abstract long byteSize(T store);

    /**
     * {@inheritDoc}
     */
    public boolean freeSpace(Collection<T> from, long bytes) {
        List<T> sorted = new ArrayList<T>(from);
        Collections.sort(sorted, new EvictionCostComparator(getDesiredUnloadedSize(from)));

        for (T store : sorted) {
            int count;
            long byteSize = byteSize(store);
            long countSize = countSize(store);
            if (countSize == 0 || byteSize == 0) {
                count = 1;
            } else {
                count = (int) Math.max((bytes * countSize) / byteSize, 1L);
            }
            if (evict(store, count, bytes)) {
                return true;
            }
        }
        return false;
    }

    private float evictionCost(T store, long unloadedSize) {
        /*
         * The code below is a simplified version of this:
         *
         * float meanEntrySize = byteSize / countSize;
         * float accessRate = hitRate + missRate;
         * float fillLevel = hitRate / accessRate;
         * float deltaFillLevel = fillLevel / byteSize;
         *
         * return meanEntrySize * accessRate * deltaFillLevel * hitDistributionFunction(fillLevel);
         */

        float hitRate = hitRate(store);
        float missRate = missRate(store);
        long countSize = countSize(store);
        float accessRate = hitRate + missRate;

        if (accessRate == 0.0f) {
            if (byteSize(store) > unloadedSize) {
                return Float.NEGATIVE_INFINITY;
            } else {
                return Float.POSITIVE_INFINITY;
            }
        } else if (hitRate == 0.0f) {
            return Float.POSITIVE_INFINITY;
        } else {
            float cost = (hitRate / countSize) * hitDistributionFunction(hitRate / accessRate);
            if (Float.isNaN(cost)) {
                throw new AssertionError(String.format("NaN Eviction Cost [hit:%f miss:%f size:%f]", hitRate, missRate, countSize));
            } else {
                return cost;
            }
        }
    }

    private static float hitDistributionFunction(float fillLevel) {
        return (float) Math.pow(fillLevel, -ALPHA);
    }

    private long getDesiredUnloadedSize(Collection<T> from) {
        long unloadedSize = 0L;
        for (T s : from) {
            unloadedSize += byteSize(s);
        }
        return unloadedSize / from.size();
    }
}
