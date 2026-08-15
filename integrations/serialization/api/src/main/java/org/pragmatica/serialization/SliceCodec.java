/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
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
package org.pragmatica.serialization;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;


public interface SliceCodec extends Serializer, Deserializer {
    // --- Wire format tag constants ---
    int TAG_UNIT = 0;
    int TAG_NONE = 1;
    int TAG_SOME = 2;
    int TAG_SUCCESS = 3;
    int TAG_FAILURE = 4;
    int TAG_TRUE = 5;
    int TAG_FALSE = 6;
    int TAG_NULL = 7;
    int TAG_BYTE_ARRAY = 8;
    int TAG_SET = 9;
    int TAG_BYTE = 10;
    int TAG_SHORT = 11;
    int TAG_CHAR = 12;
    int TAG_INT = 13;
    int TAG_LONG = 14;
    int TAG_FLOAT = 15;
    int TAG_DOUBLE = 16;
    int TAG_STRING = 17;
    int TAG_ARRAY = 18;
    int TAG_TUPLE = 19;
    int TAG_MAP = 20;
    // --- Tag space ---
    ///
    /// Tags are VLQ-encoded ([#writeCompact]), so the tag VALUE decides its wire cost:
    /// `0..127` is 1 byte, `128..16383` is 2, `16384..2097151` is 3. The split below spends that
    /// deliberately.
    ///
    /// **SYSTEM range `0..16383` — manually assigned in [SystemTags], never reused.** Framework and
    /// Aether protocol types live here: DHT lookups, SWIM gossip, Rabia rounds, KV commands. They are
    /// enumerable, framework-owned, and do not grow with user code, so hand-assignment is tractable and
    /// buys the cheap 1-2 byte encodings for the highest-frequency traffic in the system. A tag here is
    /// a WIRE CONTRACT: once assigned it is never renumbered and never reused for a different type,
    /// because two nodes disagreeing on a tag is undiagnosable corruption rather than a clean failure.
    /// [#systemCodec] refuses to build a system registry containing a type that was never pinned.
    ///
    /// **USER range `16384..2097151` — hash-derived from the FQCN.** Slice-generated codecs live
    /// here. They grow without bound as applications add types, so they cannot be hand-assigned, and
    /// a wide space is what keeps collisions negligible: at ~100 types the birthday probability is
    /// about 0.25%, where the old single 16256-slot space was already at ~27% — and that one bit us
    /// for real (`EntityCheckpointValue` landed on `HealthHintWire`'s tag 7612 and poisoned
    /// `NodeCodecs` static init, erroring 48 unrelated tests, invisibly to the owning module's own
    /// build).
    ///
    /// The two ranges are DISJOINT, so a slice type can never collide with a system type. Two
    /// different blueprints cannot collide either: [#sliceCodec] gives each slice its own registry
    /// layered over the shared system parent, so their types never meet. The only collision left
    /// possible is between types WITHIN one blueprint, which is why that is checked at assembly time.
    int SYSTEM_TAG_MAX = 16383;
    int TAG_SPACE_SIZE = 16384;
    int USER_TAG_BASE = 16384;
    int USER_TAG_LIMIT = 1 << 21;
    int USER_TAG_SPACE_SIZE = USER_TAG_LIMIT - USER_TAG_BASE;
    // --- Stream header ---
    int MAGIC = 0xAE01;
    int FORMAT_VERSION = 1;

    static void writeHeader(ByteBuf buf) {
        buf.writeShort(MAGIC);
        buf.writeByte(FORMAT_VERSION);
    }

    static void verifyHeader(ByteBuf buf) {
        int magic = buf.readShort() & 0xFFFF;

        if (magic != MAGIC) {
            throw new IllegalArgumentException("Invalid magic: " + Integer.toHexString(magic));
        }

        int version = buf.readByte() & 0xFF;

        if (version != FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported version: " + version);
        }
    }

    // --- Nested types ---
    @FunctionalInterface
    interface TypeWriter<T> {
        void writeBody(SliceCodec codec, ByteBuf buf, T value);
    }

    @FunctionalInterface
    interface TypeReader<T> {
        T readBody(SliceCodec codec, ByteBuf buf);
    }

    /// Maps a value to its wire tag. For most types, returns the constant tag.
    /// For variant types (e.g. Boolean -> TAG_TRUE/TAG_FALSE), returns a tag based on the value.
    @FunctionalInterface
    interface TagMapper<T> {
        int tagFor(T value);
    }

    record TypeCodec<T>(Class<T> type, int tag, TagMapper<T> tagMapper, TypeWriter<T> writer, TypeReader<T> reader) {
        /// Convenience constructor for types with a constant tag.
        public TypeCodec(Class<T> type, int tag, TypeWriter<T> writer, TypeReader<T> reader) {
            this(type, tag, _ -> tag, writer, reader);
        }
    }

    // --- VLQ encoding ---
    static void writeCompact(ByteBuf buf, int value) {
        while ((value & ~0x7F) != 0) {
            buf.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }

        buf.writeByte(value);
    }

    static int readCompact(ByteBuf buf) {
        int value = 0;
        int shift = 0;
        int b;

        do {
            b = buf.readByte() & 0xFF;
            value |= (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);

        return value;
    }

    // --- Tag computation ---
    /// The wire tag for `className`: its hand-assigned [SystemTags] entry when it has one, otherwise a
    /// hash-derived tag in the USER range.
    ///
    /// Generated codecs call this at class-init, which is why the lookup lives HERE rather than in the
    /// generator: pinning a type becomes a one-line edit to one registry file, with no regenerated
    /// code, no churn across the `@Codec` annotations, and no envelope-version question.
    static int deterministicTag(String className) {
        var pinned = SystemTags.tagFor(className);

        return pinned == SystemTags.NOT_PINNED
               ? hashedTag(className)
               : pinned;
    }

    /// Hash-derived tag in the USER range for a slice-generated type.
    ///
    /// FNV-1a rather than `String.hashCode()`: our FQCNs share long prefixes
    /// (`org.pragmatica.aether.resource.entity.Entity…`), and `String.hashCode` clusters badly on
    /// exactly that shape, so it wasted a space that was already too small. FNV-1a avalanches, which
    /// gets the collision rate to the theoretical birthday bound instead of well above it.
    ///
    /// Separate from [#deterministicTag] so the hash's own properties stay testable: pinning a name in
    /// [SystemTags] must not silently turn a hash assertion into an assertion about the registry.
    static int hashedTag(String className) {
        long hash = 0xcbf29ce484222325L;

        for (var i = 0; i < className.length(); i++) {
            hash ^= className.charAt(i);
            hash *= 0x100000001b3L;
        }

        return USER_TAG_BASE + (int) Long.remainderUnsigned(hash, USER_TAG_SPACE_SIZE);
    }

    // --- String helpers ---
    static void writeString(ByteBuf buf, String value) {
        int utf8Bytes = ByteBufUtil.utf8Bytes(value);

        writeCompact(buf, utf8Bytes);
        ByteBufUtil.writeUtf8(buf, value);
    }

    static String readString(ByteBuf buf) {
        var len = readCompact(buf);
        var value = buf.toString(buf.readerIndex(), len, StandardCharsets.UTF_8);

        buf.skipBytes(len);

        return value;
    }

    // --- Body helpers for generated code ---
    @SuppressWarnings("unchecked")
    default <T> void writeBodyFor(ByteBuf buf, T value, Class<T> type) {
        var codec = (TypeCodec<T>) lookupByClass(type);

        codec.writer().writeBody(this, buf, value);
    }

    @SuppressWarnings("unchecked")
    default <T> T readBodyFor(ByteBuf buf, int tag) {
        var codec = (TypeCodec<T>) lookupByTag(tag);

        return codec.reader()
                    .readBody(this, buf);
    }

    // --- Internal lookup (package-private contract) ---
    TypeCodec<?> lookupByClass(Class<?> type);
    TypeCodec<?> lookupByTag(int tag);

    // --- Factory methods ---
    static SliceCodec sliceCodec(List<TypeCodec<?>> codecs) {
        return sliceCodec(null, codecs);
    }

    static SliceCodec sliceCodec(SliceCodec parent, List<TypeCodec<?>> codecs) {
        var byClass = new HashMap<Class<?>, TypeCodec<?>>();
        var tagArray = new TypeCodec<?>[TAG_SPACE_SIZE];
        // USER-range tags are sparse across ~2M slots, so they get a map rather than an array: a flat
        // array covering that range would be ~16MB PER SLICE, and every slice builds its own registry.
        // The map holds only what a blueprint actually declares — tens of entries, not millions.
        var userTags = new HashMap<Integer, TypeCodec<?>>();

        if (parent instanceof CodecHolder holder) {
            byClass.putAll(holder.byClass());
            copyTagArray(holder.tagArray(), tagArray);
            userTags.putAll(holder.userTags());
        }

        for (var codec : codecs) {
            byClass.put(codec.type(), codec);
            validateAndSetTag(tagArray, userTags, codec);
        }

        return new CodecHolder(Map.copyOf(byClass), tagArray, Map.copyOf(userTags));
    }

    /// Build a codec registry and validate that all required types have registered codecs.
    /// Required types come from @CodecFor declarations — the processor generates the set,
    /// and this method verifies at startup that manual codecs were actually provided.
    static SliceCodec sliceCodec(SliceCodec parent, List<TypeCodec<?>> codecs, Set<Class<?>> requiredTypes) {
        var result = sliceCodec(parent, codecs);

        validateRequiredTypes(result, requiredTypes);

        return result;
    }

    /// Build the SYSTEM codec registry — the shared parent that every slice registry layers over.
    ///
    /// Identical to [#sliceCodec(SliceCodec, List, Set)] except that it refuses a type whose tag is not
    /// hand-assigned in [SystemTags]. That refusal is what makes the pinning obligation enforceable
    /// instead of aspirational, and it is why the system set never has to be recovered by grepping for
    /// `@Codec`: adding a framework codec without a pin fails the build and the message names the type.
    ///
    /// An unpinned framework type is wrong on two counts. It pays three wire bytes on the cluster's own
    /// highest-frequency traffic, and its identity becomes hostage to a rename or a change of hash
    /// function — for a value that two nodes must agree on to communicate at all.
    static SliceCodec systemCodec(SliceCodec parent, List<TypeCodec<?>> codecs, Set<Class<?>> requiredTypes) {
        var unpinned = codecs.stream()
                             .filter(codec -> codec.tag() > SYSTEM_TAG_MAX)
                             .map(codec -> codec.type().getName().replace('$', '.'))
                             .distinct()
                             .sorted()
                             .toList();

        if (!unpinned.isEmpty()) {
            throw new IllegalStateException("System types with no hand-assigned tag: " + String.join(", ", unpinned)
                                            + ". Every type in the system registry needs a pin in SystemTags"
                                            + " (system range is [0, " + SYSTEM_TAG_MAX + "]); these fell through to the hash"
                                            + " and landed in the user range. Add each to the block for its subsystem taking"
                                            + " the next free tag — never renumber or reuse an existing one.");
        }

        return sliceCodec(parent, codecs, requiredTypes);
    }

    /// Verify that every type on the required-codec checklist has a registered codec.
    /// Call this at startup after building the full codec registry.
    ///
    /// The checklist has two sources, so the message names neither: `@CodecFor` declarations, and
    /// type arguments the slice-processor derives from resource-qualified parameters (e.g. the state
    /// type of a `DurableEntity<K, S>`). An author who never wrote `@CodecFor` can still land here,
    /// so pointing them at that annotation would send them looking for something they do not have.
    static void validateRequiredTypes(SliceCodec codec, Set<Class<?>> requiredTypes) {
        var missing = requiredTypes.stream().filter(type -> !hasCodecFor(codec, type)).toList();

        if (!missing.isEmpty()) {
            throw new IllegalStateException("Required codecs are not registered: " + missing.stream()
                                                                                            .map(Class::getName)
                                                                                            .collect(Collectors.joining(", "))
                                           + ". A codec is generated automatically for a record or an enum;"
                                           + " any other type needs a manual codec registered for it.");
        }
    }

    private static boolean hasCodecFor(SliceCodec codec, Class<?> type) {
        try {
            codec.lookupByClass(type);

            return true;
        } catch (IllegalArgumentException _) {
            return false;
        }
    }

    private static void copyTagArray(TypeCodec<?>[] source, TypeCodec<?>[] target) {
        System.arraycopy(source, 0, target, 0, source.length);
    }

    private static void validateAndSetTag(TypeCodec<?>[] tagArray,
                                          Map<Integer, TypeCodec<?>> userTags,
                                          TypeCodec<?> codec) {
        int tag = codec.tag();

        if (tag < 0 || tag >= USER_TAG_LIMIT) {
            throw new IllegalArgumentException("Tag %d for %s is out of range [0, %d)".formatted(tag,
                                                                                                 codec.type().getName(),
                                                                                                 USER_TAG_LIMIT));
        }

        var existing = tag <= SYSTEM_TAG_MAX
                       ? tagArray[tag]
                       : userTags.get(tag);

        if (existing != null && !existing.type().equals(codec.type())) {
            throw new IllegalArgumentException("Tag collision: tag %d claimed by both %s and %s".formatted(tag,
                                                                                                           existing.type()
                                                                                                                   .getName(),
                                                                                                           codec.type()
                                                                                                                .getName()));
        }

        if (tag <= SYSTEM_TAG_MAX) {
            tagArray[tag] = codec;
        } else {
            userTags.put(tag, codec);
        }
    }
}

final class CodecHolder implements SliceCodec {
    private final Map<Class<?>, SliceCodec.TypeCodec<?>> byClass;
    private final SliceCodec.TypeCodec<?>[] tagArray;
    private final Map<Integer, SliceCodec.TypeCodec<?>> userTags;
    private final ConcurrentHashMap<Class<?>, SliceCodec.TypeCodec<?>> classCache;
    private volatile SliceCodec.TypeCodec<?> lastLookup;

    CodecHolder(Map<Class<?>, SliceCodec.TypeCodec<?>> byClass,
                SliceCodec.TypeCodec<?>[] tagArray,
                Map<Integer, SliceCodec.TypeCodec<?>> userTags) {
        this.byClass = byClass;
        this.tagArray = tagArray;
        this.userTags = userTags;
        this.classCache = new ConcurrentHashMap<>(byClass);
    }

    Map<Class<?>, SliceCodec.TypeCodec<?>> byClass() {
        return byClass;
    }

    SliceCodec.TypeCodec<?>[] tagArray() {
        return tagArray;
    }

    Map<Integer, SliceCodec.TypeCodec<?>> userTags() {
        return userTags;
    }

    @Override
    public SliceCodec.TypeCodec<?> lookupByClass(Class<?> type) {
        var cached = lastLookup;

        if (cached != null && cached.type() == type) {
            return cached;
        }

        var codec = classCache.get(type);

        if (codec != null) {
            lastLookup = codec;

            return codec;
        }
        // Supertype fallback -- handles collection implementations (e.g. ImmutableCollections$ListN -> List)
        codec = findBySupertype(type);
        lastLookup = codec;

        return codec;
    }

    /// Resolve a codec for a type with no exact registration by walking the registered supertypes.
    ///
    /// Resolution is deterministic and independent of `byClass` iteration order, so every node in a
    /// cluster (and every restart of the same node) reaches the same answer for the same type — the
    /// prior "first assignable entry wins" behaviour could cache a DIFFERENT codec per process and
    /// produce cross-node payloads that decode as the wrong type instead of failing (#529).
    ///
    /// The candidate set is reduced to the MOST SPECIFIC registered supertypes: a candidate survives
    /// only when no other candidate is a strict subtype of it. That alone resolves the documented
    /// case (`ImmutableCollections$ListN` -> `List`). When several mutually unrelated candidates
    /// remain, one documented tie-break applies: a class candidate beats interface candidates,
    /// which is well-founded because Java's single-inheritance class chain leaves at most one
    /// minimal class. Anything still ambiguous fails loudly naming the type and every competing
    /// candidate — an encoding decision is not a defaultable value.
    private SliceCodec.TypeCodec<?> findBySupertype(Class<?> type) {
        var minimal = mostSpecific(assignableCandidates(type));

        if (minimal.isEmpty()) {
            throw new IllegalArgumentException("No codec registered for class: " + type.getName());
        }

        var resolved = preferClassCandidate(minimal);

        if (resolved.size() > 1) {
            throw new IllegalArgumentException(ambiguousCandidates(type, resolved));
        }

        var codec = resolved.getFirst();

        classCache.put(type, codec);

        return codec;
    }

    /// Every registered codec whose type is a supertype of `type`, ordered by type name so that both
    /// the resolution and the ambiguity message are stable across processes.
    private List<SliceCodec.TypeCodec<?>> assignableCandidates(Class<?> type) {
        return byClass.values()
                      .stream()
                      .filter(codec -> codec.type().isAssignableFrom(type))
                      .sorted(Comparator.comparing(codec -> codec.type().getName()))
                      .toList();
    }

    private static List<SliceCodec.TypeCodec<?>> mostSpecific(List<SliceCodec.TypeCodec<?>> candidates) {
        return candidates.stream().filter(candidate -> isMinimal(candidate, candidates)).toList();
    }

    private static boolean isMinimal(SliceCodec.TypeCodec<?> candidate, List<SliceCodec.TypeCodec<?>> candidates) {
        return candidates.stream().noneMatch(other -> isStrictSubtype(other.type(), candidate.type()));
    }

    private static boolean isStrictSubtype(Class<?> subtype, Class<?> supertype) {
        return !subtype.equals(supertype) && supertype.isAssignableFrom(subtype);
    }

    /// Documented tie-break for equally specific candidates: prefer the class over unrelated
    /// interfaces. Java's single-inheritance class chain is totally ordered, so at most one class
    /// candidate can be minimal; when none is, the candidates stay ambiguous.
    private static List<SliceCodec.TypeCodec<?>> preferClassCandidate(List<SliceCodec.TypeCodec<?>> minimal) {
        var classes = minimal.stream().filter(codec -> !codec.type().isInterface()).toList();

        return classes.size() == 1
               ? classes
               : minimal;
    }

    private static String ambiguousCandidates(Class<?> type, List<SliceCodec.TypeCodec<?>> candidates) {
        var names = candidates.stream()
                              .map(codec -> codec.type().getName())
                              .collect(Collectors.joining(", "));

        return "Ambiguous codec resolution for class %s: equally specific registered supertypes [%s]. Register an explicit codec for %s.".formatted(type.getName(),
                                                                                                                                                    names,
                                                                                                                                                    type.getName());
    }

    @Override
    public SliceCodec.TypeCodec<?> lookupByTag(int tag) {
        // System tags keep the flat-array index — they carry the cluster's own protocol traffic and
        // are the hot path. User tags are sparse and take a map lookup.
        var codec = tag >= 0 && tag < tagArray.length
                    ? tagArray[tag]
                    : userTags.get(tag);

        if (codec == null) {
            throw new IllegalArgumentException("No codec registered for tag: " + tag);
        }

        return codec;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> void write(ByteBuf byteBuf, T object) {
        if (object == null) {
            SliceCodec.writeCompact(byteBuf, SliceCodec.TAG_NULL);

            return;
        }

        var type = (Class<T>) object.getClass();
        var codec = (TypeCodec<T>) lookupByClass(type);

        SliceCodec.writeCompact(byteBuf,
                                codec.tagMapper().tagFor(object));
        codec.writer().writeBody(this, byteBuf, object);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T read(ByteBuf byteBuf) {
        var tag = SliceCodec.readCompact(byteBuf);
        var codec = (TypeCodec<T>) lookupByTag(tag);

        return codec.reader()
                    .readBody(this, byteBuf);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }

        if (! (obj instanceof CodecHolder other)) {
            return false;
        }

        return byClass.equals(other.byClass) && Arrays.equals(tagArray, other.tagArray);
    }

    @Override
    public int hashCode() {
        return 31 * byClass.hashCode() + Arrays.hashCode(tagArray);
    }

    @Override
    public String toString() {
        return "CodecHolder[byClass=%s, tagCount=%d]".formatted(byClass.keySet(), classCache.size());
    }
}
