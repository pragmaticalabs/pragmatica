package org.pragmatica.aether.example.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.example.catalog.Catalog.CatalogError;
import org.pragmatica.aether.example.catalog.Catalog.GetRequest;
import org.pragmatica.aether.example.catalog.Catalog.ListRequest;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

class CatalogTest {
    private Catalog catalog;

    @BeforeEach
    void setUp() {
        catalog = Catalog.catalog();
    }

    @Nested
    class VersionProjection {
        @Test
        void listV1_dropsCurrencyAndTags_whenProjectingFromV2() {
            catalog.listV1(new ListRequest())
                   .await()
                   .onFailure(_ -> fail("listV1 should succeed"))
                   .onSuccess(list -> assertThat(list.items())
                       .isNotEmpty()
                       .allSatisfy(item -> assertThat(item.name()).isNotBlank()));
        }

        @Test
        void getV1_returnsBasicShape_forKnownId() {
            catalog.getV1(new GetRequest(1L))
                   .await()
                   .onFailure(_ -> fail("getV1 should succeed"))
                   .onSuccess(item -> {
                       assertThat(item.id()).isEqualTo(1L);
                       assertThat(item.name()).isEqualTo("Mechanical Keyboard");
                       assertThat(item.priceCents()).isEqualTo(12900L);
                   });
        }

        @Test
        void getV2_retainsCurrencyAndTags_forKnownId() {
            catalog.getV2(new GetRequest(1L))
                   .await()
                   .onFailure(_ -> fail("getV2 should succeed"))
                   .onSuccess(item -> {
                       assertThat(item.currency()).isEqualTo("USD");
                       assertThat(item.tags()).containsExactly("peripherals", "input");
                   });
        }

        @Test
        void listV2_retainsRichShape_forAllItems() {
            catalog.listV2(new ListRequest())
                   .await()
                   .onFailure(_ -> fail("listV2 should succeed"))
                   .onSuccess(list -> assertThat(list.items())
                       .hasSize(6)
                       .allSatisfy(item -> assertThat(item.currency()).isNotBlank()));
        }
    }

    @Nested
    class NotFound {
        @Test
        void getV1_fails_forUnknownId() {
            catalog.getV1(new GetRequest(999L))
                   .await()
                   .onSuccess(_ -> fail("getV1 should fail for unknown id"))
                   .onFailure(cause -> assertThat(cause).isInstanceOf(CatalogError.ItemNotFound.class));
        }

        @Test
        void getV2_fails_forUnknownId() {
            catalog.getV2(new GetRequest(999L))
                   .await()
                   .onSuccess(_ -> fail("getV2 should fail for unknown id"))
                   .onFailure(cause -> assertThat(cause).isInstanceOf(CatalogError.ItemNotFound.class));
        }

        @Test
        void imageV2_fails_forUnknownId() {
            catalog.imageV2(new GetRequest(999L))
                   .await()
                   .onSuccess(_ -> fail("imageV2 should fail for unknown id"))
                   .onFailure(cause -> assertThat(cause).isInstanceOf(CatalogError.ItemNotFound.class));
        }
    }

    @Nested
    class MediaRoutes {
        @Test
        void exportCsvV2_producesHeaderAndRows_withTagsJoinedByPipe() {
            catalog.exportCsvV2(new ListRequest())
                   .await()
                   .onFailure(_ -> fail("exportCsvV2 should succeed"))
                   .onSuccess(csv -> {
                       var lines = csv.split("\n");

                       assertThat(lines[0]).isEqualTo("id,name,priceCents,currency,tags");
                       assertThat(lines).hasSize(7);
                       assertThat(lines[1]).isEqualTo("1,Mechanical Keyboard,12900,USD,peripherals|input");
                   });
        }

        @Test
        void imageV2_returnsNonEmptyBytes_forKnownId() {
            catalog.imageV2(new GetRequest(2L))
                   .await()
                   .onFailure(_ -> fail("imageV2 should succeed"))
                   .onSuccess(bytes -> assertThat(bytes).isNotEmpty());
        }
    }

    @Nested
    class CsvImport {
        @Test
        void importCsvV2_growsStoreAndReturnsCount_forValidRows() {
            var body = "id,name,priceCents,currency,tags\n"
                       + "7,Desk Lamp,2500,USD,lighting|office\n"
                       + "8,Cable Tray,1500,EUR,cable";

            catalog.importCsvV2(body)
                   .await()
                   .onFailure(_ -> fail("importCsvV2 should succeed"))
                   .onSuccess(response -> assertThat(response.imported()).isEqualTo(2));

            catalog.getV2(new GetRequest(7L))
                   .await()
                   .onFailure(_ -> fail("imported item 7 should be retrievable"))
                   .onSuccess(item -> assertThat(item.tags()).containsExactly("lighting", "office"));
        }

        @Test
        void importCsvV2_acceptsBodyWithoutHeader() {
            var body = "9,Monitor Arm,5500,GBP,office";

            catalog.importCsvV2(body)
                   .await()
                   .onFailure(_ -> fail("importCsvV2 should succeed without header"))
                   .onSuccess(response -> assertThat(response.imported()).isEqualTo(1));
        }

        @Test
        void importCsvV2_fails_forMalformedRow() {
            var body = "10,Broken Row,not-a-number,USD,tag";

            catalog.importCsvV2(body)
                   .await()
                   .onSuccess(_ -> fail("importCsvV2 should fail for malformed price"))
                   .onFailure(cause -> assertThat(cause.message()).contains("Invalid"));
        }

        @Test
        void importCsvV2_fails_forTooFewColumns() {
            var body = "11,Incomplete,2500";

            catalog.importCsvV2(body)
                   .await()
                   .onSuccess(_ -> fail("importCsvV2 should fail for too few columns"))
                   .onFailure(cause -> assertThat(cause.message()).contains("Invalid"));
        }

        @Test
        void importCsvV2_parsesUtf8Body_asString() {
            var bytes = "12,Café Mug,1200,EUR,kitchen".getBytes(StandardCharsets.UTF_8);
            var body = new String(bytes, StandardCharsets.UTF_8);

            catalog.importCsvV2(body)
                   .await()
                   .onFailure(_ -> fail("importCsvV2 should parse utf-8 body"))
                   .onSuccess(response -> assertThat(response.imported()).isEqualTo(1));
        }
    }
}
