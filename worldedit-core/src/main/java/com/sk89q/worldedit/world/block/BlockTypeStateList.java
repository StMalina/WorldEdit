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
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.extension.platform.Capability;
import com.sk89q.worldedit.extension.platform.Watchdog;
import com.sk89q.worldedit.registry.state.Property;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * All states of one block type, stored once per type, with index arithmetic for lookups.
 *
 * <p>This replaces the per-state neighbour tables and the per-type map keyed by property value
 * maps. A state only stores its index into {@link #getStates()}; {@link BlockState#with} and
 * {@link BlockType#getState(Map)} compute the target index instead of looking it up in a table.</p>
 *
 * <p>Instances are immutable after construction and are only created inside the
 * {@link com.sk89q.worldedit.util.concurrency.LazyReference} of their {@link BlockType}.</p>
 */
abstract class BlockTypeStateList {

    static BlockTypeStateList createFor(BlockType blockType) {
        List<? extends Property<?>> properties = blockType.getProperties();
        long startTime = System.currentTimeMillis();

        BlockTypeStateList list;
        int stateCount = DefaultBlockTypeStateList.countStates(properties);
        if (stateCount == 0) {
            // No properties, or a property without values (an empty cartesian product).
            // Both cases have always produced a single state without values.
            list = new SingletonBlockTypeStateList(new BlockState(blockType, Collections.emptyMap(), 0));
        } else {
            list = new DefaultBlockTypeStateList(blockType, properties, stateCount);
        }

        // Sometimes loading can take a while. This is the perfect spot to let MC know we're working.
        Watchdog watchdog = WorldEdit.getInstance().getPlatformManager().queryCapability(Capability.GAME_HOOKS)
            .getWatchdog();
        if (watchdog != null) {
            watchdog.tick();
        }
        long timeTaken = System.currentTimeMillis() - startTime;
        if (timeTaken > 5000) {
            WorldEdit.logger.warn("Took more than 5 seconds to generate complete state map for " + blockType.getId()
                + ". This block is likely improperly using properties. State count: " + list.getStates().size()
                + ". " + timeTaken + "ms elapsed.");
        }
        return list;
    }

    /**
     * Gets all states, in the order of the cartesian product of the property values
     * (the last property of {@link BlockType#getProperties()} changes fastest).
     *
     * @return all states, never empty
     */
    abstract ImmutableList<BlockState> getStates();

    /**
     * Calculates the index of the state whose values equal the given map.
     *
     * @param values the property values, may be {@code null}
     * @return the index into {@link #getStates()}, or {@code -1} if no state matches
     */
    abstract int calculateIndexOrInvalid(Map<?, ?> values);

    /**
     * Calculates the index of the state that differs from the state at {@code currentIndex}
     * only in the value of {@code property}.
     *
     * @param currentIndex the index of the current state
     * @param property the property to change, may be {@code null}
     * @param newValue the new value, may be {@code null}
     * @return the new index (equal to {@code currentIndex} if the value is unchanged),
     *     or {@code -1} if the property or value is not valid for this block type
     */
    abstract int updateIndexOrInvalid(int currentIndex, Property<?> property, Object newValue);
}
