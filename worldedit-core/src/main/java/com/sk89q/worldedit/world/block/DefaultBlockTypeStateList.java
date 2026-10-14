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

import com.google.common.collect.ImmutableList;
import com.sk89q.worldedit.registry.state.Property;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * {@link BlockTypeStateList} for block types with at least one property.
 *
 * <p>All states live in a single list, ordered like the cartesian product of the property
 * values with the <em>last</em> property changing fastest. This is the order that
 * {@code Lists.cartesianProduct} produced before, so {@link BlockType#getAllStates()} and the
 * default state are unchanged. The index of a state is therefore
 * {@code sum(valueIndex[p] * stride[p])}, where {@code stride[last] = 1} and
 * {@code stride[p] = stride[p + 1] * valueCount[p + 1]}. For example, with
 * {@code [color: [red, green], shape: [cube, sphere, pyramid]]}:</p>
 * <pre>
 * 0: color=red,   shape=cube     (0 * 3 + 0)
 * 1: color=red,   shape=sphere   (0 * 3 + 1)
 * 2: color=red,   shape=pyramid  (0 * 3 + 2)
 * 3: color=green, shape=cube     (1 * 3 + 0)
 * ...
 * </pre>
 */
final class DefaultBlockTypeStateList extends BlockTypeStateList {

    /**
     * Property value counts above this use a hash index instead of a linear scan.
     */
    private static final int LINEAR_SCAN_LIMIT = 8;

    /**
     * Pre-computed information about one property, shared by all states of the block type.
     */
    private static final class PropertyEntry {
        final ImmutableList<Object> values;
        final int stride;
        @Nullable
        final Object2IntMap<Object> valueIndex;

        PropertyEntry(ImmutableList<Object> values, int stride) {
            this.values = values;
            this.stride = stride;
            if (values.size() > LINEAR_SCAN_LIMIT) {
                Object2IntOpenHashMap<Object> index = new Object2IntOpenHashMap<>(values.size());
                index.defaultReturnValue(-1);
                for (int i = 0; i < values.size(); i++) {
                    index.put(values.get(i), i);
                }
                this.valueIndex = index;
            } else {
                this.valueIndex = null;
            }
        }

        /**
         * Finds a value with the same rule a hash map uses (same reference, or equal hash codes
         * and {@code value.equals(v)}), which is how the old neighbour tables looked values up.
         *
         * @param value the value, may be {@code null}
         * @return the index of the value, or {@code -1} if it is not a value of this property
         */
        int indexOfValue(@Nullable Object value) {
            if (value == null) {
                return -1;
            }
            if (valueIndex != null) {
                return valueIndex.getInt(value);
            }
            for (int i = 0; i < values.size(); i++) {
                if (values.get(i) == value) {
                    return i;
                }
            }
            int hash = value.hashCode();
            for (int i = 0; i < values.size(); i++) {
                Object v = values.get(i);
                if (v.hashCode() == hash && value.equals(v)) {
                    return i;
                }
            }
            return -1;
        }

        int valueIndexAt(int stateIndex) {
            return (stateIndex / stride) % values.size();
        }
    }

    /**
     * Counts the states of a block type with the given properties.
     *
     * @param properties the properties
     * @return the number of states, or {@code 0} if there are no properties or a property has no values
     * @throws IllegalArgumentException if there are more than {@link Integer#MAX_VALUE} states
     */
    static int countStates(List<? extends Property<?>> properties) {
        if (properties.isEmpty()) {
            return 0;
        }
        long count = 1;
        for (Property<?> property : properties) {
            count *= property.getValues().size();
            if (count == 0) {
                return 0;
            }
            if (count > Integer.MAX_VALUE) {
                // Same exception type as Lists.cartesianProduct, which was used before
                throw new IllegalArgumentException("Cartesian product too large; must have size at most Integer.MAX_VALUE");
            }
        }
        return (int) count;
    }

    // Shared by all states of the block type, in the order of BlockType#getProperties()
    private final Property<?>[] keys;
    private final PropertyEntry[] entries;
    private final ImmutableList<BlockState> states;

    DefaultBlockTypeStateList(BlockType blockType, List<? extends Property<?>> properties, int stateCount) {
        int propertyCount = properties.size();
        this.keys = properties.toArray(new Property<?>[0]);
        this.entries = new PropertyEntry[propertyCount];
        int stride = 1;
        for (int i = propertyCount - 1; i >= 0; i--) {
            ImmutableList<Object> values = ImmutableList.copyOf(keys[i].getValues());
            if (new HashSet<>(values).size() != values.size()) {
                // The old ImmutableMap-based state map rejected these too
                throw new IllegalArgumentException("Property " + keys[i].getName() + " of " + blockType.getId()
                    + " has duplicate values: " + values);
            }
            entries[i] = new PropertyEntry(values, stride);
            stride *= values.size();
        }
        this.states = createStates(blockType, stateCount);
    }

    private ImmutableList<BlockState> createStates(BlockType blockType, int stateCount) {
        int propertyCount = keys.length;
        int[] valueIndexes = new int[propertyCount];
        Object[] currentValues = new Object[propertyCount];
        for (int i = 0; i < propertyCount; i++) {
            currentValues[i] = entries[i].values.get(0);
        }

        ImmutableList.Builder<BlockState> builder = ImmutableList.builderWithExpectedSize(stateCount);
        for (int stateIndex = 0; stateIndex < stateCount; stateIndex++) {
            // The key array is shared; each state owns a copy of the value array.
            builder.add(new BlockState(blockType, new PropertyValueMap(keys, currentValues.clone()), stateIndex));

            // Advance like an odometer, last property fastest
            for (int p = propertyCount - 1; p >= 0; p--) {
                PropertyEntry entry = entries[p];
                if (++valueIndexes[p] < entry.values.size()) {
                    currentValues[p] = entry.values.get(valueIndexes[p]);
                    break;
                }
                valueIndexes[p] = 0;
                currentValues[p] = entry.values.get(0);
            }
        }
        return builder.build();
    }

    @Override
    ImmutableList<BlockState> getStates() {
        return states;
    }

    @Override
    int calculateIndexOrInvalid(Map<?, ?> values) {
        if (values == null || values.size() != keys.length) {
            return -1;
        }
        // Same result as the old map lookup: sizes match and every given entry matches a distinct property
        boolean[] seen = new boolean[keys.length];
        int index = 0;
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            int slot = PropertyValueMap.indexOfKey(keys, entry.getKey());
            if (slot == -1 || seen[slot]) {
                return -1;
            }
            seen[slot] = true;
            int valueIndex = entries[slot].indexOfValue(entry.getValue());
            if (valueIndex == -1) {
                return -1;
            }
            index += valueIndex * entries[slot].stride;
        }
        return index;
    }

    @Override
    int updateIndexOrInvalid(int currentIndex, Property<?> property, Object newValue) {
        if (currentIndex < 0 || currentIndex >= states.size()) {
            return -1;
        }
        int slot = PropertyValueMap.indexOfKey(keys, property);
        if (slot == -1) {
            return -1;
        }
        PropertyEntry entry = entries[slot];
        int newValueIndex = entry.indexOfValue(newValue);
        if (newValueIndex == -1) {
            return -1;
        }
        return currentIndex + (newValueIndex - entry.valueIndexAt(currentIndex)) * entry.stride;
    }
}
