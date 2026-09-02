package org.pragmatica.jbct.derive.result;

import java.util.List;

import org.pragmatica.jbct.derive.model.AnswerRow;
import org.pragmatica.jbct.derive.model.Axis;

/// One row of the pressure matrix (SPEC.md §4 press): what a single answer does to one axis.
///
/// A [Mode#PRESS] pressure pushes the axis in a direction; a [Mode#EXCLUDE] pressure narrows a
/// scope out (scope-exclusion before hardening); an [Mode#INERT] pressure is recorded as a
/// *result*, not discarded — the answer was read and found not to move the axis. Every pressure
/// cites the answers that produced it (F10).
public record Pressure(Axis axis, Mode mode, String direction, String mechanism, List<AnswerRow> citing) {
    public Pressure {
        citing = List.copyOf(citing);
    }

    /// How an answer bears on an axis.
    public enum Mode {
        /// Pushes the axis toward a dearer position along its rung ladder.
        PRESS,
        /// Contains the demand by narrowing where it applies (split the scope out).
        EXCLUDE,
        /// Read and found not to move the axis — kept as a result, not discarded.
        INERT
    }

    /// A pressure that moves the axis in a direction, produced by the given answer.
    public static Pressure press(Axis axis, String direction, String mechanism, AnswerRow citing) {
        return new Pressure(axis, Mode.PRESS, direction, mechanism, List.of(citing));
    }

    /// A scope-exclusion pressure: contain the demand by splitting its scope out.
    public static Pressure exclude(Axis axis, String direction, AnswerRow citing) {
        return new Pressure(axis, Mode.EXCLUDE, direction, "scope-exclusion", List.of(citing));
    }

    /// An inert pressure: the answer was read and found not to move the axis (kept as a result).
    public static Pressure inert(Axis axis, String reason, AnswerRow citing) {
        return new Pressure(axis, Mode.INERT, reason, "none", List.of(citing));
    }

    /// Whether this pressure actually moves its axis (PRESS or EXCLUDE, not INERT).
    public boolean moves() {
        return mode != Mode.INERT;
    }

    /// The citations rendered as a compact comma-separated list (F10).
    public String citations() {
        return String.join(", ", citing.stream().map(AnswerRow::cite).toList());
    }
}
