package com.stockportfolio.valuation.explicit;

import java.math.BigDecimal;

/**
 * Operating assumptions for one explicit forecast year. Rates are decimals
 * (for example, 8% is represented as 0.08).
 */
public record OperatingDriver(
        BigDecimal revenueGrowthRate,
        BigDecimal ebitMargin,
        BigDecimal taxRate,
        BigDecimal depreciationAndAmortizationRate,
        BigDecimal capexRate,
        BigDecimal changeInNetWorkingCapitalRate
) { }
