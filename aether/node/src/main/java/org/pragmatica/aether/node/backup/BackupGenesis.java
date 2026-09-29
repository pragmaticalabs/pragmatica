// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.backup;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import org.pragmatica.aether.node.ClusterIncarnation;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterIncarnationValue;
import org.pragmatica.aether.slice.kvstore.BackupEntryCodec.BackupHeader;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.HttpStatusAware;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn3;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.utility.ULID;


/// `aether backup declare-genesis` (#1532): the operator's statement that this cluster's state — not the
/// backup already there — is the one to keep.
///
/// A freshly started cluster mints its own lineage at incarnation 1 and is GATED while the backup head
/// belongs to another lineage. Declaring genesis moves this cluster's incarnation past both its own and the
/// head's ([ClusterIncarnation#superseding]), and
/// the next backup flush then supersedes it as a fast-forward (the old lineage stays in git history).
///
/// It refuses whenever the head is not another lineage's: a head of this cluster's own lineage is either
/// already this cluster's backup, or AHEAD of it — in which case the right action is a restore (#1533),
/// and declaring genesis would throw that state away.
public record BackupGenesis(KvBackupService service) {
    public static BackupGenesis backupGenesis(KvBackupService service) {
        return new BackupGenesis(service);
    }

    /// The result of a declaration: this cluster's lineage at its new incarnation, and the head it
    /// supersedes.
    public record GenesisDeclared(String lineageId,
                                  long incarnation,
                                  String supersededLineageId,
                                  long supersededIncarnation) {
        public static GenesisDeclared genesisDeclared(String lineageId,
                                                      long incarnation,
                                                      String supersededLineageId,
                                                      long supersededIncarnation) {
            return new GenesisDeclared(lineageId, incarnation, supersededLineageId, supersededIncarnation);
        }
    }

    /// Why a declaration was refused. Each is a conflict with the backup's current state, or a node
    /// that cannot act on it.
    public sealed interface DeclareGenesisError extends Cause, HttpStatusAware {
        enum General implements DeclareGenesisError {
            NOT_LEADER("Only the leader can declare genesis; retry against the leader", HttpStatus.SERVICE_UNAVAILABLE),
            NO_INCARNATION("This cluster has not minted its incarnation yet; retry shortly",
                           HttpStatus.SERVICE_UNAVAILABLE),
            NOTHING_TO_SUPERSEDE("The backup is empty — this cluster's first flush establishes its lineage; nothing to declare",
                                 HttpStatus.CONFLICT),
            NOT_COMMITTED("The new incarnation did not commit (a concurrent write won); re-read the backup status and retry",
                          HttpStatus.CONFLICT);
            private final String message;
            private final HttpStatus status;
            General(String message, HttpStatus status) {
                this.message = message;
                this.status = status;
            }
            @Override
            public String message() {
                return message;
            }
            @Override
            public HttpStatus httpStatus() {
                return status;
            }
        }

        /// The head is this cluster's own lineage and is not ahead of it: this cluster's backup already.
        record SameLineage(String lineageId, String message) implements DeclareGenesisError {
            static final Fn1<SameLineage, String> FACTORY = Causes.forOneValue("The backup head already belongs to this cluster's lineage %s; nothing to supersede",
                                                                               SameLineage::new);

            @Override
            public HttpStatus httpStatus() {
                return HttpStatus.CONFLICT;
            }
        }

        /// The head is this cluster's own lineage and AHEAD of it: restore it instead.
        record RemoteIsNewer(String lineageId, long incarnation, long revision, String message) implements DeclareGenesisError {
            static final Fn3<RemoteIsNewer, String, Long, Long> FACTORY = Causes.forThreeValues("The backup head of this cluster's lineage %s is newer (incarnation %d, revision %d) than this cluster; restore it instead of declaring genesis",
                                                                                                RemoteIsNewer::new);

            @Override
            public HttpStatus httpStatus() {
                return HttpStatus.CONFLICT;
            }
        }

        /// The incarnation committed but the declaration did not reach the backup; the command is safe to
        /// repeat — a re-run finds the declaration it already committed and only retries the publish.
        record DeclarationNotPublished(Cause origin, String message) implements DeclareGenesisError, Cause.Wrapped {
            static final Fn1<DeclarationNotPublished, Cause> FACTORY = Causes.forOneValue("The genesis declaration could not be written to the backup: %s; re-run the command",
                                                                                          DeclarationNotPublished::new);

            @Override
            public HttpStatus httpStatus() {
                return HttpStatus.SERVICE_UNAVAILABLE;
            }
        }

        record HeadUnreadable(Cause origin, String message) implements DeclareGenesisError, Cause.Wrapped {
            static final Fn1<HeadUnreadable, Cause> FACTORY = Causes.forOneValue("The backup head could not be read: %s",
                                                                                 HeadUnreadable::new);

            @Override
            public HttpStatus httpStatus() {
                return HttpStatus.SERVICE_UNAVAILABLE;
            }
        }
    }

    public Promise<GenesisDeclared> declare(Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier) {
        if (!service.isLeader()) {
            return DeclareGenesisError.General.NOT_LEADER.promise();
        }

        return ClusterIncarnation.committed(service.kvStore())
                                 .map(current -> readHeadThenSupersede(current, applier))
                                 .or(DeclareGenesisError.General.NO_INCARNATION::promise);
    }

    private Promise<GenesisDeclared> readHeadThenSupersede(ClusterIncarnationValue current,
                                                           Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier) {
        return service.onWorker(service::currentHead)
                      .mapError(DeclareGenesisError.HeadUnreadable.FACTORY::apply)
                      .flatMap(head -> supersede(current, head, applier));
    }

    private Promise<GenesisDeclared> supersede(ClusterIncarnationValue current,
                                               Option<BackupHeader> head,
                                               Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier) {
        return head.map(existing -> supersedeExisting(current, existing, applier))
                   .or(DeclareGenesisError.General.NOTHING_TO_SUPERSEDE::promise);
    }

    private Promise<GenesisDeclared> supersedeExisting(ClusterIncarnationValue current,
                                                       BackupHeader head,
                                                       Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier) {
        if (head.lineageId().equals(current.lineageId())) {
            return refuseSameLineage(current, head);
        }

        return service.onWorker(service::localDeclaration)
                      .mapError(DeclareGenesisError.DeclarationNotPublished.FACTORY::apply)
                      .flatMap(pending -> resumeOrSupersede(current, head, pending, applier));
    }

    /// Re-runnable from any intermediate state: when an earlier run already moved this cluster past the
    /// head and committed the matching declaration locally (its push, or the flush after it, failed),
    /// the incarnation step is skipped and only the publish is retried — a re-run never bumps the
    /// incarnation a second time.
    private Promise<GenesisDeclared> resumeOrSupersede(ClusterIncarnationValue current,
                                                       BackupHeader head,
                                                       Option<BackupDecision.Declaration> pending,
                                                       Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier) {
        var alreadyDeclared = pending.filter(declaration -> declaration.equals(BackupDecision.Declaration.declaration(current.lineageId(),
                                                                                                                      current.incarnation())))
                                     .filter(_ -> current.incarnation() > head.incarnation())
                                     .isPresent();

        return alreadyDeclared
               ? publish(current, head)
               : commitSupersede(current, head, applier);
    }

    private Promise<GenesisDeclared> commitSupersede(ClusterIncarnationValue current,
                                                     BackupHeader head,
                                                     Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> applier) {
        var next = ClusterIncarnation.superseding(current, head.incarnation(), ULID.ulid().encoded());
        var transactionId = "declare-genesis:" + UUID.randomUUID();

        return service.kvStore()
                      .getTyped(LeaderKey.INSTANCE, LeaderValue.class)
                      .async(DeclareGenesisError.General.NOT_LEADER)
                      .flatMap(leader -> applier.apply(ClusterIncarnation.supersedeCommands(leader,
                                                                                            transactionId,
                                                                                            current,
                                                                                            next)))
                      .flatMap(results -> confirm(results, transactionId, next, head));
    }

    private Promise<GenesisDeclared> refuseSameLineage(ClusterIncarnationValue current, BackupHeader head) {
        var ours = BackupHeader.backupHeader(current.lineageId(),
                                             current.incarnation(),
                                             service.kvStore().committedRevision());

        return head.isAhead(ours)
               ? DeclareGenesisError.RemoteIsNewer.FACTORY.apply(head.lineageId(),
                                                                 head.incarnation(),
                                                                 head.revision())
                                                          .promise()
               : DeclareGenesisError.SameLineage.FACTORY.apply(head.lineageId()).promise();
    }

    /// Committed only when both supersede transactions were accepted — a re-read alone could be satisfied
    /// by a concurrent write — then the declaration is committed beside the backup, the only thing that
    /// lets this lineage replace the head.
    private Promise<GenesisDeclared> confirm(List<Object> results,
                                             String transactionId,
                                             ClusterIncarnationValue next,
                                             BackupHeader head) {
        return ClusterIncarnation.supersedeAccepted(results, transactionId)
               ? publish(next, head)
               : DeclareGenesisError.General.NOT_COMMITTED.promise();
    }

    private Promise<GenesisDeclared> publish(ClusterIncarnationValue committed, BackupHeader head) {
        var declaration = BackupDecision.Declaration.declaration(committed.lineageId(), committed.incarnation());

        return service.onWorker(() -> service.publishDeclaration(declaration))
                      .mapError(DeclareGenesisError.DeclarationNotPublished.FACTORY::apply)
                      .map(_ -> GenesisDeclared.genesisDeclared(committed.lineageId(),
                                                                committed.incarnation(),
                                                                head.lineageId(),
                                                                head.incarnation()));
    }
}
