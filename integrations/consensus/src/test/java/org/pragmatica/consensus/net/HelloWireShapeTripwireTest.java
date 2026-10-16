/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */

package org.pragmatica.consensus.net;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/// Tripwire for the inventory row U-007 / lane D D-08 (cluster T assessment, rc4 `7c786db0d3`): `Hello` has no version,
/// capability, generation or attach-verdict field, and it has already grown twice (`bootToken`, `intendedPeer`) under one codec
/// tag with no old-versus-new compatibility test anywhere. The link-arbitration overhaul plans a connection generation in
/// `Hello` (1b-link), which is a wire change on a type whose compatibility nothing asserts. Pre-GA, breaking wire changes are ruled
/// fine (R-024); this tripwire exists so the change cannot land without someone deciding that on purpose.
///
/// There is no disabled twin: the real assertion is a rolling-upgrade interop test (an old-shape `Hello` decodes under the new codec,
/// and the reverse) that cannot be written until the new shape exists. The failure message says to write it with the change.
class HelloWireShapeTripwireTest {
    @Test
    void hello_carriesExactlyTheFiveFieldsOfRc4_noVersionNoGeneration() {
        var components = Arrays.stream(NetworkMessage.Hello.class.getRecordComponents()).map(component -> component.getName()).toList();

        assertThat(components)
            .as("TRIPWIRE U-007: Hello changed shape. Add the old-shape/new-shape decode test for the rolling-upgrade window (or record the "
                + "pre-GA ruling that breaks it) in the same change, then update this list.")
            .containsExactly("sender", "address", "labels", "bootToken", "intendedPeer");
    }
}
