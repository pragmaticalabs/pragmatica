package org.pragmatica.net.dns;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.net.dns.ResolverErrors.InvalidIpAddress;
import org.pragmatica.net.dns.ResolverErrors.UnknownError;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Utilities for working with InetAddress.
 */
public final class InetUtils {
    public static Result<InetAddress> forBytes(byte[] address) {
        return Result.lift(InetUtils::exceptionMapper, () -> InetAddress.getByAddress(address));
    }

    static Cause exceptionMapper(Throwable throwable) {
        if (throwable instanceof UnknownHostException) {
            return new InvalidIpAddress(throwable.getMessage());
        }
        return new UnknownError(throwable.getMessage());
    }

    private InetUtils() {}
}
