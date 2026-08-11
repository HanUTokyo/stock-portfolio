package com.stockportfolio.valuation.explicit;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Typed, deterministic output for later integration into the valuation API. */
public record ExplicitOperatingForecastResult(
        String forecastMode,
        List<OperatingForecastYear> operatingSchedule,
        List<DebtForecastYear> debtSchedule,
        ValuationTrack fcff,
        ValuationTrack fcfe,
        SensitivityGrid fcffSensitivity,
        SensitivityGrid fcfeSensitivity,
        ReverseDcf fcffReverseDcf,
        ReverseDcf fcfeReverseDcf
) {
    public ExplicitOperatingForecastResult {
        operatingSchedule = List.copyOf(operatingSchedule);
        debtSchedule = List.copyOf(debtSchedule);
    }

    public record OperatingForecastYear(
            int year,
            boolean explicitPeriod,
            BigDecimal revenueGrowthRate,
            BigDecimal revenue,
            BigDecimal ebitMargin,
            BigDecimal ebit,
            BigDecimal taxRate,
            BigDecimal nopat,
            BigDecimal depreciationAndAmortization,
            BigDecimal capex,
            BigDecimal changeInNetWorkingCapital,
            BigDecimal reinvestment,
            BigDecimal fcff
    ) { }

    public record DebtForecastYear(
            int year,
            DebtFinancingPolicy.Type policyType,
            BigDecimal openingDebt,
            BigDecimal netBorrowing,
            BigDecimal closingDebt,
            BigDecimal averageDebt,
            BigDecimal pretaxInterestExpense,
            BigDecimal afterTaxInterestExpense,
            BigDecimal fcfe
    ) { }

    public record DiscountedCashFlowYear(
            int year,
            BigDecimal cashFlow,
            BigDecimal discountFactor,
            BigDecimal presentValue
    ) { }

    public record ValuationTrack(
            String cashFlowDefinition,
            String discountRateType,
            BigDecimal discountRate,
            List<DiscountedCashFlowYear> discountedCashFlows,
            BigDecimal terminalCashFlow,
            BigDecimal terminalValue,
            BigDecimal presentValueOfExplicitCashFlows,
            BigDecimal presentValueOfTerminalValue,
            BigDecimal enterpriseValue,
            BigDecimal netDebtBridge,
            BigDecimal equityValue
    ) {
        public ValuationTrack {
            discountedCashFlows = List.copyOf(discountedCashFlows);
        }
    }

    public record SensitivityGrid(
            List<BigDecimal> discountRates,
            List<BigDecimal> terminalGrowthRates,
            List<List<BigDecimal>> equityValues
    ) {
        public SensitivityGrid {
            discountRates = List.copyOf(discountRates);
            terminalGrowthRates = List.copyOf(terminalGrowthRates);
            equityValues = equityValues.stream()
                    .map(row -> Collections.unmodifiableList(new ArrayList<>(row)))
                    .toList();
        }
    }

    public record ReverseDcf(
            String status,
            BigDecimal targetEquityValue,
            BigDecimal impliedDiscountRate
    ) { }
}
