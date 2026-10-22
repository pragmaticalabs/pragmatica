// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.state.kvstore;

import java.util.List;


/// A filtered image of the store taken under ONE store monitor: the encoded map, the incarnation tag, the revision and the anchor are
/// mutually consistent. The image holds exactly the filtered effects of batches with `slot < revision`.
public record ReplicaSnapshot(byte[] bytes, IncarnationTag tag, long revision, List<AnchorPair> anchor) {}
