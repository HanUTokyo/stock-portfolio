package com.stockportfolio.dto;

import java.math.BigDecimal;

public record PortfolioSummaryResponse(
        int totalPositions,
        int trackedSymbols,
        int currentHoldings,
        BigDecimal totalCostBasis,
        BigDecimal totalUnits,
        BigDecimal totalMarketValue,
        BigDecimal totalUnrealizedPnl,
        BigDecimal totalRealizedGain
) {
}
