package com.example.testslice;

import org.pragmatica.lang.Option;

public record GetOrdersRequest(Long userId, Option<String> status, Option<Integer> limit) {}
