package org.pragmatica.jbct.derive.model;

import org.pragmatica.lang.Option;

/// Sheet metadata (SPEC.md §3, `[meta]`): the system slug, its era (mandatory — architecture
/// claims are only meaningful pinned to a time window), optional author and registration date,
/// and whether the run is greenfield or living.
public record Meta(String system, String era, Option<String> author, Option<String> date, Mode mode) {}
