package org.pragmatica.storage;

import org.pragmatica.lang.Cause;


/// Failures of the durable-anchor primitive ([StorageTier#putAnchor], [StorageTier#latestAnchor]).
public sealed interface AnchorError extends Cause {
    /// A durable tier that does not implement anchors. Refused rather than answered with "no anchor": a cold boot that
    /// read nothing from such a tier would lose every name anchored there.
    record AnchorsUnsupported(TierLevel tier) implements AnchorError {
        static AnchorsUnsupported anchorsUnsupported(TierLevel tier) {
            return new AnchorsUnsupported(tier);
        }

        @Override
        public String message() {
            return "The durable " + tier + " tier does not implement anchors";
        }
    }

    record InvalidAnchorName(String value) implements AnchorError {
        static InvalidAnchorName invalidAnchorName(String value) {
            return new InvalidAnchorName(value);
        }

        @Override
        public String message() {
            return "Invalid anchor name '" + value
                 + "': 1 to 3 segments joined by '/', each 1 to 128 of [a-z0-9._-] "
                 + "and neither '.' nor '..'";
        }
    }

    record NegativeVersion(AnchorName name, long version) implements AnchorError {
        static NegativeVersion negativeVersion(AnchorName name, long version) {
            return new NegativeVersion(name, version);
        }

        @Override
        public String message() {
            return "Anchor " + name.value() + " cannot be written at negative version " + version;
        }
    }

    record RetentionTooSmall(int versions) implements AnchorError {
        static RetentionTooSmall retentionTooSmall(int versions) {
            return new RetentionTooSmall(versions);
        }

        @Override
        public String message() {
            return "Anchor retention of " + versions + " is below the minimum of " + AnchorRetention.MINIMUM;
        }
    }

    /// A storage instance with no durable tier has nowhere to keep an anchor that a cold boot could read.
    record NoDurableTier(String instanceName) implements AnchorError {
        static NoDurableTier noDurableTier(String instanceName) {
            return new NoDurableTier(instanceName);
        }

        @Override
        public String message() {
            return "Storage instance '" + instanceName + "' has no durable tier to hold anchors";
        }
    }

    /// The newest version of an anchor on a tier cannot be read back as written: its frame is damaged, or names
    /// another version. Fails closed: the next-older version is never returned instead, because it would silently
    /// drop the acknowledged commits the newest one recorded. Recovery is an explicit operator action (A3-3).
    record AnchorCorrupt(TierLevel tier, AnchorName name, long version, String detail) implements AnchorError {
        static AnchorCorrupt anchorCorrupt(TierLevel tier, AnchorName name, long version, String detail) {
            return new AnchorCorrupt(tier, name, version, detail);
        }

        @Override
        public String message() {
            return "Anchor " + name.value()
                 + " version " + version
                 + " on the " + tier
                 + " tier is corrupt (" + detail
                 + "); older versions are not read in its place until an operator accepts one";
        }
    }

    /// Two durable tiers hold the same version of an anchor with different values. Every write of one version carries
    /// the same value, so this is never a race; it fails closed like [AnchorCorrupt].
    record AnchorDiverged(AnchorName name, long version) implements AnchorError {
        static AnchorDiverged anchorDiverged(AnchorName name, long version) {
            return new AnchorDiverged(name, version);
        }

        @Override
        public String message() {
            return "Anchor " + name.value()
                 + " version " + version
                 + " differs between durable tiers; "
                 + "it is not read until an operator accepts one";
        }
    }
}
