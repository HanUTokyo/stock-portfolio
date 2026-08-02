package com.stockportfolio.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PositionMetadataRequest(
        String assetClass,
        String instrumentType,
        String underlying,
        String sector,
        String region
) {
}
