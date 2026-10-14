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

import com.sk89q.jnbt.CompoundTag;
import com.sk89q.worldedit.internal.block.BlockStateIdAccess;
import com.sk89q.worldedit.registry.state.Property;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * An immutable class that represents the state a block can be in.
 */
@SuppressWarnings("unchecked")
public class BlockState implements BlockStateHolder<BlockState> {

    static {
        BlockStateIdAccess.setBlockStateInternalId(new BlockStateIdAccess.BlockStateInternalId() {
            @Override
            public int getInternalId(BlockState blockState) {
                return blockState.internalId;
            }

            @Override
            public void setInternalId(BlockState blockState, int internalId) {
                blockState.internalId = internalId;
            }
        });
    }

    private final BlockType blockType;
    private final Map<Property<?>, Object> values;
    /**
     * Index of this state in {@link BlockType#getAllStates()}, or {@code -1} for states that are
     * not part of it ({@link FuzzyBlockState}). Replaces the per-state neighbour table.
     */
    private final int stateListIndex;

    private final BaseBlock emptyBaseBlock;

    /**
     * The internal ID of the block state.
     */
    private volatile int internalId = BlockStateIdAccess.invalidId();

    /**
     * Creates a block state. The values map must not be modified afterwards.
     *
     * @param blockType the block type
     * @param values the property values, iterated in property order
     * @param stateListIndex the index in the block type's state list, or {@code -1}
     */
    BlockState(BlockType blockType, Map<Property<?>, Object> values, int stateListIndex) {
        this.blockType = blockType;
        this.values = values;
        this.stateListIndex = stateListIndex;
        this.emptyBaseBlock = new BaseBlock(this);
    }

    @Override
    public BlockType getBlockType() {
        return this.blockType;
    }

    @Override
    public <V> BlockState with(final Property<V> property, final V value) {
        if (this.stateListIndex == -1) {
            return this;
        }
        BlockTypeStateList stateList = blockType.getInternalStateList();
        int newIndex = stateList.updateIndexOrInvalid(this.stateListIndex, property, value);
        // Unknown properties and values leave the state unchanged, as the old neighbour table did
        return newIndex == -1 ? this : stateList.getStates().get(newIndex);
    }

    @Override
    public <V> V getState(final Property<V> property) {
        return (V) this.values.get(property);
    }

    @Override
    public Map<Property<?>, Object> getStates() {
        return Collections.unmodifiableMap(this.values);
    }

    @Override
    public boolean equalsFuzzy(BlockStateHolder<?> o) {
        if (null == o) {
            return false;
        }
        if (this == o) {
            // Added a reference equality check for speediness
            return true;
        }
        if (!getBlockType().equals(o.getBlockType())) {
            return false;
        }

        Set<Property<?>> differingProperties = new HashSet<>();
        for (Object state : o.getStates().keySet()) {
            if (getState((Property<?>) state) == null) {
                differingProperties.add((Property<?>) state);
            }
        }
        for (Property<?> property : getStates().keySet()) {
            if (o.getState(property) == null) {
                differingProperties.add(property);
            }
        }

        for (Property<?> property : getStates().keySet()) {
            if (differingProperties.contains(property)) {
                continue;
            }
            if (!Objects.equals(getState(property), o.getState(property))) {
                return false;
            }
        }

        return true;
    }

    @Override
    public BlockState toImmutableState() {
        return this;
    }

    @Override
    public BaseBlock toBaseBlock() {
        return this.emptyBaseBlock;
    }

    @Override
    public BaseBlock toBaseBlock(CompoundTag compoundTag) {
        if (compoundTag == null) {
            return toBaseBlock();
        }
        return new BaseBlock(this, compoundTag);
    }

    @Override
    public String toString() {
        return getAsString();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof BlockState)) {
            return false;
        }

        return equalsFuzzy((BlockState) obj);
    }

    private Integer hashCodeCache = null;

    @Override
    public int hashCode() {
        if (hashCodeCache == null) {
            hashCodeCache = Objects.hash(blockType, values);
        }
        return hashCodeCache;
    }
}
