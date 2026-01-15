package com.example.testslice;

import org.pragmatica.lang.Option;

public record SearchRequest(Option<String> query, Option<Integer> limit, Option<Integer> offset) {}
