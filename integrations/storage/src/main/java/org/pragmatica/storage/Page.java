package org.pragmatica.storage;

import java.util.List;

import org.pragmatica.lang.Option;


/// One page of a listing; `next` is absent on the last page.
public record Page<T>(List<T> items, Option<Cursor> next) {
    public Page {
        items = List.copyOf(items);
    }

    public static <T> Page<T> page(List<T> items, Option<Cursor> next) {
        return new Page<>(items, next);
    }
}
