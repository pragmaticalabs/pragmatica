package org.pragmatica.storage;

/// The catalog contract against [LocalCatalog], over a memory-tier instance.
class LocalCatalogContractTest extends CatalogContract {
    @Override
    Catalog newCatalog() {
        return LocalCatalog.localCatalog(ContentFixture.contentFixture().storage,
                                         Namespace.namespace(CatalogFixture.NAMESPACE).unwrap());
    }
}
