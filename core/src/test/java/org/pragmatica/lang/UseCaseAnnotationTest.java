package org.pragmatica.lang;

import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class UseCaseAnnotationTest {
    @UseCase
    interface AnnotatedUseCase {
        Result<String> execute(String request);
    }

    @Test
    void useCase_compilesOnInterfaceType() {
        AnnotatedUseCase useCase = Result::success;

        assertEquals("ok", useCase.execute("ok").unwrap());
    }

    @Test
    void useCase_targetsTypesOnly() {
        assertArrayEquals(new ElementType[] {ElementType.TYPE}, UseCase.class.getAnnotation(Target.class).value());
    }

    @Test
    void useCase_retainedAtSourceLikeSiblings() {
        assertEquals(RetentionPolicy.SOURCE, UseCase.class.getAnnotation(Retention.class).value());
        assertEquals(RetentionPolicy.SOURCE, Contract.class.getAnnotation(Retention.class).value());
    }
}
