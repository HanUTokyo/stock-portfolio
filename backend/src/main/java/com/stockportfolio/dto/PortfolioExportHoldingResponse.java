package com.stockportfolio.dto;

import java.math.BigDecimal;

public record PortfolioExportHoldingResponse(
        String symbol,
        BigDecimal quantity,
        BigDecimal latestPrice,
        BigDecimal marketValue,
        BigDecimal weightPct,
        BigDecimal unrealizedPnl,
        BigDecimal unrealizedPnlPct,
        BigDecimal latestPe,
        BigDecimal dividendIncome,
        BigDecimal yieldOnCostPct,
        String stockNote
) {
}
