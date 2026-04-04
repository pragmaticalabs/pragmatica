package org.pragmatica.aether.storage;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.storage.ContentStore;
import org.pragmatica.storage.ContentStoreConfig;
import org.pragmatica.storage.StorageInstance;


/// ResourceFactory SPI implementation for provisioning ContentStore instances.
///
/// Discovered via ServiceLoader. Creates ContentStore instances using a StorageInstance
/// from the ProvisioningContext extensions and ContentStoreConfig from TOML configuration.
///
/// The StorageInstance must be registered as a runtime extension by the node infrastructure
/// before slice provisioning occurs.
public final class ContentStoreFactory implements ResourceFactory<ContentStore, ContentStoreConfig> {
    private static final Cause REQUIRES_CONTEXT = Causes.cause("ContentStore requires ProvisioningContext with StorageInstance extension");

    @Override public Class<ContentStore> resourceType() {
        return ContentStore.class;
    }

    @Override public Class<ContentStoreConfig> configType() {
        return ContentStoreConfig.class;
    }

    @Override public Promise<ContentStore> provision(ContentStoreConfig config) {
        return REQUIRES_CONTEXT.promise();
    }

    @Override public Promise<ContentStore> provision(ContentStoreConfig config, ProvisioningContext context) {
        return context.extension(StorageInstance.class).map(instance -> ContentStore.contentStore(instance, config))
                                .async();
    }

    @Override public Promise<Unit> close(ContentStore resource) {
        return Promise.unitPromise();
    }
}
