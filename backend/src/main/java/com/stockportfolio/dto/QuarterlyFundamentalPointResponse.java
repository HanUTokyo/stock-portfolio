package com.stockportfolio.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

public record QuarterlyFundamentalPointResponse(
        LocalDate asOfDate,
        BigDecimal basicEps,
        BigDecimal ttmEps,
        BigDecimal forwardEps,
        BigDecimal cashFlow,
        BigDecimal fcf,
        BigDecimal capex,
        BigDecimal adjustedFcf,
        BigDecimal roe,
        BigDecimal roic,
        BigDecimal grossMargin,
        BigDecimal revenue,
        BigDecimal grossProfit,
        BigDecimal operatingIncome,
        BigDecimal interestExpense,
        BigDecimal netIncome,
        BigDecimal stockholdersEquity,
        BigDecimal totalDebt,
        BigDecimal cashAndEquivalents,
        BigDecimal shortTermInvestments,
        BigDecimal noncurrentMarketableSecurities,
        BigDecimal taxProvision,
        BigDecimal pretaxIncome,
        BigDecimal investedCapital,
        boolean forecast,
        BigDecimal dilutedEps,
        BigDecimal dilutedWeightedAverageShares,
        BigDecimal depreciationAmortization,
        BigDecimal changeInWorkingCapital,
        BigDecimal netBorrowing,
        BigDecimal shareRepurchases,
        BigDecimal totalAssets,
        Integer fiscalYear,
        String fiscalPeriod,
        LocalDate filingDate,
        Map<String, FieldSourceResponse> fieldMetadata
) {
    public QuarterlyFundamentalPointResponse(
            LocalDate asOfDate,
            BigDecimal basicEps,
            BigDecimal ttmEps,
            BigDecimal forwardEps,
            BigDecimal cashFlow,
            BigDecimal fcf,
            BigDecimal capex,
            BigDecimal adjustedFcf,
            BigDecimal roe,
            BigDecimal roic,
            BigDecimal grossMargin,
            BigDecimal revenue,
            BigDecimal grossProfit,
            BigDecimal operatingIncome,
            BigDecimal interestExpense,
            BigDecimal netIncome,
            BigDecimal stockholdersEquity,
            BigDecimal totalDebt,
            BigDecimal cashAndEquivalents,
            BigDecimal taxProvision,
            BigDecimal pretaxIncome,
            BigDecimal investedCapital,
            boolean forecast
    ) {
        this(asOfDate, basicEps, ttmEps, forwardEps, cashFlow, fcf, capex, adjustedFcf,
                roe, roic, grossMargin, revenue, grossProfit, operatingIncome, interestExpense,
                netIncome, stockholdersEquity, totalDebt, cashAndEquivalents, null, null, taxProvision,
                pretaxIncome, investedCapital, forecast, null, null, null, null, null, null, null,
                null, null, null, Map.of());
    }
}
