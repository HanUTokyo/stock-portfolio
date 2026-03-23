package com.stockportfolio.dto;

import java.math.BigDecimal;

public record HoldingResponse(
        String symbol,
        BigDecimal quantity,
        BigDecimal averageCost,
        BigDecimal costBasis,
        BigDecimal latestPrice,
        BigDecimal latestPe,
        BigDecimal marketValue,
        BigDecimal unrealizedPnl
) {
}
