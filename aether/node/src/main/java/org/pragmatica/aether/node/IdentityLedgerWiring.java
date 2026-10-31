// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.pragmatica.aether.config.StorageConfig;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.BootTokens;
import org.pragmatica.consensus.net.IdentityLedger;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;
import org.pragmatica.utility.warning.OperatorWarnings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Where this node keeps its identity ledger (#2429), and the boot steps that attach it to the node's
/// [BootTokens] registry before any admission path can consult it.
///
/// **Location.** `<artifacts disk path sibling>/identity/<nodeId>/ledger`: the convention of the stream data
/// directory, so the file sits on the same durable `${data.dir}` volume, is distinct per node when several nodes
/// share a process, and is stable across a restart of the same node id. Deleting it makes the node WIPED; losing it
/// on every core is a new cluster. The in-JVM harness states its own file through `ProtocolConfig`.
///
/// **Operator-set versus synthesized.** A data path the operator wrote (`StorageConfig.diskPathExplicit`, also when
/// it equals the built-in default), or one the harness stated, must be usable on EVERY boot: a ledger directory that
/// cannot be created, written or forced fails the boot with a named cause ([#verifyBootable]), whether or not a ledger
/// file already exists there. A synthesized default degrades instead: the node runs without the restart protection, with
/// a WARN, the `identity_ledger_durable` gauge at 0 and an `identity-ledger-not-durable` event; the protection is OFF on
/// that node.
public sealed interface IdentityLedgerWiring {
    Logger log = LoggerFactory.getLogger(IdentityLedgerWiring.class);

    /// The ledger file, and whether its location was stated (operator or harness) rather than synthesized.
    record Location(Path file, boolean stated) {}

    /// A stated ledger location that cannot be used: the boot is refused with this cause.
    record LedgerUnusable(Path file, Cause origin, String message) implements Cause, Cause.Wrapped {
        static LedgerUnusable ledgerUnusable(Path file, Cause origin) {
            return new LedgerUnusable(file,
                                      origin,
                                      "The identity ledger at " + file
                                     + " cannot be created or forced to disk; the data path is set explicitly, so the node does not start without it."
                                     + " Fix the volume or permissions of this path, or remove the explicit disk_path to run without the restart protection.");
        }
    }

    static Location location(AetherNodeConfig config) {
        return location(config.self(),
                        config.protocol().identityLedgerFile(),
                        config.storageConfig());
    }

    /// The location a node with these inputs uses: the harness-stated file when there is one, else the file derived from the
    /// artifacts disk path.
    static Location location(NodeId self, Option<Path> stated, Map<String, StorageConfig> storage) {
        return stated.map(file -> new Location(file, true))
                     .or(() -> derivedLocation(self, storage));
    }

    private static Location derivedLocation(NodeId self, Map<String, StorageConfig> storage) {
        var defaults = StorageConfig.storageConfig();
        var artifacts = Option.option(storage.get("artifacts")).or(defaults);
        var file = Path.of(artifacts.diskPath()).resolveSibling("identity").resolve(self.id()).resolve("ledger");

        return new Location(file, artifacts.diskPathExplicit());
    }

    /// The boot gate: a stated location must be usable. Nothing is checked for a synthesized one.
    static Result<Unit> verifyBootable(AetherNodeConfig config) {
        return verifyBootable(location(config));
    }

    static Result<Unit> verifyBootable(Location location) {
        return location.stated()
               ? unusableCause(location).fold(Result::unitResult,
                                              cause -> LedgerUnusable.ledgerUnusable(location.file(),
                                                                                     cause)
                                                                     .result())
               : Result.unitResult();
    }

    private static Option<Cause> unusableCause(Location location) {
        return IdentityLedger.identityLedger(location.file(),
                                             IdentityLedger.DurableWriter.fileOps())
                             .open(isNewDirectory(location))
                             .unwritable();
    }

    /// Attach the ledger to the registry and load it, before the node starts: admission answers UNAVAILABLE in between.
    static Unit wire(AetherNodeConfig config, BootTokens tokens, OperatorWarningSink sink) {
        tokens.seats(config.self(), configuredCoreSeats(config));

        return wire(location(config), config.self(), tokens, sink);
    }

    /// The configured core seats a WIPED node counts its majority over (#2429, ID-1c): the core-role entries of `topology.coreNodes`,
    /// which is the roster `cluster.genesis_voters` names when that is set.
    static Set<NodeId> configuredCoreSeats(AetherNodeConfig config) {
        return config.topology()
                     .coreNodes()
                     .stream()
                     .filter(peer -> "core".equalsIgnoreCase(peer.labels().getOrDefault(NodeInfo.LABEL_ROLE, "core")))
                     .map(NodeInfo::id)
                     .collect(Collectors.toUnmodifiableSet());
    }

    /// [#wire], and the apply listener that writes each retirement the cluster commits to this node's own ledger (ID-1b).
    static IdentityRecordPersister wirePersisting(AetherNodeConfig config,
                                                  BootTokens tokens,
                                                  OperatorWarningSink sink) {
        tokens.seats(config.self(), configuredCoreSeats(config));

        return IdentityRecordPersister.identityRecordPersister(wireLedger(location(config), config.self(), tokens, sink),
                                                               tokens);
    }

    static Unit wire(Location location, NodeId self, BootTokens tokens, OperatorWarningSink sink) {
        wireLedger(location, self, tokens, sink);

        return Unit.unit();
    }

    private static IdentityLedger wireLedger(Location location,
                                             NodeId self,
                                             BootTokens tokens,
                                             OperatorWarningSink sink) {
        var ledger = IdentityLedger.identityLedger(location.file(), IdentityLedger.DurableWriter.fileOps());
        var subject = self.id();

        tokens.attachLedger(ledger);
        var loading = ledger.open(isNewDirectory(location));
        // The listener goes in BEFORE the preload: loading the offers journal of a wiped node can already report an unusable file.
        ledger.onDurabilityChange(change -> raiseTransition(sink, subject, change));
        tokens.preload(loading.facts(), loading.origin() == IdentityLedger.Origin.WIPED);
        raiseAtBoot(sink, subject, location, loading);

        return ledger;
    }

    private static boolean isNewDirectory(Location location) {
        return Files.notExists(location.file().toAbsolutePath().getParent());
    }

    private static Unit raiseAtBoot(OperatorWarningSink sink,
                                    String subject,
                                    Location location,
                                    IdentityLedger.Loading loading) {
        if (loading.origin() == IdentityLedger.Origin.WIPED) {
            return OperatorWarnings.raise(log,
                                          sink,
                                          OperatorWarningCode.IDENTITY_LEDGER_NOT_DURABLE,
                                          subject,
                                          "The identity ledger {} is missing on a node that is not new, so this node cannot tell which"
                                         + " NodeIds it retired before. It admits no new peer until the offers of a majority of the configured core seats have reached it"
                                         + " (the Hello exchange carries them), so it stays ISOLATED from the cluster while too few seats are reachable and restart protection is off on it until then."
                                         + " The safe remedy, when none is reachable, is to stop it and rejoin it under a NEW node id."
                                         + " Deleting the directory {} starts it as a new node and joins the cluster, but turns the protection off for this node.",
                                          location.file(),
                                          location.file().getParent());
        }

        return loading.unwritable()
                      .fold(Unit::unit,
                            cause -> OperatorWarnings.raise(log,
                                                            sink,
                                                            OperatorWarningCode.IDENTITY_LEDGER_NOT_DURABLE,
                                                            subject,
                                                            "The identity ledger {} cannot be written ({}): this node runs WITHOUT the"
                                                           + " restart protection for retired NodeIds, as before the ledger existed."
                                                           + " Put the data path on a writable durable volume.",
                                                            location.file(),
                                                            cause.message()));
    }

    private static Unit raiseTransition(OperatorWarningSink sink, String subject, Option<Cause> change) {
        return change.fold(() -> OperatorWarnings.raise(log,
                                                        sink,
                                                        OperatorWarningCode.IDENTITY_LEDGER_DURABLE,
                                                        subject,
                                                        "The identity ledger is written again; restart protection for retired NodeIds is back on."),
                           cause -> OperatorWarnings.raise(log,
                                                           sink,
                                                           OperatorWarningCode.IDENTITY_LEDGER_NOT_DURABLE,
                                                           subject,
                                                           "The identity ledger or its offers journal failed ({}): retirements from now on will not survive a restart until a write succeeds.",
                                                           cause.message()));
    }

    record unused() implements IdentityLedgerWiring {}
}
