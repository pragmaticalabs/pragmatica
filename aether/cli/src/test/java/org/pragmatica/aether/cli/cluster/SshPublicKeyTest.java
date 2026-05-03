// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SshPublicKeyTest {

    private static final String VALID_ED25519 = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIBaa1234567890abcdefghijklmnopqrstuvwxyz/+ABCD operator@workstation";
    private static final String VALID_RSA = "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQDExampleBlobMaterialRSA== operator@host";
    private static final String VALID_ECDSA = "ecdsa-sha2-nistp256 AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBExample= operator";

    @Nested
    class Validation {
        @Test
        void sshPublicKey_acceptsEd25519_withComment() {
            var result = SshPublicKey.sshPublicKey(VALID_ED25519);
            assertTrue(result.isSuccess(), () -> "expected success, got: " + result);
        }

        @Test
        void sshPublicKey_acceptsRsa() {
            assertTrue(SshPublicKey.sshPublicKey(VALID_RSA).isSuccess());
        }

        @Test
        void sshPublicKey_acceptsEcdsa() {
            assertTrue(SshPublicKey.sshPublicKey(VALID_ECDSA).isSuccess());
        }

        @Test
        void sshPublicKey_rejectsBlankInput() {
            var result = SshPublicKey.sshPublicKey("   \t  ");
            assertTrue(result.isFailure());
        }

        @Test
        void sshPublicKey_rejectsNullInput() {
            assertTrue(SshPublicKey.sshPublicKey(null).isFailure());
        }

        @Test
        void sshPublicKey_rejectsMultilineInput() {
            var multiLine = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAA op\nssh-rsa AAAAB3 op2";
            var result = SshPublicKey.sshPublicKey(multiLine);
            assertTrue(result.isFailure(), "Multi-line keys must be rejected");
        }

        @Test
        void sshPublicKey_rejectsUnsupportedAlgorithm() {
            var bogus = "rot13-key AAAAFakeBlobMaterial== operator";
            assertTrue(SshPublicKey.sshPublicKey(bogus).isFailure());
        }

        @Test
        void sshPublicKey_rejectsAlgorithmOnly() {
            assertTrue(SshPublicKey.sshPublicKey("ssh-rsa").isFailure());
        }

        @Test
        void sshPublicKey_trimsLeadingTrailingWhitespace() {
            var padded = "  " + VALID_ED25519 + "  ";
            var result = SshPublicKey.sshPublicKey(padded);
            assertTrue(result.isSuccess());
            assertEquals(VALID_ED25519, result.unwrap().value());
        }
    }

    @Nested
    class Accessors {
        @Test
        void algorithm_returnsAlgoToken() {
            var key = SshPublicKey.sshPublicKey(VALID_ED25519).unwrap();
            assertEquals("ssh-ed25519", key.algorithm());
        }

        @Test
        void blob_returnsBase64Token() {
            var key = SshPublicKey.sshPublicKey(VALID_ED25519).unwrap();
            assertTrue(key.blob().startsWith("AAAAC3NzaC1lZDI1NTE5"));
        }

        @Test
        void comment_returnsCommentToken() {
            var key = SshPublicKey.sshPublicKey(VALID_ED25519).unwrap();
            assertEquals("operator@workstation", key.comment());
        }

        @Test
        void hetznerKeyName_combinesPrefixWithBlobPrefix() {
            var key = SshPublicKey.sshPublicKey(VALID_ED25519).unwrap();
            var name = key.hetznerKeyName("aether-bootstrap");
            assertTrue(name.startsWith("aether-bootstrap-"));
            assertEquals("aether-bootstrap-AAAAC3Nz", name);
        }
    }

    @Nested
    class ParseAll {
        @Test
        void parseAll_returnsAllValid_whenAllValid() {
            var result = SshPublicKey.parseAll(List.of(VALID_ED25519, VALID_RSA));
            assertTrue(result.isSuccess());
            assertEquals(2, result.unwrap().size());
        }

        @Test
        void parseAll_failsFast_whenOneInvalid() {
            var result = SshPublicKey.parseAll(List.of(VALID_ED25519, "garbage"));
            assertTrue(result.isFailure());
        }
    }
}
