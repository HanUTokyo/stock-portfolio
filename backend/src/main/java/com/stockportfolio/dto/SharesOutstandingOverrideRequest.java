package com.stockportfolio.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record SharesOutstandingOverrideRequest(
        BigDecimal sharesOutstandingOverride
) {
}
