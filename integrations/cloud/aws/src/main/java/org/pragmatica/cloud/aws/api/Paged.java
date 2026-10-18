// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cloud.aws.api;

import java.util.List;

import org.pragmatica.lang.Option;


/// An EC2 list response that knows which page follows it: the entries of this page and the token that
/// asks for the next one, absent on the last page.
public interface Paged<T> {
    List<T> items();

    Option<String> continuation();
}
