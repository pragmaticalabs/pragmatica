package org.pragmatica.storage;

/// The catalog contract against the pure core behind the seam ([TreeCatalog]), with a fanout of 2 so even these small
/// catalogs have branch pages.
class TreeCatalogContractTest extends CatalogContract {
    @Override
    Catalog newCatalog() {
        return new TreeCatalog(2);
    }
}
