package com.stockportfolio.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

public record ValuationEvaluateRequest(
        @NotBlank String scenarioType,
        @Valid ValuationAssumptions assumptions
) { }
