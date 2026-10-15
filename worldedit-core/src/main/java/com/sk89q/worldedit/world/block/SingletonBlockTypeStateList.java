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

import java.util.Map;

/**
 * {@link BlockTypeStateList} with exactly one state, which has no property values.
 */
final class SingletonBlockTypeStateList extends BlockTypeStateList {

    private final ImmutableList<BlockState> states;

    SingletonBlockTypeStateList(BlockState state) {
        this.states = ImmutableList.of(state);
    }

    @Override
    ImmutableList<BlockState> getStates() {
        return states;
    }

    @Override
    int calculateIndexOrInvalid(Map<?, ?> values) {
        return values != null && values.isEmpty() ? 0 : -1;
    }

    @Override
    int updateIndexOrInvalid(int currentIndex, Property<?> property, Object newValue) {
        return -1;
    }
}
