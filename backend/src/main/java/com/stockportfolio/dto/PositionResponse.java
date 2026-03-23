package com.stockportfolio.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record PositionResponse(
        Long id,
        String symbol,
        BigDecimal quantity,
        BigDecimal averageCost,
        BigDecimal latestPrice,
        BigDecimal latestPe,
        OffsetDateTime priceUpdatedAt,
        OffsetDateTime peUpdatedAt,
        OffsetDateTime updatedAt
) {
}
