package org.pragmatica.storage;

import java.nio.file.Path;

/// Run in a forked JVM by [MountClaimTest]: claims the mount, says so on stdout, then holds the claim until the process is killed
/// (stdin is never written, so the read blocks for ever).
public final class MountClaimHolderMain {
    private MountClaimHolderMain() {}

    public static void main(String[] args) throws Exception {
        var claim = MountClaim.claim(Path.of(args[0]), args[1]);

        if (claim.isFailure()) {
            System.out.println("refused");
            System.out.flush();
            return;
        }
        System.out.println("claimed");
        System.out.flush();
        System.in.read();
    }
}
