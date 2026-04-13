package org.pragmatica.aether.test.persistence;

import org.pragmatica.aether.slice.StreamPublisher;
import org.pragmatica.aether.slice.annotation.ResourceQualifier;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;


@ResourceQualifier(type = StreamPublisher.class, config = "streams.test-events") @Retention(RetentionPolicy.RUNTIME) @Target(ElementType.PARAMETER) public@interface EventStreamPublisher {}
