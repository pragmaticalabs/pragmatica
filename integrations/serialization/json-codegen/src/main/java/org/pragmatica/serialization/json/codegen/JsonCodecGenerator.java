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
package org.pragmatica.serialization.json.codegen;

import javax.annotation.processing.Filer;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import java.io.PrintWriter;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;


/// Generates reflection-free JSON codecs for a set of root types. Shared by the codec processor (one `@Json`
/// type per entry point, [#writeEntryPoint]) and the slice processor (HTTP route request and response types in
/// one holder, [#write]), so both emit identical codecs. A root the generator cannot handle is reported, not
/// generated; its users fall back.
public final class JsonCodecGenerator {
    private final Filer filer;
    private final JsonTypeAnalyzer analyzer;
    private final List<TypeMirror> roots = new ArrayList<>();
    private final String packageName;

    /// `packageName` is where the holder will be written; it decides which types and factories are reachable.
    public JsonCodecGenerator(ProcessingEnvironment env, String packageName) {
        this(env.getFiler(), env.getElementUtils(), env.getTypeUtils(), packageName);
    }

    public JsonCodecGenerator(Filer filer, Elements elements, Types types, String packageName) {
        this.filer = filer;
        this.packageName = packageName;
        this.analyzer = new JsonTypeAnalyzer(elements, types, packageName);
    }

    /// Adds a root type; empty when supported, otherwise why it is not.
    public Option<String> addRoot(TypeMirror type) {
        if (analyzer.analyzeRoot(type).isPresent()) {
            roots.add(type);

            return Option.none();
        }

        return Option.some(analyzer.reason(type));
    }

    public boolean hasCodecs() {
        return ! analyzer.records()
                         .isEmpty() || !analyzer.enums()
                                                .isEmpty();
    }

    public List<TypeMirror> roots() {
        return List.copyOf(roots);
    }

    public String source(String holderName, String generatedBy) {
        return new JsonCodecEmitter(analyzer).emit(packageName, holderName, generatedBy);
    }

    /// The entry point of `root` (the only root added), named `holderName`.
    public String entryPointSource(String holderName, TypeElement root, String generatedBy) {
        return new JsonCodecEmitter(analyzer).emitEntryPoint(packageName, holderName, root, generatedBy);
    }

    /// Writes the holder `packageName.holderName` through the Filer.
    public Result<Unit> write(String holderName, String generatedBy, Element... originatingElements) {
        return writeSource(holderName, source(holderName, generatedBy), originatingElements);
    }

    /// Writes the entry point `packageName.holderName` of `root` (the only root added) through the Filer.
    public Result<Unit> writeEntryPoint(String holderName, TypeElement root, String generatedBy) {
        return writeSource(holderName, entryPointSource(holderName, root, generatedBy), root);
    }

    private Result<Unit> writeSource(String holderName, String source, Element... originatingElements) {
        var qualified = packageName.isEmpty()
                        ? holderName
                        : packageName + "." + holderName;

        return Result.lift(Causes::fromThrowable,
                           () -> filer.createSourceFile(qualified, originatingElements))
                     .flatMap(file -> Result.lift(Causes::fromThrowable, file::openWriter))
                     .map(writer -> print(writer, source));
    }

    private static Unit print(Writer target, String source) {
        try (var writer = new PrintWriter(target)) {
            writer.print(source);
        }

        return Unit.unit();
    }
}
