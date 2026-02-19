package org.pragmatica.aether.resource.http;

import org.pragmatica.aether.slice.annotation.ResourceQualifier;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/// Resource qualifier for injecting HttpClient instances.
///
/// Use this annotation on factory method parameters to inject an HttpClient
/// configured from the "http" section of aether.toml.
///
/// @see HttpClient
/// @see ResourceQualifier
@ResourceQualifier(type = HttpClient.class, config = "http")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface Http {}
