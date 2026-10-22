// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.sharedlist;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.type.TypeToken;

import static org.assertj.core.api.Assertions.assertThat;

/// Container roots are de-duplicated by type: three routes with a `List<Item>` body (one also returning it) give one
/// `CONTAINERS` entry and one container codec. A duplicate would be a second `Map.ofEntries` key, which throws in the
/// holder's initialiser and breaks the whole slice.
class SharedContainerRootTest {
    @Test
    void routesSharingAListBody_getOneContainerEntry_andTheHolderInitialises() throws IOException {
        var listType = new TypeToken<List<Item>>() {}.token();

        assertThat(SharedListSliceRoutesJsonCodecs.CONTAINERS).hasSize(1)
                                                             .containsKey(listType);
        assertThat(SharedListSliceRoutesJsonCodecs.CODECS).containsOnlyKeys(Item.class);
        assertThat(SharedListSliceRoutesJsonCodecs.CONTAINERS.get(listType)
                                                             .decode("[{\"name\":\"a\",\"quantity\":2}]")
                                                             .unwrap()).isEqualTo(List.of(new Item("a", 2)));
        assertThat(Files.readString(generatedFile("SharedListSliceRoutesJsonCodecs.java"))).contains("interface ListRootJson {")
                                                                                          .doesNotContain("ListRootJson2");
    }

    private static Path generatedFile(String name) {
        var relative = Paths.get("target", "generated-sources", "annotations", "com", "example", "sharedlist", name);
        var moduleDir = Paths.get(System.getProperty("user.dir"));
        var candidate = moduleDir.resolve(relative);

        return Files.exists(candidate)
               ? candidate
               : moduleDir.resolve(Path.of("jbct", "slice-processor-tests")).resolve(relative);
    }
}
