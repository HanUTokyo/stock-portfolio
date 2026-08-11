package com.stockportfolio.dto;

public record ValuationMethodsResponse(
        ValuationMethodResponse fcff,
        ValuationMethodResponse fcfe
) { }
