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
        BigDecimal equityRiskPremiumPct,
        WaccReferenceSelection fcffWaccSelection,
        BigDecimal fcffCashInterestReference
) {
    public ValuationAssumptions(BigDecimal baseCashFlow, BigDecimal initialGrowthRatePct,
                                BigDecimal discountRatePct, BigDecimal terminalGrowthRatePct,
                                Integer projectionYears, BigDecimal marginOfSafetyPct,
                                BigDecimal taxRateOverridePct, String baseCashFlowMode,
                                String growthMode, String discountRateMode,
                                List<BigDecimal> annualGrowthRatesPct, BigDecimal riskFreeRatePct,
                                BigDecimal beta, BigDecimal equityRiskPremiumPct,
                                WaccReferenceSelection fcffWaccSelection) {
        this(baseCashFlow, initialGrowthRatePct, discountRatePct, terminalGrowthRatePct,
                projectionYears, marginOfSafetyPct, taxRateOverridePct, baseCashFlowMode,
                growthMode, discountRateMode, annualGrowthRatesPct, riskFreeRatePct, beta,
                equityRiskPremiumPct, fcffWaccSelection, null);
    }
    /** Kept for pre-v3 callers; v3 adds only the optional immutable FCFF WACC snapshot. */
    public ValuationAssumptions(BigDecimal baseCashFlow, BigDecimal initialGrowthRatePct,
                                BigDecimal discountRatePct, BigDecimal terminalGrowthRatePct,
                                Integer projectionYears, BigDecimal marginOfSafetyPct,
                                BigDecimal taxRateOverridePct, String baseCashFlowMode,
                                String growthMode, String discountRateMode,
                                List<BigDecimal> annualGrowthRatesPct, BigDecimal riskFreeRatePct,
                                BigDecimal beta, BigDecimal equityRiskPremiumPct) {
        this(baseCashFlow, initialGrowthRatePct, discountRatePct, terminalGrowthRatePct,
                projectionYears, marginOfSafetyPct, taxRateOverridePct, baseCashFlowMode,
                growthMode, discountRateMode, annualGrowthRatesPct, riskFreeRatePct, beta,
                equityRiskPremiumPct, null, null);
    }
    public ValuationAssumptions(BigDecimal baseCashFlow, BigDecimal initialGrowthRatePct,
                                BigDecimal discountRatePct, BigDecimal terminalGrowthRatePct,
                                Integer projectionYears, BigDecimal marginOfSafetyPct,
                                BigDecimal taxRateOverridePct) {
        this(baseCashFlow, initialGrowthRatePct, discountRatePct, terminalGrowthRatePct,
                projectionYears, marginOfSafetyPct, taxRateOverridePct,
                null, null, null, null, null, null, null, null, null);
    }
}
