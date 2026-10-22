// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.json;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.aether.slice.JsonCodecSettings;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.utility.warning.OperatorWarningSink;


/// Provisions a slice's [JsonCodecSettings] from its `[json]` section ([JsonSectionBinder]). The generated factory of
/// every slice with HTTP routes asks for it implicitly (`provide(JsonCodecSettings.class, "json")`), so no slice
/// declares it.
///
/// With the slice's configuration provider in the context the handle is live ([LiveJsonCodecSettings]): it keeps the
/// provider and re-binds on every committed `[json]` change. Without one (no context, or a runtime with no slice
/// composite) it is fixed at the bound selection, which is then the defaults.
public final class JsonCodecSettingsFactory implements ResourceFactory<JsonCodecSettings, JsonSectionConfig> {
    @Override
    public Class<JsonCodecSettings> resourceType() {
        return JsonCodecSettings.class;
    }

    @Override
    public Class<JsonSectionConfig> configType() {
        return JsonSectionConfig.class;
    }

    @Override
    public Promise<JsonCodecSettings> provision(JsonSectionConfig config) {
        return Promise.success(JsonCodecSettings.fixed(config.selected()));
    }

    @Override
    public Promise<JsonCodecSettings> provision(JsonSectionConfig config, ProvisioningContext context) {
        return Promise.success(context.extension(ConfigurationProvider.class)
                                      .option()
                                      .map(provider -> live(config, context, provider))
                                      .or(() -> JsonCodecSettings.fixed(config.selected())));
    }

    @Override
    public Option<SectionBinder<JsonSectionConfig>> sectionBinder() {
        return Option.some((provider, section, _) -> JsonSectionBinder.bind(provider, section));
    }

    private static JsonCodecSettings live(JsonSectionConfig config,
                                          ProvisioningContext context,
                                          ConfigurationProvider provider) {
        var slice = context.extension(String.class).or("<unattributed>");

        return LiveJsonCodecSettings.liveJsonCodecSettings(provider,
                                                           config.section(),
                                                           context.extension(OperatorWarningSink.class)
                                                                  .or(OperatorWarningSink.logOnly()),
                                                           slice + "/" + config.section(),
                                                           config.selected());
    }
}
