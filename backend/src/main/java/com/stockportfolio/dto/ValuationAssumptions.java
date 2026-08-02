package com.stockportfolio.dto;

import java.math.BigDecimal;
import java.util.List;

public record ValuationAssumptions(
        BigDecimal baseCashFlow,
        BigDecimal initialGrowthRatePct,
        BigDecimal discountRatePct,
        BigDecimal terminalGrowthRatePct,
        Integer projectionYears,
        BigDecimal marginOfSafetyPct,
        BigDecimal taxRateOverridePct,
        String baseCashFlowMode,
        String growthMode,
        String discountRateMode,
        List<BigDecimal> annualGrowthRatesPct,
        BigDecimal riskFreeRatePct,
        BigDecimal beta,
        BigDecimal equityRiskPremiumPct
) {
    public ValuationAssumptions(BigDecimal baseCashFlow, BigDecimal initialGrowthRatePct,
                                BigDecimal discountRatePct, BigDecimal terminalGrowthRatePct,
                                Integer projectionYears, BigDecimal marginOfSafetyPct,
                                BigDecimal taxRateOverridePct) {
        this(baseCashFlow, initialGrowthRatePct, discountRatePct, terminalGrowthRatePct,
                projectionYears, marginOfSafetyPct, taxRateOverridePct,
                null, null, null, null, null, null, null);
    }
}
