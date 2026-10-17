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

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.Element;
import javax.lang.model.type.TypeMirror;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/// Generates one holder of reflection-free JSON codecs for a set of root types. Shared by the codec
/// processor (`@Json` types) and the slice processor (HTTP route request and response types), so both emit
/// identical codecs. A root the generator cannot handle is reported, not generated; its users fall back.
public final class JsonCodecGenerator {
    private final ProcessingEnvironment env;
    private final JsonTypeAnalyzer analyzer;
    private final List<TypeMirror> roots = new ArrayList<>();

    public JsonCodecGenerator(ProcessingEnvironment env) {
        this.env = env;
        this.analyzer = new JsonTypeAnalyzer(env.getElementUtils(), env.getTypeUtils());
    }

    /// Adds a root type; empty when supported, otherwise why it is not.
    public Optional<String> addRoot(TypeMirror type) {
        if (analyzer.analyze(type).isPresent()) {
            roots.add(type);
            return Optional.empty();
        }
        return Optional.of(analyzer.reason(type));
    }

    public boolean hasCodecs() {
        return !analyzer.records().isEmpty() || !analyzer.enums().isEmpty();
    }

    public List<TypeMirror> roots() {
        return List.copyOf(roots);
    }

    public String source(String packageName, String holderName, String generatedBy) {
        return new JsonCodecEmitter(analyzer).emit(packageName, holderName, generatedBy);
    }

    /// Writes `packageName.holderName` through the Filer.
    public void write(String packageName, String holderName, String generatedBy, Element... originatingElements) throws IOException {
        var qualified = packageName.isEmpty()
                        ? holderName
                        : packageName + "." + holderName;
        var file = env.getFiler().createSourceFile(qualified, originatingElements);

        try (var writer = new PrintWriter(file.openWriter())) {
            writer.print(source(packageName, holderName, generatedBy));
        }
    }
}
