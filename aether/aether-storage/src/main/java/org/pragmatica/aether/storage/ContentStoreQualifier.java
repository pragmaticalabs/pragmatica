package org.pragmatica.aether.storage;

import org.pragmatica.aether.slice.annotation.ResourceQualifier;
import org.pragmatica.storage.ContentStore;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


/// Resource qualifier for injecting ContentStore instances.
///
/// Use this annotation on slice factory method parameters to inject a ContentStore
/// configured from the "storage" section of aether.toml.
///
/// The ContentStore provides content-addressed storage with auto-chunking for large files.
/// A StorageInstance must be registered as a runtime extension in the ProvisioningContext.
///
/// @see ContentStore
/// @see ResourceQualifier
@ResourceQualifier(type = ContentStore.class, config = "storage") @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.PARAMETER) public@interface ContentStoreQualifier {}
