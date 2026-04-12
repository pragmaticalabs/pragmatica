package org.pragmatica.aether.environment;


/// Cloud provider quota check result. Section 11.1
public record QuotaStatus(boolean sufficient, int requested, int availableInRegion, String limitingResource) {
    public static QuotaStatus quotaStatus(boolean sufficient, int requested, int availableInRegion, String limitingResource) {
        return new QuotaStatus(sufficient, requested, availableInRegion, limitingResource);
    }

    public static QuotaStatus unknown(int requested) {
        return new QuotaStatus(true, requested, -1, "unknown");
    }
}
