package org.pragmatica.consensus.rabia;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

import org.pragmatica.consensus.Command;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;


/// Comment-header encoding keeps authority inside the same atomic TOML snapshot replacement.
final class VoterAuthoritySnapshotCodec {
    private VoterAuthoritySnapshotCodec() {}

    static <C extends Command> String encode(VoterAuthority<C> authority) {
        return "# Voter: " + encodeConfiguration(authority.configuration())
             + "\n"
             + "# Voter-Installed: " + authority.installationWitnesses()
                                                .stream()
                                                .map(NodeId::id)
                                                .map(id -> Base64.getEncoder().encodeToString(id.getBytes(StandardCharsets.UTF_8)))
                                                .collect(Collectors.joining(","))
             + "\n" + encodeCertificates("Voter-Certificate", authority.history()) + authority.handoff()
                                                                                              .map(VoterAuthoritySnapshotCodec::encodeHandoff)
                                                                                              .or("");
    }

    private static String encodeConfiguration(VoterConfiguration config) {
        return config.epoch()
             + "|" + config.members()
                           .stream()
                           .map(NodeId::id)
                           .map(value -> Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)))
                           .collect(Collectors.joining(","));
    }

    private static String encodeHandoff(ConfigurationHandoff<?> handoff) {
        return "# Handoff-Previous: " + encodeConfiguration(handoff.previous())
             + "\n"
             + "# Handoff-Next: " + encodeConfiguration(handoff.next())
             + "\n"
             + "# Handoff-Slot: " + handoff.nextSlot()
                                           .value()
             + "\n"
             + "# Handoff-Snapshot: " + Base64.getEncoder().encodeToString(handoff.snapshot())
             + "\n" + encodeCertificates("Handoff-Certificate", handoff.history());
    }

    static <C extends Command> Result<Option<VoterAuthority<C>>> decode(String text) {
        return header(text, "Voter").map(value -> decodeConfiguration(value).flatMap(config -> Result.all(VoterAuthoritySnapshotCodec.<C> decodeHandoff(text),
                                                                                                          decodeCertificates(text,
                                                                                                                             "Voter-Certificate"),
                                                                                                          decodeInstallation(text)).map((handoff, history, installed) -> Option.some(new VoterAuthority<>(config,
                                                                                                                                                                                                          handoff,
                                                                                                                                                                                                          history,
                                                                                                                                                                                                          installed)))))
                     .or(Result.success(Option.none()));
    }

    private static <C extends Command> Result<Option<ConfigurationHandoff<C>>> decodeHandoff(String text) {
        return header(text, "Handoff-Previous").map(previous -> Result.all(decodeConfiguration(previous),
                                                                           required(text, "Handoff-Next").flatMap(VoterAuthoritySnapshotCodec::decodeConfiguration),
                                                                           required(text, "Handoff-Slot").flatMap(VoterAuthoritySnapshotCodec::parseNonnegative),
                                                                           required(text, "Handoff-Snapshot").flatMap(VoterAuthoritySnapshotCodec::decodeBytes),
                                                                           decodeCertificates(text,
                                                                                              "Handoff-Certificate"))
                                                                      .map((before, after, slot, snapshot, history) -> Option.some(new ConfigurationHandoff<C>(before,
                                                                                                                                                               after,
                                                                                                                                                               Phase.phase(slot),
                                                                                                                                                               snapshot,
                                                                                                                                                               List.of(),
                                                                                                                                                               history))))
                     .or(Result.success(Option.none()));
    }

    private static Result<List<NodeId>> decodeInstallation(String text) {
        return header(text, "Voter-Installed").filter(value -> !value.isEmpty())
                     .map(value -> Result.allOf(Arrays.stream(value.split(",", -1)).map(VoterAuthoritySnapshotCodec::decodeNode)))
                     .or(Result.success(List.of()));
    }

    private static Result<VoterConfiguration> decodeConfiguration(String encoded) {
        var parts = encoded.split("\\|", -1);

        if (parts.length != 2) {
            return ReconfigurationError.INCOMPATIBLE_EPOCH.result();
        }

        return parseNonnegative(parts[0]).flatMap(epoch -> Result.allOf(Arrays.stream(parts[1].split(",", -1)).map(VoterAuthoritySnapshotCodec::decodeNode)).flatMap(nodes -> VoterConfiguration.voterConfiguration(epoch,
                                                                                                                                                                                                                    nodes)));
    }

    private static String encodeCertificates(String header, List<ConfigurationCertificate> certificates) {
        return certificates.stream()
                           .map(certificate -> "# " + header
                                              + ": " + encodeConfiguration(certificate.previous())
                                              + ";" + encodeConfiguration(certificate.next())
                                              + ";" + certificate.nextSlot()
                                                                 .value()
                                              + ";" + certificate.witnesses()
                                                                 .stream()
                                                                 .map(NodeId::id)
                                                                 .map(id -> Base64.getEncoder().encodeToString(id.getBytes(StandardCharsets.UTF_8)))
                                                                 .collect(Collectors.joining(","))
                                              + "\n")
                           .collect(Collectors.joining());
    }

    private static Result<List<ConfigurationCertificate>> decodeCertificates(String text, String name) {
        var prefix = "# " + name + ": ";

        return Result.allOf(text.lines()
                                .filter(line -> line.startsWith(prefix))
                                .map(line -> decodeCertificate(line.substring(prefix.length()))));
    }

    private static Result<ConfigurationCertificate> decodeCertificate(String text) {
        var parts = text.split(";", -1);

        if (parts.length != 4) {
            return ReconfigurationError.INCOMPATIBLE_EPOCH.result();
        }

        return Result.all(decodeConfiguration(parts[0]),
                          decodeConfiguration(parts[1]),
                          parseNonnegative(parts[2]),
                          Result.allOf(Arrays.stream(parts[3].split(",", -1)).map(VoterAuthoritySnapshotCodec::decodeNode)))
                     .map((previous, next, slot, witnesses) -> new ConfigurationCertificate(previous,
                                                                                            next,
                                                                                            Phase.phase(slot),
                                                                                            witnesses))
                     .filter(ReconfigurationError.INCOMPATIBLE_EPOCH, ConfigurationCertificate::isValid);
    }

    private static Result<NodeId> decodeNode(String encoded) {
        return decodeBytes(encoded).map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                          .flatMap(NodeId::nodeId);
    }

    private static Result<byte[]> decodeBytes(String encoded) {
        return Result.lift(ReconfigurationError.INCOMPATIBLE_EPOCH,
                           () -> Base64.getDecoder().decode(encoded));
    }

    private static Result<Long> parseNonnegative(String value) {
        return Result.lift(ReconfigurationError.INCOMPATIBLE_EPOCH,
                           () -> Long.parseLong(value))
                     .filter(ReconfigurationError.INCOMPATIBLE_EPOCH, number -> number >= 0);
    }

    private static Result<String> required(String text, String name) {
        return header(text, name).toResult(ReconfigurationError.INCOMPATIBLE_EPOCH);
    }

    private static Option<String> header(String text, String name) {
        var prefix = "# " + name + ": ";

        return Option.from(text.lines()
                               .filter(line -> line.startsWith(prefix))
                               .map(line -> line.substring(prefix.length()))
                               .findFirst());
    }
}
