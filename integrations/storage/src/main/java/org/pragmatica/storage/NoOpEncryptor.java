package org.pragmatica.storage;

import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;

/// No-op encryptor that passes data through unchanged.
final class NoOpEncryptor implements BlockEncryptor {

    static final NoOpEncryptor INSTANCE = new NoOpEncryptor();

    private NoOpEncryptor() {}

    @Override
    public Result<BlockEncryptor.EncryptedData> encrypt(byte[] data) {
        return success(BlockEncryptor.EncryptedData.encryptedData(data, EncryptionParams.NONE));
    }

    @Override
    public Result<byte[]> decrypt(byte[] encryptedData, EncryptionParams params) {
        return success(encryptedData.clone());
    }
}
