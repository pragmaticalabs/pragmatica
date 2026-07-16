package org.pragmatica.http.routing;

import java.util.function.Supplier;

import org.pragmatica.lang.Contract;


/// API-versioning observability sink (#198 §11.1).
///
/// A minimal, dependency-free seam the version-aware router emits to when a versioned request is
/// dispatched, so the metrics backend (e.g. Micrometer in the aether adapter) stays out of the
/// `http-routing` module. The runtime supplies a concrete sink; tests and unversioned wiring use
/// [#noop()].
///
/// Counters (#198 §11.1):
///   - [#versionedRequest] → `http.requests.versioned{slice,version,method,status}` per request.
///   - [#deprecatedRequest] → `api.versioning.deprecated.requests{slice,version}` when the served
///     version is deprecated.
///   - [#missingVersionHeader] → `api.versioning.missing.header{slice}` per header-mode request that
///     omitted the version header (served via fallback or rejected).
public interface VersioningMetricsSink {
    /// Record a versioned request served by `slice` at `version` via `method` with HTTP `status`.
    @Contract
    void versionedRequest(String slice, int version, String method, int status);

    /// Record a request served by a deprecated `version` of `slice`.
    @Contract
    void deprecatedRequest(String slice, int version);

    /// Record a header-mode request to `slice` that omitted the configured version header.
    @Contract
    void missingVersionHeader(String slice);

    /// A sink that records nothing — the default for tests and unversioned wiring.
    ///
    /// @return the shared no-op sink
    static VersioningMetricsSink noop() {
        return Noop.INSTANCE;
    }

    /// A sink that resolves the concrete sink per call from `source`, so a router bound before the
    /// metrics backend exists (the publisher creates routers at deploy time; the backend is wired by
    /// the management server) observes the backend once it is installed. `source` returning the
    /// current sink (or [#noop()] until set) is read on every emit.
    ///
    /// @param source supplies the live sink on each emit
    /// @return a forwarding sink
    static VersioningMetricsSink forwarding(Supplier<VersioningMetricsSink> source) {
        return new Forwarding(source);
    }

    enum Noop implements VersioningMetricsSink {
        INSTANCE;
        @Override
        @Contract
        public void versionedRequest(String slice, int version, String method, int status) {}
        @Override
        @Contract
        public void deprecatedRequest(String slice, int version) {}
        @Override
        @Contract
        public void missingVersionHeader(String slice) {}
    }

    record Forwarding(Supplier<VersioningMetricsSink> source) implements VersioningMetricsSink {
        @Override
        @Contract
        public void versionedRequest(String slice, int version, String method, int status) {
            source.get().versionedRequest(slice, version, method, status);
        }

        @Override
        @Contract
        public void deprecatedRequest(String slice, int version) {
            source.get().deprecatedRequest(slice, version);
        }

        @Override
        @Contract
        public void missingVersionHeader(String slice) {
            source.get().missingVersionHeader(slice);
        }
    }
}
