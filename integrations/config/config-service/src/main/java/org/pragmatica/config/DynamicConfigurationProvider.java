package org.pragmatica.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.unitResult;


/// Wraps a base ConfigurationProvider with a mutable overlay.
///
/// Values in the overlay take precedence over the base provider.
/// The overlay is thread-safe via ConcurrentHashMap.
/// Removing an overlay key restores visibility of the base value.
///
/// A reader that needs several keys from ONE overlay state (a binder reading the fields of a record) uses
/// [#atomically]: every overlay write is bracketed by a version counter that is odd while the write runs, and a
/// read that saw the same even version before and after is a state the writes produced. Nothing is locked on the
/// read side, so a reader never delays a writer (the writer is the KV applier thread).
/// [#asMap] and [#overlayMap] are consistent snapshots by the same rule.
public final class DynamicConfigurationProvider implements ConfigurationProvider {
    /// [#atomically] gave up: the overlay changed under every attempt. The caller treats it as a failed read and
    /// keeps what it had; the next change retries.
    public static final Cause OVERLAY_KEPT_CHANGING = Causes.cause("Config overlay kept changing during a consistent read");

    private static final int ATOMIC_ATTEMPTS = 8;
    private static final long ATOMIC_WAIT_NANOS = 5_000_000L;
    private static final int SNAPSHOT_ATTEMPTS = 1000;
    private static final long SNAPSHOT_WAIT_NANOS = 100_000_000L;

    private final ConfigurationProvider base;
    private final Overlay overlay;

    private DynamicConfigurationProvider(ConfigurationProvider base, Overlay overlay) {
        this.base = base;
        this.overlay = overlay;
    }

    public static DynamicConfigurationProvider dynamicConfigurationProvider(ConfigurationProvider base) {
        return new DynamicConfigurationProvider(base, new Overlay());
    }

    /// The overlay map and the version that brackets its writes. They are shared together: [#reload] returns a new
    /// provider instance over the same overlay, so a version kept on the provider would not be shared.
    private static final class Overlay {
        private final ConcurrentHashMap<String, String> entries = new ConcurrentHashMap<>();
        private final AtomicLong version = new AtomicLong();

        ConcurrentHashMap<String, String> entries() {
            return entries;
        }

        /// Writers are serialised here (the applier thread, plus tests); readers never take this monitor.
        <R> R write(Fn0<R> mutation) {
            synchronized (this) {
                version.incrementAndGet();
                try {
                    return mutation.apply();
                } finally {
                    version.incrementAndGet();
                }
            }
        }

        Option<String> get(String key) {
            return option(entries.get(key));
        }

        /// Up to `attempts` COMPLETED reads, within `waitNanos` overall. An attempt that finds a write in flight (odd
        /// version) spins without reading and without spending an attempt: a writer's window is a few map operations,
        /// so counting such spins as attempts would burn the budget in nanoseconds while a single write runs.
        // PAT-01: a seqlock retry loop is the primitive itself; its exits are the accepted read and the two bounds.
        @SuppressWarnings("JBCT-PAT-01")
        <R> Option<R> consistent(Fn0<R> read, int attempts, long waitNanos) {
            var deadline = System.nanoTime() + waitNanos;
            var failed = 0;

            while (failed < attempts && System.nanoTime() < deadline) {
                var before = version.get();

                if ((before & 1L) == 0L) {
                    var result = read.apply();

                    if (version.get() == before) {
                        return some(result);
                    }

                    failed++;
                } else {
                    Thread.onSpinWait();
                }
            }

            return none();
        }
    }

    @Override
    public Option<String> getString(String key) {
        return overlay.get(key)
                      .orElse(() -> base.getString(key));
    }

    /// Runs `read` against ONE overlay state: it is accepted only if no overlay write started or finished while it
    /// ran, and retried a bounded number of times (and a bounded wait for a write in flight) otherwise. After the bound the read FAILS with
    /// [#OVERLAY_KEPT_CHANGING] - no torn result is ever returned. `read` may be called more than once and must
    /// have no side effects; no lock is held while it runs.
    public <R> Result<R> atomically(Fn0<R> read) {
        return overlay.consistent(read, ATOMIC_ATTEMPTS, ATOMIC_WAIT_NANOS)
                      .toResult(OVERLAY_KEPT_CHANGING);
    }

    @Override
    public Set<String> keys() {
        var allKeys = new LinkedHashSet<>(base.keys());

        allKeys.addAll(overlay.entries().keySet());

        return Collections.unmodifiableSet(allKeys);
    }

    /// Excludes the mutable overlay entirely — it IS the KV-replayed-at-startup dynamic layer
    /// [ConfigurationProvider#staticKeys()] exists to keep out of [StrictKeys] validation.
    @Override
    public Set<String> staticKeys() {
        return base.staticKeys();
    }

    @Override
    public Map<String, String> asMap() {
        return overlay.consistent(this::mergedWithBase, SNAPSHOT_ATTEMPTS, SNAPSHOT_WAIT_NANOS)
                      .or(this::mergedWithBase);
    }

    private Map<String, String> mergedWithBase() {
        var merged = new LinkedHashMap<>(base.asMap());

        merged.putAll(overlay.entries());

        return Collections.unmodifiableMap(merged);
    }

    @Override
    public List<ConfigSource> sources() {
        return base.sources();
    }

    @Override
    public String name() {
        return "DynamicConfigurationProvider[" + base.name() + "]";
    }

    /// #1326 sibling: this used to reload the base, discard the result and return `this`, so the
    /// returned provider still read the PRE-reload base. It now returns a provider over the reloaded
    /// base that SHARES this overlay: the overlay is the live dynamic layer writers hold this instance
    /// for, so a write made through either is visible through both.
    @Override
    public Result<ConfigSource> reload() {
        return base.reload()
                   .map(reloaded -> new DynamicConfigurationProvider(asProvider(reloaded),
                                                                     overlay));
    }

    private static ConfigurationProvider asProvider(ConfigSource source) {
        return source instanceof ConfigurationProvider provider
               ? provider
               : ConfigurationProvider.configurationProvider(source);
    }

    public Result<Unit> put(String key, String value) {
        inWriteWindow(() -> overlay.entries()
                                   .put(key, value));

        return unitResult();
    }

    public Result<Unit> remove(String key) {
        inWriteWindow(() -> overlay.entries()
                                   .remove(key));

        return unitResult();
    }

    /// The one path every overlay write takes; package-private so a test can hold a write window open.
    <R> R inWriteWindow(Fn0<R> mutation) {
        return overlay.write(mutation);
    }

    /// Listing snapshot. Consistent unless the overlay changed under all [#SNAPSHOT_ATTEMPTS] attempts or kept a
    /// write in flight for the whole wait, in which case the last copy is returned: this signature has no failure
    /// channel, and the binder path ([#atomically]) never takes this fallback.
    public Map<String, String> overlayMap() {
        return overlay.consistent(this::copyOfOverlay, SNAPSHOT_ATTEMPTS, SNAPSHOT_WAIT_NANOS)
                      .or(this::copyOfOverlay);
    }

    private Map<String, String> copyOfOverlay() {
        return Map.copyOf(overlay.entries());
    }
}
