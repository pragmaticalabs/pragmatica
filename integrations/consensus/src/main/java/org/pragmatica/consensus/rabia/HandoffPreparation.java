package org.pragmatica.consensus.rabia;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

import org.pragmatica.consensus.Command;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.StateMachine.Batch;
import org.pragmatica.consensus.net.OutboundMessageLimit;
import org.pragmatica.consensus.rabia.RabiaPersistence.SavedState;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.ConfigurationTransfer;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.SyncResponse;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


/// Prepares a bounded transfer at one exact application prefix. Uncommitted pending requests
/// are optional recovery hints: omitting them never acknowledges their completion.
final class HandoffPreparation {
    private HandoffPreparation() {}

    static <C extends Command> Result<ConfigurationHandoff<C>> prepare(VoterAuthority<C> authority,
                                                                       ClusterConfig target,
                                                                       Phase boundary,
                                                                       byte[] snapshot,
                                                                       List<Batch<C>> pending,
                                                                       Function<ProtocolMessage, Result<Unit>> validate) {
        if (snapshot.length > OutboundMessageLimit.MAX_TRANSFER_BYTES) {
            return ReconfigurationError.STATE_TRANSFER_TOO_LARGE.result();
        }

        return VoterConfiguration.voterConfiguration(authority.configuration().epoch() + 1,
                                                     target.members())
                                 .flatMap(next -> preparePending(new ConfigurationHandoff<C>(authority.configuration(),
                                                                                             next,
                                                                                             boundary,
                                                                                             snapshot,
                                                                                             List.of(),
                                                                                             authority.history()),
                                                                 pending,
                                                                 validate));
    }

    private static <C extends Command> Result<ConfigurationHandoff<C>> preparePending(ConfigurationHandoff<C> empty,
                                                                                      List<Batch<C>> pending,
                                                                                      Function<ProtocolMessage, Result<Unit>> validate) {
        return validateEnvelopes(empty, validate).flatMap(_ -> withPendingIfBounded(empty, pending, validate))
                                .mapError(_ -> ReconfigurationError.STATE_TRANSFER_TOO_LARGE);
    }

    private static <C extends Command> Result<ConfigurationHandoff<C>> withPendingIfBounded(ConfigurationHandoff<C> empty,
                                                                                            List<Batch<C>> pending,
                                                                                            Function<ProtocolMessage, Result<Unit>> validate) {
        var populated = new ConfigurationHandoff<C>(empty.previous(),
                                                    empty.next(),
                                                    empty.nextSlot(),
                                                    empty.snapshot(),
                                                    pending,
                                                    empty.history());

        return validateEnvelopes(populated, validate).fold(_ -> Result.success(empty), _ -> Result.success(populated));
    }

    private static <C extends Command> Result<Unit> validateEnvelopes(ConfigurationHandoff<C> handoff,
                                                                      Function<ProtocolMessage, Result<Unit>> validate) {
        var history = new ArrayList<>(handoff.history());

        history.add(new ConfigurationCertificate(handoff.previous(),
                                                 handoff.next(),
                                                 handoff.nextSlot(),
                                                 handoff.previous().members()));
        var installed = new VoterAuthority<C>(handoff.next(),
                                              Option.some(handoff),
                                              history,
                                              handoff.next().members());
        var response = new SyncResponse<C>(largestIdentity(handoff.next().members()),
                                           new SavedState<C>(handoff.snapshot(),
                                                             handoff.nextSlot(),
                                                             handoff.pendingBatches(),
                                                             Option.some(installed)),
                                           ResponderState.LIVE);

        return validate.apply(new ConfigurationTransfer<C>(largestIdentity(handoff.previous().members()),
                                                           handoff))
                       .flatMap(_ -> validate.apply(response));
    }

    private static NodeId largestIdentity(List<NodeId> identities) {
        return identities.stream()
                         .max(Comparator.comparingInt(node -> node.id()
                                                                  .getBytes(StandardCharsets.UTF_8).length))
                         .orElse(identities.getFirst());
    }
}
