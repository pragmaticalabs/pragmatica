package org.pragmatica.aether.pg.codegen.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


/// Specifies the target table for a CRUD method when it cannot be inferred
/// from the return type or input type alone.
///
/// Takes a generated record class; the processor resolves the table from the record's origin.
///
/// Example:
/// ```{@code
/// @Table(UserRow.class)
/// Promise<Option<UserRow>> findById(long id);
/// }```
@Retention(RetentionPolicy.SOURCE) @Target(ElementType.METHOD) public@interface Table {
    Class<?> value();
}
