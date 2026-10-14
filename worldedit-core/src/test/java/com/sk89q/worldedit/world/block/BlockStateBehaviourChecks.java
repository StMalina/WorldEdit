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

import com.sk89q.worldedit.registry.state.BooleanProperty;
import com.sk89q.worldedit.registry.state.EnumProperty;
import com.sk89q.worldedit.registry.state.IntegerProperty;
import com.sk89q.worldedit.registry.state.Property;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the observable behaviour of {@link BlockState} and {@link BlockType} against an
 * independent reference model of the pre-index-list implementation (the cartesian product of the
 * property values, last property fastest, and lookups by map equality).
 *
 * <p>This class only uses the public API plus package-private constructors, and does not
 * depend on how properties are supplied, so it can run against both the old and the new
 * implementation. The block type must not have been used before a check runs.</p>
 */
final class BlockStateBehaviourChecks {

    /**
     * Synthetic block type definitions. Property maps use a non-alphabetical insertion order.
     */
    static Map<String, Map<String, Property<?>>> syntheticTypes() {
        Map<String, Map<String, Property<?>>> types = new LinkedHashMap<>();
        types.put("p0", new LinkedHashMap<>());

        Map<String, Property<?>> p1 = new LinkedHashMap<>();
        p1.put("lit", bool("lit"));
        types.put("p1", p1);

        Map<String, Property<?>> p3 = new LinkedHashMap<>();
        p3.put("waterlogged", bool("waterlogged"));
        p3.put("facing", enumeration("facing", "north", "east", "south", "west", "up", "down"));
        p3.put("age", integer("age", 4));
        types.put("p3", p3);

        Map<String, Property<?>> wire = new LinkedHashMap<>();
        for (String side : new String[] {"north", "east", "south", "west"}) {
            wire.put(side, enumeration(side, "up", "side", "none"));
        }
        wire.put("power", integer("power", 16));
        types.put("wire5", wire);

        // More values than the linear scan limit, and Integer values outside the Integer cache
        Map<String, Property<?>> wide = new LinkedHashMap<>();
        wide.put("level", new IntegerProperty("level",
            IntStream.range(1000, 1300).boxed().collect(Collectors.toList())));
        wide.put("lit", bool("lit"));
        types.put("wide", wide);

        String[] names = {"shape", "facing", "type", "half", "mode", "axis", "part", "face"};
        String[] words = {"north", "east", "south", "west", "up", "down", "none", "side", "low", "tall", "top",
            "bottom", "head", "foot", "left", "right", "single", "double", "inner", "outer", "straight",
            "ascending", "floor", "ceiling", "wall", "x", "y", "z", "lit", "open", "powered", "attached"};
        Map<String, Property<?>> p8 = new LinkedHashMap<>();
        for (int i = 0; i < names.length; i++) {
            p8.put(names[i], enumeration(names[i], Arrays.copyOfRange(words, i * 4, i * 4 + 4)));
        }
        types.put("p8x4", p8);
        return types;
    }

    static BooleanProperty bool(String name) {
        return new BooleanProperty(name, Arrays.asList(false, true));
    }

    static EnumProperty enumeration(String name, String... values) {
        return new EnumProperty(name, Arrays.asList(values));
    }

    static IntegerProperty integer(String name, int count) {
        return new IntegerProperty(name, IntStream.range(0, count).boxed().collect(Collectors.toList()));
    }

    /**
     * Creates a property that is equal to, but not the same instance as, the given one.
     */
    static Property<?> copyOf(Property<?> property) {
        if (property instanceof BooleanProperty) {
            return new BooleanProperty(property.getName(), ((BooleanProperty) property).getValues());
        } else if (property instanceof IntegerProperty) {
            return new IntegerProperty(property.getName(), ((IntegerProperty) property).getValues());
        } else if (property instanceof EnumProperty) {
            return new EnumProperty(property.getName(), ((EnumProperty) property).getValues());
        }
        throw new IllegalArgumentException("Unsupported property " + property);
    }

    /**
     * The reference model: the value maps of all states, in the order of the cartesian product
     * of the property values with the last property changing fastest.
     */
    static List<Map<Property<?>, Object>> expectedValueMaps(List<? extends Property<?>> properties) {
        List<Map<Property<?>, Object>> result = new ArrayList<>();
        result.add(new LinkedHashMap<>());
        for (Property<?> property : properties) {
            List<Map<Property<?>, Object>> next = new ArrayList<>();
            for (Map<Property<?>, Object> prefix : result) {
                for (Object value : property.getValues()) {
                    Map<Property<?>, Object> map = new LinkedHashMap<>(prefix);
                    map.put(property, value);
                    next.add(map);
                }
            }
            result = next;
        }
        if (result.isEmpty()) {
            // A property without values: a single state without values
            result.add(new LinkedHashMap<>());
        }
        return result;
    }

    static String expectedString(BlockType type, Map<Property<?>, Object> values) {
        if (values.isEmpty()) {
            return type.getId();
        }
        return type.getId() + "[" + values.entrySet().stream()
            .map(e -> e.getKey().getName() + "=" + e.getValue().toString().toLowerCase(Locale.ROOT))
            .collect(Collectors.joining(",")) + "]";
    }

    @SuppressWarnings("unchecked")
    static void checkAll(BlockType type) {
        List<? extends Property<?>> properties = type.getProperties();
        List<Map<Property<?>, Object>> expected = expectedValueMaps(properties);
        List<BlockState> all = type.getAllStates();

        // getAllStates(): content, order, distinct instances, stable, unmodifiable
        assertEquals(expected.size(), all.size(), "state count");
        assertEquals(all, type.getAllStates());
        assertThrows(UnsupportedOperationException.class, () -> type.getAllStates().add(all.get(0)));
        Map<BlockState, Integer> indexOf = new IdentityHashMap<>();
        Map<Map<Property<?>, Object>, BlockState> byValues = new HashMap<>();
        for (int i = 0; i < all.size(); i++) {
            BlockState state = all.get(i);
            assertSame(type, state.getBlockType());
            assertNull(indexOf.put(state, i), "duplicate instance");
            byValues.put(expected.get(i), state);
            assertEquals(expected.get(i), state.getStates(), "values of state " + i);
            assertEquals(new ArrayList<>(expected.get(i).keySet()), new ArrayList<>(state.getStates().keySet()),
                "iteration order of state " + i);
        }
        assertSame(all.get(0), type.getDefaultState());

        Property<Object> unknown = (Property<Object>) (Property<?>) bool("zz_unknown");
        for (int i = 0; i < all.size(); i++) {
            BlockState state = all.get(i);
            Map<Property<?>, Object> values = expected.get(i);

            // with(): every property, every value, the same and an equal property instance
            for (Property<?> property : properties) {
                Property<Object> p = (Property<Object>) property;
                Property<Object> equalCopy = (Property<Object>) copyOf(property);
                for (Object value : p.getValues()) {
                    Map<Property<?>, Object> target = new LinkedHashMap<>(values);
                    target.put(p, value);
                    BlockState expectedState = byValues.get(target);
                    assertSame(expectedState, state.with(p, value));
                    assertSame(expectedState, state.with(equalCopy, value));
                }
                assertSame(state, state.with(p, values.get(p)));
                assertSame(state, state.with(p, "not-a-value"));
                assertSame(state, state.with(p, 12345));
                assertSame(state, state.with(p, null));
                assertEquals(values.get(p), state.getState(p));
                assertEquals(values.get(p), state.getState(equalCopy));
            }
            assertSame(state, state.with(unknown, true));
            assertSame(state, state.with(null, true));
            assertNull(state.getState(unknown));
            assertNull(state.getState(null));

            // getState(Map) with other map implementations and equal property instances
            assertSame(state, type.getState(new HashMap<>(values)));
            TreeMap<Property<?>, Object> sorted = new TreeMap<>(Comparator.comparing(Property::getName));
            sorted.putAll(values);
            assertSame(state, type.getState(sorted));
            Map<Property<?>, Object> copied = new LinkedHashMap<>();
            values.forEach((k, v) -> copied.put(copyOf(k), v));
            assertSame(state, type.getState(copied));
            assertSame(state, type.getState(state.getStates()));

            // equals / hashCode / toString as the LinkedHashMap-based implementation defined them
            assertEquals(Objects.hash(type, new LinkedHashMap<>(values)), state.hashCode());
            assertEquals(new LinkedHashMap<>(values).hashCode(), state.getStates().hashCode());
            assertEquals(expectedString(type, values), state.toString());
            assertEquals(state.toString(), state.getAsString());
            assertTrue(state.equalsFuzzy(state));
            assertFalse(state.equalsFuzzy(null));

            // getStates() is unmodifiable
            Map<Property<?>, Object> states = state.getStates();
            assertThrows(UnsupportedOperationException.class, () -> states.put(unknown, true));
            assertThrows(UnsupportedOperationException.class, states::clear);

            // BaseBlock identity
            assertSame(state.toBaseBlock(), state.toBaseBlock());
            assertSame(state, state.toBaseBlock().toImmutableState());
            assertSame(state.toBaseBlock(), state.toBaseBlock(null));
            assertSame(state, state.toImmutableState());
        }

        // getState(Map) failures keep the old exception and message
        Map<Property<?>, Object> first = new HashMap<>(expected.get(0));
        assertNoState(type, null);
        Map<Property<?>, Object> extra = new HashMap<>(first);
        extra.put(unknown, true);
        assertNoState(type, extra);
        if (!properties.isEmpty()) {
            Map<Property<?>, Object> missing = new HashMap<>(first);
            missing.remove(properties.get(0));
            assertNoState(type, missing);
            Map<Property<?>, Object> invalid = new HashMap<>(first);
            invalid.put(properties.get(0), "not-a-value");
            assertNoState(type, invalid);
            Map<Property<?>, Object> nullValue = new HashMap<>(first);
            nullValue.put(properties.get(0), null);
            assertNoState(type, nullValue);
        }

        // equals / equalsFuzzy between full states: true exactly for the same state
        int step = Math.max(1, all.size() / 300);
        for (int a = 0; a < all.size(); a += step) {
            for (int b = 0; b < all.size(); b += step) {
                assertEquals(a == b, all.get(a).equalsFuzzy(all.get(b)));
                assertEquals(a == b, all.get(a).equals(all.get(b)));
            }
        }

        // FuzzyBlockState: matches exactly the states with the given values
        if (!properties.isEmpty()) {
            Property<Object> p = (Property<Object>) properties.get(0);
            Object value = p.getValues().get(p.getValues().size() - 1);
            FuzzyBlockState fuzzy = FuzzyBlockState.builder().type(type).withProperty(p, value).build();
            long matching = expected.stream().filter(m -> m.get(p).equals(value)).count();
            assertEquals(matching, all.stream().filter(fuzzy::equalsFuzzy).count());
            assertEquals(matching, all.stream().filter(s -> s.equalsFuzzy(fuzzy)).count());
            assertSame(type.getDefaultState().with(p, value), fuzzy.getFullState());
            assertEquals(type.getId() + "[" + p.getName() + "=" + value.toString().toLowerCase(Locale.ROOT) + "]",
                fuzzy.toString());
        }
        FuzzyBlockState empty = FuzzyBlockState.builder().type(type).build();
        assertSame(type.getFuzzyMatcher(), empty);
        assertSame(type.getDefaultState(), empty.getFullState());
        assertTrue(all.stream().allMatch(empty::equalsFuzzy));
    }

    private static void assertNoState(BlockType type, Map<Property<?>, Object> key) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> type.getState(key));
        assertEquals(type + " has no state for " + key, e.getMessage());
    }

    /**
     * Many threads race on the first access to the lazily generated states; all must see the same instances.
     *
     * @param typeFactory creates a fresh, unused block type for a round
     */
    static void checkConcurrentFirstAccess(BiFunction<Integer, Integer, BlockType> typeFactory) throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int round = 0; round < 20; round++) {
                BlockType type = typeFactory.apply(round, threads);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<List<BlockState>>> results = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    int thread = t;
                    Callable<List<BlockState>> task = () -> {
                        start.await();
                        if (thread % 2 == 0) {
                            type.getDefaultState();
                        }
                        BlockState state = type.getAllStates().get(type.getAllStates().size() - 1);
                        state.with(type.getProperties().get(0), null);
                        return type.getAllStates();
                    };
                    results.add(pool.submit(task));
                }
                start.countDown();
                List<BlockState> reference = results.get(0).get();
                for (Future<List<BlockState>> result : results) {
                    List<BlockState> states = result.get();
                    assertEquals(reference.size(), states.size());
                    for (int i = 0; i < states.size(); i++) {
                        assertSame(reference.get(i), states.get(i));
                    }
                }
                assertSame(reference.get(0), type.getDefaultState());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private BlockStateBehaviourChecks() {
    }
}
