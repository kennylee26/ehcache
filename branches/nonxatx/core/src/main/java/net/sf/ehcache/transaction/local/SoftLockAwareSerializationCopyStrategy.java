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

package net.sf.ehcache.transaction.local;

import net.sf.ehcache.Element;
import net.sf.ehcache.store.compound.CopyStrategy;
import net.sf.ehcache.store.compound.SerializationCopyStrategy;

/**
 * todo this could be merged in the SerializationCopyStrategy
 *  benefit:  only one copy strategy which can act as default and works everywhere
 *  drawback: less readable
 *
 * @author Ludovic Orban
 */
public class SoftLockAwareSerializationCopyStrategy implements CopyStrategy {

    private CopyStrategy defaultCopyStrategy = new SerializationCopyStrategy();

    /**
     * @inheritDoc
     */
    public <T> T copy(final T value) {
        if (value instanceof Element) {
            Element element = (Element) value;

            Object elementValue = element.getObjectValue();
            if (elementValue instanceof SoftLock) {
                SoftLock softLock = (SoftLock) elementValue;

                SoftLock copy = softLock.copy(defaultCopyStrategy.copy(softLock.getOldElement()),
                        defaultCopyStrategy.copy(softLock.getNewElement()));

                return (T) new Element(element.getObjectKey(), copy);
            }

        }
        return defaultCopyStrategy.copy(value);
    }
}
