package org.pragmatica.jbct.derive.model;

import java.util.List;

import org.pragmatica.lang.Option;

/// A parsed architecture answer sheet (SPEC.md §3): the fully-typed result of parsing one TOML
/// document. The entry gate ([org.pragmatica.jbct.derive.gate.EntryGate]) validates it; Phase B
/// will derive a vector from it.
///
/// `source` is the sheet's origin (a file path or a test label) used to locate gate findings.
public record AnswerSheet(String source,
                          String schemaVersion,
                          Meta meta,
                          List<AnswerRow> rows,
                          List<DomainShape> domainShapes,
                          List<ChangeDriver> changeDrivers,
                          Option<CurrentVector> currentVector,
                          List<Floor> floors) {
    public AnswerSheet {
        rows = List.copyOf(rows);
        domainShapes = List.copyOf(domainShapes);
        changeDrivers = List.copyOf(changeDrivers);
        floors = List.copyOf(floors);
    }
}
