package org.pragmatica.serialization;

import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class CanonicalSliceCodecTest {
    @Test void nestedMapsAndSetsHaveStableBytesIndependentOfInsertionOrder() {
        var codec = FrameworkCodecs.frameworkCodecs();
        var first = new LinkedHashMap<String, Object>();
        first.put("b", new LinkedHashSet<>(List.of("z", "a")));
        first.put("a", new LinkedHashMap<>(Map.of("x", 1, "y", 2)));
        var second = new LinkedHashMap<String, Object>();
        var nested = new LinkedHashMap<String, Integer>();
        nested.put("y", 2);
        nested.put("x", 1);
        second.put("a", nested);
        second.put("b", new LinkedHashSet<>(List.of("a", "z")));
        assertFalse(java.util.Arrays.equals(codec.encode(first), codec.encode(second)));
        assertArrayEquals(codec.canonical().encode(first), codec.canonical().encode(second));
        assertEquals(first, (Object) codec.decode(codec.canonical().encode(first)));
        assertEquals(codec.canonical().canonical(), codec.canonical().canonical());
    }

    @Test void orderedListsRemainOrdered() {
        var codec = FrameworkCodecs.frameworkCodecs().canonical();
        assertFalse(java.util.Arrays.equals(codec.encode(List.of("a", "b")), codec.encode(List.of("b", "a"))));
        assertArrayEquals(codec.encode(Set.of()), codec.encode(new LinkedHashSet<>()));
    }
}
