package com.stockportfolio.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record MarketAssumptionsResponse(
        String symbol,
        BigDecimal riskFreeRate,
        String riskFreeMaturity,
        LocalDate riskFreeDate,
        String riskFreeSource,
        BigDecimal beta,
        String betaSource,
        List<String> warnings
) {
}
