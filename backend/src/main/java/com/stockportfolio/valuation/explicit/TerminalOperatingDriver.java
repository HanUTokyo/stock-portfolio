package com.stockportfolio.valuation.explicit;

import java.math.BigDecimal;

/**
 * Operating state reached in forecast year ten. Revenue growth is supplied by
 * {@link ExplicitOperatingForecastRequest#terminalGrowthRate()}.
 */
public record TerminalOperatingDriver(
        BigDecimal ebitMargin,
        BigDecimal taxRate,
        BigDecimal depreciationAndAmortizationRate,
        BigDecimal capexRate,
        BigDecimal changeInNetWorkingCapitalRate
) { }
