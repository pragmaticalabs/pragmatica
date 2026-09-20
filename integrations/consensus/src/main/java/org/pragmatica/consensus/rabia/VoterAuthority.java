package org.pragmatica.consensus.rabia;

import java.util.List;

import org.pragmatica.consensus.Command;
import org.pragmatica.lang.Option;
import org.pragmatica.serialization.Codec;


/// Persisted authority and the most recent barrier, atomically stored with application state.
@Codec
public record VoterAuthority<C extends Command>(VoterConfiguration configuration,
                                                Option<ConfigurationHandoff<C>> handoff,
                                                List<ConfigurationCertificate> history,
                                                List<org.pragmatica.consensus.NodeId> installationWitnesses) {
    public VoterAuthority {
        history = List.copyOf(history);
        installationWitnesses = List.copyOf(installationWitnesses);
    }

    public VoterAuthority(VoterConfiguration configuration, Option<ConfigurationHandoff<C>> handoff) {
        this(configuration, handoff, List.of(), List.of());
    }

    public VoterAuthority(VoterConfiguration configuration,
                          Option<ConfigurationHandoff<C>> handoff,
                          List<ConfigurationCertificate> history) {
        this(configuration, handoff, history, List.of());
    }

    public boolean retirementSafe() {
        return handoff.isEmpty() || handoff.map(value -> value.next()
                                                              .equals(configuration)
                                                         && installationWitnesses.size() >= configuration.quorumSize()
                                                         && java.util.Set.copyOf(installationWitnesses)
                                                                         .size() == installationWitnesses.size()
                                                         && installationWitnesses.stream()
                                                                                 .allMatch(configuration::contains))
                                           .or(false);
    }

    public VoterAuthority<C> withInstallationWitnesses(List<org.pragmatica.consensus.NodeId> witnesses) {
        return new VoterAuthority<>(configuration, handoff, history, witnesses);
    }

    public boolean isInternallyValid() {
        var anchor = history.isEmpty()
                     ? configuration
                     : history.getFirst().previous();

        return anchor.epoch() == 0
               && VoterConfiguration.voterConfiguration(configuration.epoch(),
                                                        configuration.members())
                                    .isSuccess()
               && (configuration.epoch() == 0 || !history.isEmpty())
               && extendsConfiguration(anchor)
               && handoff.map(value -> VoterConfiguration.voterConfiguration(value.previous().epoch(),
                                                                             value.previous().members())
                                                         .isSuccess()
                                       && VoterConfiguration.voterConfiguration(value.next().epoch(),
                                                                                value.next().members())
                                                            .isSuccess()
                                       && value.nextSlot()
                                               .value() > 0
                                       && value.next()
                                               .epoch() == value.previous()
                                                                .epoch() + 1
                                       && (configuration.equals(value.previous()) || configuration.equals(value.next())))
                         .or(true);
    }

    public boolean extendsConfiguration(VoterConfiguration anchor) {
        var expected = anchor;
        var boundary = Phase.ZERO;

        for (var certificate : history) {
            if (certificate.next().epoch() <= anchor.epoch()) {
                continue;
            }

            if (!certificate.isValid() || !certificate.previous().equals(expected) || certificate.nextSlot()
                                                                                                 .compareTo(boundary) <= 0) {
                return false;
            }

            expected = certificate.next();
            boundary = certificate.nextSlot();
        }

        return expected.equals(configuration);
    }
}
