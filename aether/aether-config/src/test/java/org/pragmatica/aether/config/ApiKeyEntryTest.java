package org.pragmatica.aether.config;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApiKeyEntryTest {

    @Test
    void apiKeyEntry_createsWithGivenNameAndRoles() {
        var entry = ApiKeyEntry.apiKeyEntry("my-service", Set.of("admin", "service"));

        assertThat(entry.name()).isEqualTo("my-service");
        assertThat(entry.roles()).containsExactlyInAnyOrder("admin", "service");
    }

    @Test
    void defaultEntry_usesHashDerivedNameAndServiceRole() {
        var entry = ApiKeyEntry.defaultEntry("short");

        assertThat(entry.name()).isEqualTo("key-" + Integer.toHexString("short".hashCode()));
        assertThat(entry.roles()).containsExactly("service");
    }

    @Test
    void defaultEntry_usesHashDerivedNameForLongKeys() {
        var entry = ApiKeyEntry.defaultEntry("very-long-api-key-value-12345");

        assertThat(entry.name()).isEqualTo("key-" + Integer.toHexString("very-long-api-key-value-12345".hashCode()));
        assertThat(entry.name()).startsWith("key-");
        assertThat(entry.roles()).containsExactly("service");
    }

    @Test
    void defaultEntry_usesHashDerivedNameForExactly8CharKey() {
        var entry = ApiKeyEntry.defaultEntry("12345678");

        assertThat(entry.name()).isEqualTo("key-" + Integer.toHexString("12345678".hashCode()));
    }

    @Test
    void defaultEntry_producesConsistentNamesForSameInput() {
        var entry1 = ApiKeyEntry.defaultEntry("123456789");
        var entry2 = ApiKeyEntry.defaultEntry("123456789");

        assertThat(entry1.name()).isEqualTo(entry2.name());
        assertThat(entry1.name()).startsWith("key-");
    }

    @Test
    void constructor_defaultsNullNameToUnnamed() {
        var entry = new ApiKeyEntry(null, Set.of("admin"));

        assertThat(entry.name()).isEqualTo("unnamed");
    }

    @Test
    void constructor_defaultsBlankNameToUnnamed() {
        var entry = new ApiKeyEntry("   ", Set.of("admin"));

        assertThat(entry.name()).isEqualTo("unnamed");
    }

    @Test
    void constructor_defaultsNullRolesToService() {
        var entry = new ApiKeyEntry("my-key", null);

        assertThat(entry.roles()).containsExactly("service");
    }

    @Test
    void constructor_defaultsEmptyRolesToService() {
        var entry = new ApiKeyEntry("my-key", Set.of());

        assertThat(entry.roles()).containsExactly("service");
    }

    @Test
    void roles_areImmutable() {
        var entry = ApiKeyEntry.apiKeyEntry("svc", Set.of("admin", "service"));

        assertThatThrownBy(() -> entry.roles().add("hacker"))
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
