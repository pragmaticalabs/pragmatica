package org.pragmatica.aether.update;
/**
 * Traffic routing ratio between old and new versions.
 *
 * <p>Uses ratio-based routing (not percentages). For example:
 * <ul>
 *   <li>{@code (1, 3)} = 1 new : 3 old (25% to new)</li>
 *   <li>{@code (1, 1)} = 1 new : 1 old (50% to new)</li>
 *   <li>{@code (3, 1)} = 3 new : 1 old (75% to new)</li>
 *   <li>{@code (1, 0)} = 100% to new</li>
 *   <li>{@code (0, 1)} = 100% to old (initial state)</li>
 * </ul>
 *
 * <p>Ratios are scaled to actual instance counts. If ratio cannot be satisfied
 * with available instances (e.g., 1:3 with only 2 old instances), the operation
 * should be rejected.
 *
 * @param newWeight weight for new version traffic
 * @param oldWeight weight for old version traffic
 */
public record VersionRouting(int newWeight, int oldWeight) {
    /**
     * Initial routing: all traffic to old version.
     */
    public static final VersionRouting ALL_OLD = new VersionRouting(0, 1);

    /**
     * Final routing: all traffic to new version.
     */
    public static final VersionRouting ALL_NEW = new VersionRouting(1, 0);

    /**
     * Creates a routing configuration.
     */
    public static VersionRouting versionRouting(int newWeight, int oldWeight) {
        if (newWeight < 0 || oldWeight < 0) {
            throw new IllegalArgumentException("Weights must be non-negative");
        }
        if (newWeight == 0 && oldWeight == 0) {
            throw new IllegalArgumentException("At least one weight must be positive");
        }
        return new VersionRouting(newWeight, oldWeight);
    }

    /**
     * Parses routing from string format "new:old" (e.g., "1:3").
     *
     * @param ratio the ratio string
     * @return parsed routing
     * @throws IllegalArgumentException if format is invalid
     */
    public static VersionRouting parse(String ratio) {
        var parts = ratio.split(":");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid ratio format. Expected 'new:old', got: " + ratio);
        }
        try{
            return versionRouting(Integer.parseInt(parts[0].trim()),
                                  Integer.parseInt(parts[1].trim()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid ratio values: " + ratio);
        }
    }

    /**
     * Checks if all traffic goes to old version.
     */
    public boolean isAllOld() {
        return newWeight == 0;
    }

    /**
     * Checks if all traffic goes to new version.
     */
    public boolean isAllNew() {
        return oldWeight == 0;
    }

    /**
     * Returns the total weight (for proportion calculations).
     */
    public int totalWeight() {
        return newWeight + oldWeight;
    }

    /**
     * Calculates the new version traffic percentage.
     */
    public double newVersionPercentage() {
        if (totalWeight() == 0) return 0.0;
        return (double) newWeight / totalWeight() * 100.0;
    }

    /**
     * Scales the routing ratio to instance counts.
     *
     * <p>For example, with ratio 1:3 and instances (new=3, old=9):
     * - Scale factor: min(3/1, 9/3) = 3
     * - Effective: 3 new instances, 9 old instances used
     *
     * @param newInstances available new version instances
     * @param oldInstances available old version instances
     * @return scaled instance counts (new, old), or null if unsatisfiable
     */
    public int[] scaleToInstances(int newInstances, int oldInstances) {
        if (isAllOld()) {
            return new int[] {0, oldInstances};
        }
        if (isAllNew()) {
            return new int[] {newInstances, 0};
        }
        // Calculate maximum scale factor
        int maxNewScale = newInstances / newWeight;
        int maxOldScale = oldInstances / oldWeight;
        int scaleFactor = Math.min(maxNewScale, maxOldScale);
        if (scaleFactor < 1) {
            return null;
        }
        return new int[] {scaleFactor * newWeight,
        scaleFactor * oldWeight};
    }

    @Override
    public String toString() {
        return newWeight + ":" + oldWeight;
    }
}
