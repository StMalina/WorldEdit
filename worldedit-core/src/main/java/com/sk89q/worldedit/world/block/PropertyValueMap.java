/*
 * WorldEdit, a Minecraft world manipulation toolkit
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldEdit team and contributors
 *
 * This program is free software: you can redistribute it and/or modify
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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.sk89q.worldedit.world.block;

import com.sk89q.worldedit.registry.state.Property;

import java.util.AbstractCollection;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * A compact, read-only map from the properties of a block type to the values of one of its states.
 *
 * <p>The key array is shared by every state of a block type, so each state only owns its value
 * array. Iteration follows the key order, which is the order of {@link BlockType#getProperties()}
 * (the insertion order of the {@code LinkedHashMap} this replaces). Key lookups follow the
 * {@link java.util.HashMap} rule (same reference, or equal hash codes and {@code key.equals(k)}),
 * so a property that is equal but not identical still matches, as before.</p>
 *
 * <p>Instances are immutable: both arrays are fully populated before construction and never
 * written afterwards, and all fields are final, so they are safely published.</p>
 */
final class PropertyValueMap extends AbstractMap<Property<?>, Object> {

    /**
     * Finds the index of {@code key} in {@code keys} using {@link java.util.HashMap} semantics.
     *
     * @param keys the keys to search
     * @param key the key to search for, may be {@code null}
     * @return the index, or {@code -1} if absent
     */
    static int indexOfKey(Property<?>[] keys, Object key) {
        // Fast path: callers almost always pass the exact property instance.
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] == key) {
                return i;
            }
        }
        return indexOfEqualKey(keys, key);
    }

    private static int indexOfEqualKey(Property<?>[] keys, Object key) {
        if (key == null) {
            return -1;
        }
        int hash = key.hashCode();
        for (int i = 0; i < keys.length; i++) {
            Property<?> k = keys[i];
            if (k.hashCode() == hash && key.equals(k)) {
                return i;
            }
        }
        return -1;
    }

    private final Property<?>[] keys;
    private final Object[] values;

    /**
     * Creates a new map. Neither array may be modified afterwards.
     *
     * @param keys the keys, shared by all states of a block type
     * @param values the values, owned by this map, in key order
     */
    PropertyValueMap(Property<?>[] keys, Object[] values) {
        if (keys.length != values.length) {
            throw new IllegalArgumentException("Key and value counts differ");
        }
        this.keys = keys;
        this.values = values;
    }

    @Override
    public int size() {
        return keys.length;
    }

    @Override
    public boolean isEmpty() {
        return keys.length == 0;
    }

    @Override
    public boolean containsKey(Object key) {
        return indexOfKey(keys, key) != -1;
    }

    @Override
    public Object get(Object key) {
        int index = indexOfKey(keys, key);
        return index == -1 ? null : values[index];
    }

    @Override
    public void forEach(BiConsumer<? super Property<?>, ? super Object> action) {
        for (int i = 0; i < keys.length; i++) {
            action.accept(keys[i], values[i]);
        }
    }

    // Views are created on demand instead of being cached in AbstractMap's fields, so a state
    // never grows after construction. Their iterators do not support remove().

    @Override
    public Set<Entry<Property<?>, Object>> entrySet() {
        return new AbstractSet<Entry<Property<?>, Object>>() {
            @Override
            public Iterator<Entry<Property<?>, Object>> iterator() {
                return new IndexIterator<Entry<Property<?>, Object>>() {
                    @Override
                    Entry<Property<?>, Object> at(int index) {
                        return new SimpleImmutableEntry<>(keys[index], values[index]);
                    }
                };
            }

            @Override
            public int size() {
                return keys.length;
            }
        };
    }

    @Override
    public Set<Property<?>> keySet() {
        return new AbstractSet<Property<?>>() {
            @Override
            public Iterator<Property<?>> iterator() {
                return new IndexIterator<Property<?>>() {
                    @Override
                    Property<?> at(int index) {
                        return keys[index];
                    }
                };
            }

            @Override
            public boolean contains(Object o) {
                return containsKey(o);
            }

            @Override
            public int size() {
                return keys.length;
            }
        };
    }

    @Override
    public Collection<Object> values() {
        return new AbstractCollection<Object>() {
            @Override
            public Iterator<Object> iterator() {
                return new IndexIterator<Object>() {
                    @Override
                    Object at(int index) {
                        return values[index];
                    }
                };
            }

            @Override
            public int size() {
                return keys.length;
            }
        };
    }

    private abstract class IndexIterator<T> implements Iterator<T> {
        private int next;

        abstract T at(int index);

        @Override
        public boolean hasNext() {
            return next < keys.length;
        }

        @Override
        public T next() {
            if (next >= keys.length) {
                throw new NoSuchElementException();
            }
            return at(next++);
        }
    }
}
