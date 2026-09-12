// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.aether.slice.StreamConfig;


/// Provisioning extension that maps a slice's local stream alias to the engine key its blueprint
/// declaration was deployed under (#1040).
///
/// Supplied by `AetherNode` over the cluster KV-Store (`BlueprintStreamAddresses`), which is where the
/// deploy-time alias→address bindings live; this module cannot reach that state itself, and stating the
/// dependency as an extension keeps it out of the factories. Same shape and same reason as
/// [StreamPublisherFactory.GovernorResolver]: a node-supplied capability the publish path uses when it
/// is running inside a real node and does without elsewhere.
///
/// ABSENT IN TEST / FORGE / MINIMAL RUNTIMES, BY DESIGN — see [#qualify]. There is no deployment behind
/// those, so there is no second spelling for the engine key to disagree with.
@FunctionalInterface
public interface StreamAddressResolver {
    /// The engine key for `alias` as declared by the slice deployed under `sliceId`
    /// (`groupId:artifactId:version`), or `alias` itself when no binding resolves.
    String engineKeyFor(String sliceId, String alias);

    /// `config` with its name rewritten to the engine key, or `config` unchanged when the context
    /// carries no resolver or no slice identity.
    ///
    /// The config binder derives `StreamConfig.name()` from the `resources.toml` section suffix
    /// (`ProviderBasedConfigService.deriveNameFromSectionSuffix`), so what arrives here is the bare
    /// local alias. Rewriting it once, at the head of provisioning, is what makes the rest of the
    /// engine path — `createStream`, the `StreamConfigKey` commit, partition routing, cursors,
    /// consumer groups — key by the same string the management routes resolve through
    /// `StreamManager.engineKey`. That substitution happens BEFORE anything is committed, so no
    /// stream is ever persisted under both spellings.
    ///
    /// This mirrors what the durable-topic path has always done: `DurableTopicNames.topicStream`
    /// embeds the fully-qualified address into the engine's name (`topic:<ns:topic:version>`) instead
    /// of passing the declared alias through. Streams were the one declared resource that did not.
    static StreamConfig qualify(StreamConfig config, ProvisioningContext context) {
        return context.extension(StreamAddressResolver.class)
                      .flatMap(resolver -> context.extension(String.class)
                                                  .map(sliceId -> resolver.engineKeyFor(sliceId, config.name())))
                      .map(config::withName)
                      .or(config);
    }
}
