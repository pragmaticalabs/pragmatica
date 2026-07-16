package org.pragmatica.aether.example.catalog;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.parse.Number;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.success;


/// Catalog slice — living documentation for two just-landed rc2 platform features:
///
///   - #339 media types: `produces` / `consumes` inline-table routes (text/csv, octet-stream).
///   - #198 API versioning: ONE deployable artifact declares `v1` (deprecated) + `v2` (current)
///     together; clients pick the version via path segment or `API-Version` header.
///
/// Resource-free by design: a real slice would persist catalog data via `@PgSql` / KV and emit
/// `@Notify` events; here items are seeded in-memory so the example stays focused on routing,
/// media types and versioning. `ItemV2` is the storage superset — `v1` responses are projections
/// of it (single source of truth, no dual store).
///
/// Everything the slice needs at runtime — request/response records, the `CatalogError` failures,
/// the seed data and the implementation — is nested inside this `@Slice` interface. The slice
/// packager walks only the `@Slice` type's class nest when building the deploy envelope, so any
/// sibling top-level helper would be silently omitted and fail with `NoClassDefFoundError` on the
/// node (build + unit tests run against the full classpath and would not catch it).
@Slice
public interface Catalog {
    record ListRequest() {}

    record GetRequest(Long id) {}

    record ItemV1(long id, String name, long priceCents) {}

    record ItemV2(long id, String name, long priceCents, String currency, List<String> tags) {}

    record ItemListV1(List<ItemV1> items) {}

    record ItemListV2(List<ItemV2> items) {}

    record ImportResponse(int imported) {}

    /// Typed failures for the catalog slice. Class names carry the `*NotFound*` / `*Invalid*`
    /// markers that `routes.toml` maps to HTTP 404 / 400.
    sealed interface CatalogError extends Cause {
        record ItemNotFound(long id) implements CatalogError {
            @Override
            public String message() {
                return "Item not found: " + id;
            }
        }

        record InvalidCsvLine(String line) implements CatalogError {
            @Override
            public String message() {
                return "Invalid CSV line: " + line;
            }
        }

        static CatalogError itemNotFound(long id) {
            return new ItemNotFound(id);
        }

        static CatalogError invalidCsvLine(String line) {
            return new InvalidCsvLine(line);
        }
    }

    // v1 (deprecated) — basic shape, projected from the v2 superset
    Promise<ItemListV1> listV1(ListRequest request);
    Promise<ItemV1> getV1(GetRequest request);
    // v2 (current) — rich shape + media routes
    Promise<ItemListV2> listV2(ListRequest request);
    Promise<ItemV2> getV2(GetRequest request);
    Promise<String> exportCsvV2(ListRequest request);  // produces text/csv → String body
    Promise<byte[]> imageV2(GetRequest request);  // produces application/octet-stream → raw bytes
    Promise<ImportResponse> importCsvV2(String body);  // consumes text/csv → String param

    static Catalog catalog() {
        return new catalog(catalog.seedStore());
    }

    record catalog(ConcurrentHashMap<Long, ItemV2> items) implements Catalog {
        private static final String CSV_HEADER = "id,name,priceCents,currency,tags";
        private static final String TAG_SEPARATOR = "|";

        private static final int IMAGE_SIZE = 16;

        /// In-memory seed (a production slice would load this from `@PgSql` / KV). `ItemV2` is the
        /// storage superset; `v1` responses are projected from it.
        private static final List<ItemV2> SEED = List.of(new ItemV2(1L,
                                                                    "Mechanical Keyboard",
                                                                    12900L,
                                                                    "USD",
                                                                    List.of("peripherals", "input")),
                                                         new ItemV2(2L,
                                                                    "27-inch Monitor",
                                                                    34900L,
                                                                    "USD",
                                                                    List.of("display", "office")),
                                                         new ItemV2(3L,
                                                                    "USB-C Hub",
                                                                    4500L,
                                                                    "EUR",
                                                                    List.of("accessories", "connectivity")),
                                                         new ItemV2(4L,
                                                                    "Ergonomic Mouse",
                                                                    6900L,
                                                                    "USD",
                                                                    List.of("peripherals", "input")),
                                                         new ItemV2(5L,
                                                                    "Laptop Stand",
                                                                    3900L,
                                                                    "GBP",
                                                                    List.of("accessories", "office")),
                                                         new ItemV2(6L,
                                                                    "Webcam 4K",
                                                                    8900L,
                                                                    "EUR",
                                                                    List.of("video", "office")));

        private static ConcurrentHashMap<Long, ItemV2> seedStore() {
            var store = new ConcurrentHashMap<Long, ItemV2>();

            SEED.forEach(item -> store.put(item.id(), item));

            return store;
        }

        @Override
        public Promise<ItemListV1> listV1(ListRequest request) {
            return Promise.success(new ItemListV1(projectAll()));
        }

        @Override
        public Promise<ItemV1> getV1(GetRequest request) {
            return findById(request.id()).map(catalog::toItemV1)
                           .async();
        }

        @Override
        public Promise<ItemListV2> listV2(ListRequest request) {
            return Promise.success(new ItemListV2(snapshot()));
        }

        @Override
        public Promise<ItemV2> getV2(GetRequest request) {
            return findById(request.id()).async();
        }

        @Override
        public Promise<String> exportCsvV2(ListRequest request) {
            return Promise.success(renderCsv());
        }

        @Override
        public Promise<byte[]> imageV2(GetRequest request) {
            return findById(request.id()).map(catalog::renderImage)
                           .async();
        }

        @Override
        public Promise<ImportResponse> importCsvV2(String body) {
            return parseCsv(body).map(this::storeAll)
                           .map(ImportResponse::new)
                           .async();
        }

        private List<ItemV2> snapshot() {
            return items.values()
                        .stream()
                        .sorted(Comparator.comparingLong(ItemV2::id))
                        .toList();
        }

        private List<ItemV1> projectAll() {
            return snapshot().stream()
                           .map(catalog::toItemV1)
                           .toList();
        }

        private Result<ItemV2> findById(long id) {
            return option(items.get(id)).toResult(CatalogError.itemNotFound(id));
        }

        private static ItemV1 toItemV1(ItemV2 item) {
            return new ItemV1(item.id(), item.name(), item.priceCents());
        }

        private static byte[] renderImage(ItemV2 item) {
            var bytes = new byte[IMAGE_SIZE];

            Arrays.fill(bytes, (byte)(item.id() & 0xFF));

            return bytes;
        }

        private String renderCsv() {
            return snapshot().stream()
                           .map(catalog::toCsvRow)
                           .collect(Collectors.joining("\n", CSV_HEADER + "\n", ""));
        }

        private static String toCsvRow(ItemV2 item) {
            return item.id()
                 + "," + item.name()
                 + "," + item.priceCents()
                 + "," + item.currency()
                 + "," + String.join(TAG_SEPARATOR, item.tags());
        }

        private int storeAll(List<ItemV2> parsed) {
            parsed.forEach(item -> items.put(item.id(), item));

            return parsed.size();
        }

        private static Result<List<ItemV2>> parseCsv(String body) {
            return Result.allOf(dataLines(body).map(catalog::parseLine).toList());
        }

        private static Stream<String> dataLines(String body) {
            return body.lines()
                       .map(String::trim)
                       .filter(line -> !line.isEmpty())
                       .filter(catalog::isNotHeader);
        }

        private static boolean isNotHeader(String line) {
            return ! line.equals(CSV_HEADER);
        }

        private static Result<ItemV2> parseLine(String line) {
            return splitRow(line).flatMap(catalog::buildItem);
        }

        private static Result<String[]> splitRow(String line) {
            var fields = line.split(",", -1);

            return fields.length >= 4
                   ? success(fields)
                   : CatalogError.invalidCsvLine(line).result();
        }

        private static Result<ItemV2> buildItem(String[] fields) {
            return Result.all(Number.parseLong(fields[0].trim()),
                              Number.parseLong(fields[2].trim()))
                         .map((id, priceCents) -> assembleItem(id, fields, priceCents))
                         .mapError(_ -> CatalogError.invalidCsvLine(String.join(",", fields)));
        }

        private static ItemV2 assembleItem(long id, String[] fields, long priceCents) {
            return new ItemV2(id, fields[1].trim(), priceCents, fields[3].trim(), parseTags(fields));
        }

        private static List<String> parseTags(String[] fields) {
            return fields.length >= 5 && !fields[4].isBlank()
                   ? List.of(fields[4].trim().split("\\" + TAG_SEPARATOR))
                   : List.of();
        }
    }
}
