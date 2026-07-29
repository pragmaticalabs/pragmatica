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

import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;

import io.netty.buffer.ByteBuf;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// A [SliceCodec] whose delegate is supplied once, after construction.
///
/// A deployed slice's codec is only computable once the slice INSTANCE exists, but the resources
/// that have to encode with it — stream publishers and readers, distributed cache and idempotency
/// backends — are provisioned DURING slice construction. Without a late-bound holder the only
/// codec available at provisioning time is the node-wide one, which knows framework types and
/// nothing the application declared; that is the defect behind #526. The resource captures this
/// holder, the loader binds the real slice codec the moment the slice is built, and every use
/// after that resolves application types and framework types alike.
///
/// Nothing degrades quietly. A codec operation attempted before binding throws and names both the
/// slice and the subject of the lookup: an unbound codec means the loader wired things in the
/// wrong order, which is a defect to surface, not a condition to tolerate. Exceptions rather than
/// `Result` here follow the [Serializer] contract — see its header on why encoding failures are
/// fatal misconfiguration.
public record DeferredSliceCodec(String label, AtomicReference<Option<SliceCodec>> delegate) implements SliceCodec {
    /// Create an unbound holder labelled with the slice it serves — the label is what makes the
    /// unbound failure actionable, so pass the slice artifact coordinate.
    public static DeferredSliceCodec deferredSliceCodec(String label) {
        return new DeferredSliceCodec(label, new AtomicReference<>(none()));
    }

    /// Supply the codec every captured resource will use from now on. Called once, by the slice
    /// loader, immediately after the slice instance is constructed.
    @Contract
    public void bind(SliceCodec codec) {
        delegate.set(some(codec));
    }

    public boolean isBound() {
        return delegate.get().isPresent();
    }

    @Override
    public TypeCodec<?> lookupByClass(Class<?> type) {
        return resolve(type.getName()).lookupByClass(type);
    }

    @Override
    public TypeCodec<?> lookupByTag(int tag) {
        return resolve("wire tag " + tag).lookupByTag(tag);
    }

    @Override
    public <T> void write(ByteBuf byteBuf, T object) {
        resolve(subjectOf(object)).write(byteBuf, object);
    }

    @Override
    public <T> T read(ByteBuf byteBuf) {
        return resolve("a value read from the wire").read(byteBuf);
    }

    private SliceCodec resolve(String subject) {
        return delegate.get().or(() -> unbound(subject));
    }

    private SliceCodec unbound(String subject) {
        throw new IllegalStateException("Codec for slice %s was used for %s before it was bound. The slice codec is bound right after the slice instance is created; a resource reaching it earlier means the load order is wrong.".formatted(label,
                                                                                                                                                                                                                                            subject));
    }

    private static String subjectOf(Object object) {
        return Option.option(object)
                     .map(value -> value.getClass().getName())
                     .or("null");
    }

    @Override
    public String toString() {
        return "DeferredSliceCodec[label=%s, bound=%s]".formatted(label, isBound());
    }
}
