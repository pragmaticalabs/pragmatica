package org.pragmatica.jbct.lint.layer;


/// Architecture zone of a package — a coarser grouping than [Layer].
///
/// Two zones matter to the layering rules. `BUSINESS` code (domain, application, bootstrap) must
/// stay free of foreign-exception plumbing; `ADAPTER_BOUNDARY` code is the one place where the
/// outside world is converted into typed `Cause`s (the `lift(...)` family). The zone is a derived
/// property of the layer — see [Layer#zone()] — not an independent classification axis. JBCT-ARCH-02
/// keys on it.
public enum Zone {
    /// Domain / application / bootstrap code — pure business logic.
    BUSINESS,
    /// Adapter code — the boundary where foreign exceptions are lifted to typed causes.
    ADAPTER_BOUNDARY
}
