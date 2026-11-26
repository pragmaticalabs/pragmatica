// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.cluster.NodeLifecycleManager;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.ProvisionRequest;
import org.pragmatica.aether.environment.SourceName;
import org.pragmatica.aether.node.assembly.TopologyAssembly;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.node.GenesisSeatRemedy.genesisSeatRemedy;
import static org.pragmatica.aether.node.GenesisSeatRemedy.remedyBinding;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1654: the remedy sentence of the dead-seat warning is true in every state. Each of the three arms (provider with auto-heal on, provider
/// with auto-heal off, no provider) pins its own text; the arms must differ, so a binding that swaps them or ignores a state fails here.
class GenesisSeatRemedyTest {
    @Test
    void autoHealOn_namesTheReplacementEstimate_andNoManualToggle() {
        var text = genesisSeatRemedy(true, true, AutoHealConfig.DEFAULT);

        assertThat(text).contains("Auto-heal is on", "replacement core is typically expected", "within ~75 s", "an estimate, not a deadline")
                        .doesNotContain("no replacement will be provisioned", "auto-heal/enable", "No compute provider");
    }

    @Test
    void autoHealOn_boundFollowsTheConfig() {
        var config = AutoHealConfig.DEFAULT.withStartupCooldown(timeSpan(5).seconds())
                                           .withProvisioningTimeout(timeSpan(20).seconds());

        assertThat(genesisSeatRemedy(true, true, config)).contains("within ~25 s");
    }

    @Test
    void autoHealOff_saysNoReplacementComes_andNamesTheManualRemedy() {
        var text = genesisSeatRemedy(false, true, AutoHealConfig.DEFAULT);

        assertThat(text).contains("Auto-heal is off", "no replacement will be provisioned", "restart that node", "POST /api/cluster/topology/auto-heal/enable")
                        .doesNotContain("replacement core is typically expected", "No compute provider");
    }

    @Test
    void noProvider_withAutoHealOn_promisesNoReplacement_andNamesTheRemedies() {
        var text = genesisSeatRemedy(true, false, AutoHealConfig.DEFAULT);

        assertThat(text).contains("No compute provider is available", "no replacement can be provisioned", "restart that node", "configure a compute provider")
                        .doesNotContain("replacement core is typically expected", "within ~");
    }

    @Test
    void noProvider_withAutoHealOff_isTheSameArm_andDoesNotOfferAnEnableThatCannotHelp() {
        var text = genesisSeatRemedy(false, false, AutoHealConfig.DEFAULT);

        assertThat(text).isEqualTo(genesisSeatRemedy(true, false, AutoHealConfig.DEFAULT))
                        .doesNotContain("auto-heal/enable", "replacement core is typically expected");
    }

    /// The WIRING: the node hands the engine `remedyBinding` over its real lifecycle manager, so a node without a compute provider gets the
    /// no-provider text and one with a local provider gets the auto-heal arms. A generic cloud `compute()` with no committed source is not a
    /// provider here (the manager's own gate, pinned by AetherNodeSourceRoutingTest), so it also gets the no-provider text.
    @Test
    void binding_nodeWithoutComputeProvider_getsTheNoProviderText_evenWithAutoHealOn() {
        var text = remedyBinding(() -> true, lifecycle(Option.none()), AutoHealConfig.DEFAULT).get();

        assertThat(text).contains("No compute provider is available")
                        .doesNotContain("replacement core is typically expected");
    }

    @Test
    void binding_genericCloudComputeWithoutCommittedSource_isNotAProvider() {
        var lifecycle = lifecycle(Option.some(EnvironmentIntegration.withCompute(new IdleCompute())));

        assertThat(remedyBinding(() -> true, lifecycle, AutoHealConfig.DEFAULT).get()).contains("No compute provider is available");
    }

    @Test
    void binding_nodeWithLocalProvider_getsTheAutoHealArms_readAtEveryCall() {
        var lifecycle = lifecycle(Option.some(EnvironmentIntegration.withLocalCompute(new IdleCompute())));
        var autoHeal = new AtomicBoolean(true);
        var binding = remedyBinding(autoHeal::get, lifecycle, AutoHealConfig.DEFAULT);

        assertThat(binding.get()).contains("Auto-heal is on").doesNotContain("No compute provider");
        autoHeal.set(false);
        assertThat(binding.get()).contains("Auto-heal is off").doesNotContain("No compute provider");
    }

    private static NodeLifecycleManager lifecycle(Option<EnvironmentIntegration> environment) {
        return TopologyAssembly.sourceLifecycleManager(environment,
                                                       Option.none(),
                                                       Option.none(),
                                                       Option.none(),
                                                       Option::none,
                                                       _ -> Result.success(SourceName.DEFAULT));
    }

    private static final class IdleCompute implements ComputeProvider {
        @Override
        public Promise<InstanceInfo> createFrom(ProvisionRequest request) {
            return EnvironmentError.operationNotSupported("create").promise();
        }

        @Override
        public Promise<Unit> terminate(InstanceId id) {
            return Promise.unitPromise();
        }

        @Override
        public Promise<List<InstanceInfo>> listInstances() {
            return Promise.success(List.of());
        }

        @Override
        public Promise<InstanceInfo> instanceStatus(InstanceId id) {
            return EnvironmentError.operationNotSupported("status").promise();
        }
    }
}
