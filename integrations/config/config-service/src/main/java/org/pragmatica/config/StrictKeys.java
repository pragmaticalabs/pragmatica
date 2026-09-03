package org.pragmatica.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


/// Opt-in marker for the reflective record binder (see `ProviderBasedConfigService#bindToClass`):
/// when present on a config record, every key found at the level the record's own TOML section
/// binds must correspond to one of the record's components (compared via the same `toSnakeCase`
/// convention the binder already uses to derive keys). Any leftover key — most commonly a dashed
/// key where the binder expects underscores — fails the bind with [ConfigError.UnknownKey] instead
/// of silently resolving to [Option#none()] or a component's `DEFAULT`.
///
/// Scoped to exactly the keys the annotated record binds: a nested sub-section (for example
/// `[topics.orders.consumers.x]`, owned by a different, dashed-by-convention parser) is never
/// inspected by this check, however it is spelled.
///
/// Classes without this annotation bind exactly as before — this is opt-in per config class, not a
/// change to the shared binder's default behavior.
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface StrictKeys {}
