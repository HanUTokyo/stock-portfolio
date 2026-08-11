package com.stockportfolio.valuation.explicit;

import java.math.BigDecimal;
import java.util.List;

/**
 * Inputs for a ten-year, dual-track DCF forecast. Monetary inputs must use the
 * same currency and scale. All rate inputs are decimals, not percentage points.
 */
public record ExplicitOperatingForecastRequest(
        BigDecimal startingRevenue,
        BigDecimal openingGrossDebt,
        BigDecimal currentNetDebt,
        BigDecimal waccRate,
        BigDecimal costOfEquityRate,
        BigDecimal pretaxCostOfDebtRate,
        BigDecimal terminalGrowthRate,
        List<OperatingDriver> explicitOperatingDrivers,
        TerminalOperatingDriver terminalOperatingDriver,
        DebtFinancingPolicy debtFinancingPolicy,
        BigDecimal targetEquityValue
) {
    public ExplicitOperatingForecastRequest(BigDecimal startingRevenue, BigDecimal openingGrossDebt,
                                            BigDecimal currentNetDebt, BigDecimal waccRate,
                                            BigDecimal costOfEquityRate, BigDecimal pretaxCostOfDebtRate,
                                            BigDecimal terminalGrowthRate,
                                            List<OperatingDriver> explicitOperatingDrivers,
                                            TerminalOperatingDriver terminalOperatingDriver,
                                            DebtFinancingPolicy debtFinancingPolicy) {
        this(startingRevenue, openingGrossDebt, currentNetDebt, waccRate, costOfEquityRate,
                pretaxCostOfDebtRate, terminalGrowthRate, explicitOperatingDrivers,
                terminalOperatingDriver, debtFinancingPolicy, null);
    }
    public ExplicitOperatingForecastRequest {
        explicitOperatingDrivers = explicitOperatingDrivers == null
                ? List.of()
                : List.copyOf(explicitOperatingDrivers);
    }
}
