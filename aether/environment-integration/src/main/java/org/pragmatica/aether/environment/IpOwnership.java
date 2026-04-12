package org.pragmatica.aether.environment;

public record IpOwnership(boolean ownedByAccount, String currentAttachment) {
    public static IpOwnership ipOwnership(boolean ownedByAccount, String currentAttachment) {
        return new IpOwnership(ownedByAccount, currentAttachment);
    }
}
