package org.pragmatica.postgres;

import org.pragmatica.postgres.net.Converter;
import org.pragmatica.postgres.net.PgValue;
import org.pragmatica.postgres.net.PgWriter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author Antti Laisi
 */
@Tag("Slow")
public class CustomConverterTest {

    static class Json {
        final String json;

        Json(String json) {
            this.json = json;
        }
    }

    static class JsonConverter implements Converter<Json> {
        @Override
        public Class<Json> type() {
            return Json.class;
        }

        @Override
        public void from(Json o, PgWriter writer) {
            writer.writeText(o.json);
        }

        @Override
        public Json to(PgValue value) {
            return switch (value) {
                case PgValue.Text text -> new Json(text.asString());
                case PgValue.Binary binary -> new Json(new String(binary.asBytes()));
            };
        }
    }

    @RegisterExtension
    static final DatabaseExtension dbr = DatabaseExtension.withConverter(new JsonConverter());

    @BeforeAll
    public static void create() {
        drop();
        dbr.query("CREATE TABLE CC_TEST (ID BIGINT, JS JSON)");
    }

    @AfterAll
    public static void drop() {
        dbr.query("DROP TABLE IF EXISTS CC_TEST");
    }

    @Test
    public void shouldConvertColumnDataToType() {
        dbr.query("INSERT INTO CC_TEST VALUES (1, $1)", List.of(new Json("{\"a\": 1}")));
        assertEquals("{\"a\": 1}", dbr.query("SELECT * FROM CC_TEST WHERE ID = 1").index(0).get("js", Json.class).json);
    }

    @Test
    public void shouldConvertParameter() {
        dbr.query("INSERT INTO CC_TEST VALUES (2, $1)", List.of(new Json("{\"b\": 2}")));
        assertEquals("{\"b\": 2}", dbr.query("SELECT * FROM CC_TEST WHERE ID = 2").index(0).get("js", Json.class).json);
    }

}
