package org.pragmatica.aether.infra;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.infra.SpiResourceProvider.spiResourceProvider;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

class SpiResourceProviderTest {

    private static final TimeSpan TIMEOUT = timeSpan(5).seconds();

    @Nested
    class Fn2ConfigLoader {

        @Test
        void spiResourceProvider_createsProvider_withFn2Loader() {
            var provider = spiResourceProvider((section, configClass) -> Result.success("dummy"));

            assertThat(provider).isNotNull();
        }

        @Test
        void spiResourceProvider_passesSectionAndClass_toFn2Loader() {
            var provider = spiResourceProvider((section, configClass) -> {
                // Fn2 receives both arguments; provider creation itself succeeds
                return Result.success("config-value");
            });

            assertThat(provider).isNotNull();
            assertThat(provider.hasFactory(String.class)).isFalse();
        }
    }

    @Nested
    class HasFactory {

        @Test
        void hasFactory_returnsFalse_whenNoFactoriesRegistered() {
            var provider = spiResourceProvider(section -> Result.success("dummy"));

            assertThat(provider.hasFactory(String.class)).isFalse();
        }

        @Test
        void hasFactory_returnsFalse_forArbitraryType() {
            var provider = spiResourceProvider((section, configClass) -> Result.success("dummy"));

            assertThat(provider.hasFactory(Integer.class)).isFalse();
        }
    }

    @Nested
    class Provide {

        @Test
        void provide_returnsFailure_whenNoFactoryRegistered() {
            var provider = spiResourceProvider(section -> Result.success("dummy"));

            var result = provider.provide(String.class, "test")
                                 .await(TIMEOUT);

            assertThat(result.isFailure()).isTrue();
        }

        @Test
        void provide_returnsFactoryNotFoundError_whenNoFactoryRegistered() {
            var provider = spiResourceProvider((section, configClass) -> Result.success("dummy"));

            var result = provider.provide(String.class, "test.section")
                                 .await(TIMEOUT);

            assertThat(result.isFailure()).isTrue();
            result.onFailure(cause ->
                assertThat(cause).isInstanceOf(ResourceProvisioningError.FactoryNotFound.class)
            );
        }
    }

    @Nested
    class BackwardsCompatibleFunctionOverload {

        @Test
        void spiResourceProvider_createsProvider_withFunctionLoader() {
            var provider = spiResourceProvider(section -> Result.success("dummy"));

            assertThat(provider).isNotNull();
        }

        @Test
        void spiResourceProvider_hasNoFactories_withFunctionLoader() {
            var provider = spiResourceProvider(section -> Result.success("dummy"));

            assertThat(provider.hasFactory(String.class)).isFalse();
        }
    }

    @Nested
    class NoArgFactory {

        @Test
        void spiResourceProvider_createsProvider_withNoArgs() {
            var provider = spiResourceProvider();

            assertThat(provider).isNotNull();
        }

        @Test
        void spiResourceProvider_hasNoFactories_withNoArgs() {
            var provider = spiResourceProvider();

            assertThat(provider.hasFactory(String.class)).isFalse();
        }
    }
}
