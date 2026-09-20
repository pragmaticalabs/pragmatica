package org.pragmatica.consensus.rabia;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pragmatica.consensus.Command;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.StateMachine.Batch;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;


/// Executor-confined handoff evidence. Authority changes only after the caller persists it.
final class VoterConfigurationState<C extends Command> {
    private volatile VoterAuthority<C> authority;
    private final Map<NodeId, ConfigurationHandoff<C>> transfers = new HashMap<>();
    private final Set<NodeId> installed = new HashSet<>();

    VoterConfigurationState(VoterAuthority<C> authority) {
        this.authority = authority;
    }

    VoterAuthority<C> authority() {
        return authority;
    }

    VoterConfiguration configuration() {
        return authority.configuration();
    }

    boolean isAwaitingHandoff() {
        return authority.handoff()
                        .map(handoff -> handoff.previous()
                                               .equals(configuration()))
                        .or(false);
    }

    /// Called only after the matching application snapshot and authority were persisted atomically.
    org.pragmatica.lang.Unit install(VoterAuthority<C> next) {
        if (!configuration().equals(next.configuration())) {
            transfers.clear();
            installed.clear();
        }

        authority = next;

        return org.pragmatica.lang.Unit.unit();
    }

    Result<VoterAuthority<C>> barrier(ClusterConfig target, Phase nextSlot, byte[] snapshot, List<Batch<C>> pending) {
        if (isAwaitingHandoff()) {
            return ReconfigurationError.INCOMPATIBLE_EPOCH.result();
        }

        return VoterConfiguration.voterConfiguration(configuration().epoch() + 1,
                                                     target.members())
                                 .map(next -> new ConfigurationHandoff<>(configuration(),
                                                                         next,
                                                                         nextSlot,
                                                                         snapshot,
                                                                         pending,
                                                                         authority.history()))
                                 .map(handoff -> new VoterAuthority<>(configuration(),
                                                                      Option.some(handoff),
                                                                      authority.history()));
    }

    Result<Option<VoterAuthority<C>>> receive(NodeId sender, ConfigurationHandoff<C> handoff) {
        if (!handoff.previous().contains(sender)) {
            return ReconfigurationError.UNKNOWN_VOTER.result();
        }

        if (handoff.next().epoch() != handoff.previous().epoch() + 1) {
            return ReconfigurationError.INCOMPATIBLE_EPOCH.result();
        }

        if (handoff.next().epoch() < configuration().epoch()) {
            return Result.success(Option.none());
        }

        if (handoff.next().equals(configuration())) {
            return Result.success(Option.some(authority));
        }

        var predecessor = new VoterAuthority<C>(handoff.previous(), Option.none(), handoff.history());

        if (!predecessor.extendsConfiguration(configuration()) || conflictsWithBarrier(handoff)) {
            return ReconfigurationError.INCOMPATIBLE_EPOCH.result();
        }

        transfers.put(sender, handoff);

        return Result.success(collectCertificate(handoff));
    }

    private boolean conflictsWithBarrier(ConfigurationHandoff<C> handoff) {
        return authority.handoff()
                        .filter(own -> own.previous()
                                          .equals(handoff.previous()))
                        .map(own -> !own.sameCheckpoint(handoff))
                        .or(false);
    }

    private Option<VoterAuthority<C>> collectCertificate(ConfigurationHandoff<C> handoff) {
        var witnesses = transfers.entrySet()
                                 .stream()
                                 .filter(entry -> entry.getValue()
                                                       .sameCheckpoint(handoff))
                                 .map(Map.Entry::getKey)
                                 .sorted(java.util.Comparator.comparing(NodeId::id))
                                 .toList();

        if (witnesses.size() < handoff.previous().quorumSize()) {
            return Option.none();
        }

        var history = new ArrayList<>(handoff.history());

        history.add(new ConfigurationCertificate(handoff.previous(), handoff.next(), handoff.nextSlot(), witnesses));

        return Option.some(new VoterAuthority<>(handoff.next(), Option.some(handoff), history));
    }

    boolean acknowledge(NodeId sender, VoterConfiguration next, Phase boundary) {
        if (!configuration().equals(next) || !next.contains(sender) || !authority.handoff()
                                                                                 .map(handoff -> handoff.next()
                                                                                                        .equals(next) && handoff.nextSlot()
                                                                                                                                .equals(boundary))
                                                                                 .or(false)) {
            return false;
        }

        installed.add(sender);

        return installed.size() >= next.quorumSize();
    }

    VoterAuthority<C> certifiedInstallation() {
        return authority.withInstallationWitnesses(installed.stream()
                                                            .sorted(java.util.Comparator.comparing(NodeId::id))
                                                            .toList());
    }

    boolean accepts(VoterAuthority<C> candidate) {
        if (!candidate.isInternallyValid() || !candidate.extendsConfiguration(configuration())) {
            return false;
        }

        return authority.handoff()
                        .filter(own -> own.previous()
                                          .equals(configuration()))
                        .map(own -> candidate.history()
                                             .stream()
                                             .anyMatch(certificate -> certificate.previous()
                                                                                 .equals(own.previous())
                                                                      && certificate.next()
                                                                                    .equals(own.next())
                                                                      && certificate.nextSlot()
                                                                                    .equals(own.nextSlot())))
                        .or(true);
    }
}
