package org.pragmatica.aether.slice.annotation;

import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.RetentionPolicy;

import static org.assertj.core.api.Assertions.assertThat;

class PartitionKeyTest {

    @Test
    void retention_isRuntime() {
        var retention = PartitionKey.class.getAnnotation(java.lang.annotation.Retention.class);

        assertThat(retention).isNotNull();
        assertThat(retention.value()).isEqualTo(RetentionPolicy.RUNTIME);
    }

    @Test
    void target_isRecordComponent() {
        var target = PartitionKey.class.getAnnotation(java.lang.annotation.Target.class);

        assertThat(target).isNotNull();
        assertThat(target.value()).containsExactly(ElementType.RECORD_COMPONENT);
    }

    @Test
    void isAnnotation() {
        assertThat(PartitionKey.class.isAnnotation()).isTrue();
    }
}
