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

import com.sk89q.worldedit.LocalConfiguration;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.event.platform.PlatformsRegisteredEvent;
import com.sk89q.worldedit.extension.platform.Capability;
import com.sk89q.worldedit.extension.platform.Platform;
import com.sk89q.worldedit.extension.platform.PlatformManager;
import com.sk89q.worldedit.extension.platform.Preference;
import com.sk89q.worldedit.registry.state.Property;
import com.sk89q.worldedit.util.test.ResourceLockKeys;
import com.sk89q.worldedit.world.registry.BlockRegistry;
import com.sk89q.worldedit.world.registry.BundledBlockRegistry;
import com.sk89q.worldedit.world.registry.BundledRegistries;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ResourceLock(ResourceLockKeys.WORLDEDIT_PLATFORM)
@DisplayName("Block states backed by a per-type state list")
class BlockTypeStateListTest {

    private static final Platform MOCKED_PLATFORM = mock(Platform.class);
    private static final Map<String, Map<String, ? extends Property<?>>> PROPERTIES = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT_ID = new AtomicInteger();

    @BeforeAll
    static void setupFakePlatform() {
        BlockRegistry blockRegistry = new BundledBlockRegistry() {
            @Override
            public Map<String, ? extends Property<?>> getProperties(BlockType blockType) {
                return PROPERTIES.getOrDefault(blockType.getId(), Map.of());
            }
        };
        when(MOCKED_PLATFORM.getRegistries()).thenReturn(new BundledRegistries() {
            @Override
            public BlockRegistry getBlockRegistry() {
                return blockRegistry;
            }
        });
        when(MOCKED_PLATFORM.getCapabilities()).thenReturn(
            Stream.of(Capability.values())
                .collect(Collectors.toMap(Function.identity(), __ -> Preference.NORMAL))
        );
        when(MOCKED_PLATFORM.getConfiguration()).thenReturn(new LocalConfiguration() {
            @Override
            public void load() {
            }
        });
        PlatformManager platformManager = WorldEdit.getInstance().getPlatformManager();
        platformManager.register(MOCKED_PLATFORM);
        WorldEdit.getInstance().getEventBus().post(new PlatformsRegisteredEvent());
    }

    @AfterAll
    static void tearDownFakePlatform() {
        WorldEdit.getInstance().getPlatformManager().unregister(MOCKED_PLATFORM);
        PROPERTIES.clear();
    }

    /**
     * Creates a fresh block type, so state generation is exercised by every test.
     */
    private static BlockType newType(String name, Map<String, ? extends Property<?>> properties) {
        String id = "worldedit_test:" + name + "_" + NEXT_ID.getAndIncrement();
        PROPERTIES.put(id, properties);
        return new BlockType(id);
    }

    @TestFactory
    Stream<DynamicTest> behavesLikeTheReferenceModel() {
        return BlockStateBehaviourChecks.syntheticTypes().entrySet().stream()
            .map(e -> DynamicTest.dynamicTest(e.getKey(),
                () -> BlockStateBehaviourChecks.checkAll(newType(e.getKey(), e.getValue()))));
    }

    @Test
    void concurrentFirstAccessSeesOneSetOfStates() throws Exception {
        Map<String, Property<?>> wire = BlockStateBehaviourChecks.syntheticTypes().get("wire5");
        BlockStateBehaviourChecks.checkConcurrentFirstAccess((round, threads) -> newType("race", wire));
    }

    @Test
    void propertyWithoutValuesGivesSingleStateWithoutValues() {
        Map<String, Property<?>> properties = new LinkedHashMap<>();
        properties.put("lit", BlockStateBehaviourChecks.bool("lit"));
        properties.put("broken", BlockStateBehaviourChecks.enumeration("broken"));
        BlockType type = newType("empty_values", properties);
        List<BlockState> states = type.getAllStates();
        assertEquals(1, states.size());
        assertTrue(states.get(0).getStates().isEmpty());
        assertSame(states.get(0), type.getDefaultState());
        assertSame(states.get(0), states.get(0).with(BlockStateBehaviourChecks.bool("lit"), true));
        assertEquals(type.getId(), states.get(0).toString());
    }

    @Test
    void duplicatePropertyValuesAreRejected() {
        Map<String, Property<?>> properties = new LinkedHashMap<>();
        properties.put("mode", BlockStateBehaviourChecks.enumeration("mode", "a", "b", "a"));
        BlockType type = newType("duplicate_values", properties);
        assertThrows(IllegalArgumentException.class, type::getAllStates);
    }

    @Test
    void fuzzyStatesAreReturnedUnchangedByWith() {
        // The neighbour-table implementation threw a NullPointerException here, as fuzzy states had no table
        Map<String, Property<?>> properties = BlockStateBehaviourChecks.syntheticTypes().get("p3");
        BlockType type = newType("fuzzy", properties);
        @SuppressWarnings("unchecked")
        Property<Object> facing = (Property<Object>) type.getProperties().get(1);
        FuzzyBlockState fuzzy = FuzzyBlockState.builder().type(type).withProperty(facing, "up").build();
        assertSame(fuzzy, fuzzy.with(facing, "down"));
        assertSame(type.getFuzzyMatcher(), type.getFuzzyMatcher().with(facing, "down"));
    }

    @Test
    void getAllStatesReturnsTheSharedList() {
        BlockType type = newType("p3", BlockStateBehaviourChecks.syntheticTypes().get("p3"));
        assertSame(type.getAllStates(), type.getAllStates());
    }
}
