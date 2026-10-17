package org.pragmatica.consensus.rabia;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.stream.Collectors;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;


/// Comment-header encoding keeps the voter configuration inside the same atomic TOML snapshot replacement.
///
/// Only the `# Voter:` line is written or read. Earlier backups also carried handoff and certificate
/// header lines; decoding ignores them, which is deliberate — the certified handoff they described no
/// longer exists (#1526) and pre-GA backups need no migration.
final class VoterConfigurationHeader {
    private static final String PREFIX = "# Voter: ";

    private VoterConfigurationHeader() {}

    static String encode(VoterConfiguration configuration) {
        return PREFIX + configuration.epoch()
             + "|" + configuration.members()
                                  .stream()
                                  .map(NodeId::id)
                                  .map(VoterConfigurationHeader::encodeId)
                                  .collect(Collectors.joining(","))
             + "\n";
    }

    static Result<Option<VoterConfiguration>> decode(String text) {
        return Option.from(text.lines()
                               .filter(line -> line.startsWith(PREFIX))
                               .map(line -> line.substring(PREFIX.length()))
                               .findFirst())
                     .map(VoterConfigurationHeader::decodeConfiguration)
                     .map(result -> result.map(Option::some))
                     .or(Result.success(Option.none()));
    }

    private static Result<VoterConfiguration> decodeConfiguration(String encoded) {
        var parts = encoded.split("\\|", -1);

        if (parts.length != 2) {
            return ReconfigurationError.INCOMPATIBLE_EPOCH.result();
        }

        return parseEpoch(parts[0]).flatMap(epoch -> Result.allOf(Arrays.stream(parts[1].split(",", -1)).map(VoterConfigurationHeader::decodeNode)).flatMap(nodes -> VoterConfiguration.voterConfiguration(epoch,
                                                                                                                                                                                                           nodes)));
    }

    private static String encodeId(String id) {
        return Base64.getEncoder().encodeToString(id.getBytes(StandardCharsets.UTF_8));
    }

    private static Result<NodeId> decodeNode(String encoded) {
        return Result.lift(ReconfigurationError.INCOMPATIBLE_EPOCH,
                           () -> Base64.getDecoder().decode(encoded))
                     .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                     .flatMap(NodeId::nodeId);
    }

    private static Result<Long> parseEpoch(String value) {
        return Result.lift(ReconfigurationError.INCOMPATIBLE_EPOCH,
                           () -> Long.parseLong(value))
                     .filter(ReconfigurationError.INCOMPATIBLE_EPOCH, number -> number >= 0);
    }
}
