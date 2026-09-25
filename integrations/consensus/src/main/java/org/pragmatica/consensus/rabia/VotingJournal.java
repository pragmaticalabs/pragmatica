package org.pragmatica.consensus.rabia;

import java.util.ArrayList;
import java.util.List;

import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.*;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


/// Protocol identity comparison ignores retransmission sender and request correlation metadata.
final class VotingJournal {
    private VotingJournal() {}

    static boolean supported(RabiaProtocolMessage message) {
        return message instanceof Propose<?> || message instanceof VoteRound1 || message instanceof VoteRound2 || message instanceof Decision<?>;
    }

    static long epoch(RabiaProtocolMessage message) {
        return switch (message) {
            case Propose<?> value -> value.epoch();
            case VoteRound1 value -> value.epoch();
            case VoteRound2 value -> value.epoch();
            case Decision<?> value -> value.epoch();
            default -> - 1;
        };
    }

    static Phase phase(RabiaProtocolMessage message) {
        return switch (message) {
            case Propose<?> value -> value.phase();
            case VoteRound1 value -> value.phase();
            case VoteRound2 value -> value.phase();
            case Decision<?> value -> value.phase();
            default -> Phase.phase(-1);
        };
    }

    static boolean sameIdentity(RabiaProtocolMessage left, RabiaProtocolMessage right) {
        if (!left.getClass().equals(right.getClass()) || epoch(left) != epoch(right) || !phase(left).equals(phase(right))) {
            return false;
        }

        return switch (left) {
            case VoteRound1 value -> value.round() == ((VoteRound1) right).round();
            case VoteRound2 value -> value.round() == ((VoteRound2) right).round();
            default -> true;
        };
    }

    static boolean sameValue(RabiaProtocolMessage left, RabiaProtocolMessage right) {
        return switch (left) {
            case Propose<?> value -> right instanceof Propose<?> other && value.value().id().equals(other.value().id()) && value.reconfiguration().equals(other.reconfiguration()) && value.sender().equals(other.sender());
            case VoteRound1 value -> right instanceof VoteRound1 other && value.stateValue() == other.stateValue();
            case VoteRound2 value -> right instanceof VoteRound2 other && value.stateValue() == other.stateValue();
            case Decision<?> value -> right instanceof Decision<?> other && value.stateValue() == other.stateValue() && value.value().id().equals(other.value().id()) && value.reconfiguration().equals(other.reconfiguration());
            default -> false;
        };
    }

    static Option<RabiaProtocolMessage> existing(List<RabiaProtocolMessage> entries, RabiaProtocolMessage message) {
        return Option.from(entries.stream().filter(entry -> sameIdentity(entry, message)).findFirst());
    }

    static List<RabiaProtocolMessage> retain(List<RabiaProtocolMessage> entries,
                                             Phase frontier,
                                             Option<? extends VoterAuthority<?>> authority) {
        return entries.stream()
                      .filter(entry -> phase(entry).compareTo(frontier) >= 0)
                      .filter(entry -> authority.map(value -> epoch(entry) == value.configuration()
                                                                                   .epoch())
                                                .or(true))
                      .toList();
    }
}
