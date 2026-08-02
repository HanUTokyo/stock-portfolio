package com.stockportfolio.dto;

import jakarta.validation.Valid;

public record ValuationSaveRequest(
        String modelMode,
        @Valid ValuationAssumptions assumptions
) { }
