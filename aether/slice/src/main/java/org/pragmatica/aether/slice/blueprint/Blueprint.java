package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.util.List;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.Verify.Is;
import static org.pragmatica.lang.Verify.ensure;


/// Parsed blueprint describing which slices to deploy and how.
///
/// @param id the blueprint identifier
/// @param slices the list of slice specifications
/// @param deploymentConfig optional deployment strategy configuration
/// @param securityOverrides security overrides from [security.overrides] section
@SuppressWarnings({"JBCT-NAM-01", "JBCT-UTIL-02", "JBCT-ZONE-02"}) public record Blueprint(BlueprintId id,
                                                                                           List<SliceSpec> slices,
                                                                                           Option<DeploymentConfig> deploymentConfig,
                                                                                           SecurityOverrides securityOverrides) {
    private static final Cause NULL_ID = Causes.cause("Blueprint ID cannot be null");

    private static final Cause NULL_SLICES = Causes.cause("Slices list cannot be null");

    private static final Cause EMPTY_SLICES = Causes.cause("Slices list cannot be empty");

    public static Result<Blueprint> blueprint(BlueprintId id,
                                              List<SliceSpec> slices,
                                              Option<DeploymentConfig> deploymentConfig,
                                              SecurityOverrides securityOverrides) {
        return Result.all(ensure(id, Is::notNull, NULL_ID),
                          ensure(slices, Is::notNull, NULL_SLICES))
        .flatMap((validId, validSlices) -> validateNonEmpty(validId, validSlices, deploymentConfig, securityOverrides));
    }

    public static Result<Blueprint> blueprint(BlueprintId id,
                                              List<SliceSpec> slices,
                                              Option<DeploymentConfig> deploymentConfig) {
        return blueprint(id, slices, deploymentConfig, SecurityOverrides.EMPTY);
    }

    public static Result<Blueprint> blueprint(BlueprintId id, List<SliceSpec> slices) {
        return blueprint(id, slices, none());
    }

    private static Result<Blueprint> validateNonEmpty(BlueprintId id,
                                                      List<SliceSpec> slices,
                                                      Option<DeploymentConfig> deploymentConfig,
                                                      SecurityOverrides securityOverrides) {
        if (slices.isEmpty()) {return EMPTY_SLICES.result();}
        return success(new Blueprint(id, List.copyOf(slices), deploymentConfig, securityOverrides));
    }
}
