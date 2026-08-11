package com.stockportfolio.dto;

import java.math.BigDecimal;
import java.util.List;

public record DiscountRateBreakdownResponse(
        String status,
        String rateType,
        BigDecimal riskFreeRatePct,
        BigDecimal beta,
        BigDecimal equityRiskPremiumPct,
        BigDecimal costOfEquityPct,
        BigDecimal preTaxCostOfDebtPct,
        BigDecimal afterTaxCostOfDebtPct,
        BigDecimal equityWeightPct,
        BigDecimal debtWeightPct,
        BigDecimal discountRatePct,
        List<String> missingInputs
) { }
