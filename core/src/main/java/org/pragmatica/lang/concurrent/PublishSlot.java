package org.pragmatica.lang.concurrent;

import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// Ownership slot for a resource acquired asynchronously, ordered against a close that may arrive
/// while the acquisition is still in flight.
///
/// The hazard it removes (#1456): a component whose `start()` issues an asynchronous bind and stores
/// the result in a field, and whose `stop()` releases whatever that field holds. When `stop()` runs
/// between the bind being issued and the field being written, the field is empty — so `stop()`
/// releases nothing and reports success — and the bind then lands on a resource nobody owns,
/// unreachable and holding its port for the life of the process.
///
/// Three states over ONE atomic location, so publish and close are linearizable and **exactly one**
/// of the two callers is handed the resource to release:
///
/// - `Empty` — nothing acquired yet;
/// - `Published` — the slot owns the value; [#close()] and [#take()] hand it back;
/// - `Closed` — terminal; every later [#publishOrReclaim] hands the resource straight back to its
///   publisher, which releases it on its own thread.
///
/// Deliberately NOT a "wait for the acquisition to finish" latch. A `start()` that never settles
/// (quorum that cannot form, #1308) would make such a `stop()` hang forever, and escaping that hang
/// is exactly what the abort path exists for. Closing never blocks.
///
/// No operation ever drops a value: publishing over an already-published value hands the previous
/// one back rather than overwriting it.
public final class PublishSlot<T> {
    private final AtomicReference<SlotState<T>> state = new AtomicReference<>(new SlotState.Empty<>());

    private PublishSlot() {}

    public static <T> PublishSlot<T> publishSlot() {
        return new PublishSlot<>();
    }

    /// Hand `value` to the slot. Returns `none()` when the slot took ownership, or the value the
    /// **caller** must now release: `value` itself when the slot is already closed, or the previously
    /// published value when one was still held.
    public Option<T> publishOrReclaim(T value) {
        return reclaimed(state.getAndUpdate(current -> advance(current, value)), value);
    }

    /// Close the slot permanently, returning the published value for the caller to release. Every
    /// later [#publishOrReclaim] hands its argument straight back.
    public Option<T> close() {
        return published(state.getAndSet(new SlotState.Closed<>()));
    }

    /// Remove and return the published value **without** closing, so a fresh value can be published
    /// in its place — the certificate-rotation path. A closed slot stays closed and yields `none()`.
    public Option<T> take() {
        return published(state.getAndUpdate(PublishSlot::vacate));
    }

    /// The currently published value, or `none()` when the slot is empty or closed.
    public Option<T> current() {
        return published(state.get());
    }

    public boolean isClosed() {
        return state.get() instanceof SlotState.Closed<T>;
    }

    private static <T> SlotState<T> advance(SlotState<T> current, T value) {
        return current instanceof SlotState.Closed<T>
               ? current
               : new SlotState.Published<>(value);
    }

    private static <T> SlotState<T> vacate(SlotState<T> current) {
        return current instanceof SlotState.Closed<T>
               ? current
               : new SlotState.Empty<>();
    }

    private static <T> Option<T> reclaimed(SlotState<T> previous, T value) {
        return switch (previous) {
            case SlotState.Closed<T>_ -> some(value);
            case SlotState.Published<T>(var held) -> some(held);
            case SlotState.Empty<T>_ -> none();
        };
    }

    private static <T> Option<T> published(SlotState<T> current) {
        return current instanceof SlotState.Published<T>(var held)
               ? some(held)
               : none();
    }

    private sealed interface SlotState<T> {
        record Empty<T>() implements SlotState<T> {}

        record Published<T>(T value) implements SlotState<T> {}

        record Closed<T>() implements SlotState<T> {}
    }
}
