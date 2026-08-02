package com.stockportfolio.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record PortfolioExportV2Response(
        OffsetDateTime generatedAt,
        String baseCurrency,
        PortfolioExportSummaryResponse portfolioSummary,
        Exposures exposures,
        AiSuggestionContext aiSuggestionContext,
        List<Holding> holdings
) {
    public record AiSuggestionContext(
            String instruction,
            ContextNote monthlyIdeas,
            ContextNote previousAiSuggestions
    ) {
    }

    public record ContextNote(
            String noteType,
            String role,
            String label,
            String interpretation,
            String note,
            OffsetDateTime updatedAt
    ) {
    }

    public record Exposures(
            Map<String, BigDecimal> byAssetClass,
            Map<String, BigDecimal> bySector,
            Map<String, BigDecimal> byRegion
    ) {
    }

    public record Holding(
            String symbol,
            Position position,
            Market market,
            Computed computed,
            Classification classification,
            Valuation valuation,
            Fundamentals fundamentals,
            Performance performance,
            OptionDetails option,
            DataQuality dataQuality,
            String stockNote
    ) {
    }

    public record Position(
            BigDecimal quantity,
            BigDecimal averageCost,
            BigDecimal costBasis
    ) {
    }

    public record Market(
            BigDecimal latestPrice,
            OffsetDateTime priceAsOf,
            String currency
    ) {
    }

    public record Computed(
            BigDecimal marketValue,
            BigDecimal weightPct,
            BigDecimal unrealizedPnl,
            BigDecimal unrealizedPnlPct
    ) {
    }

    public record Classification(
            String assetClass,
            String instrumentType,
            String underlying,
            String sector,
            String region
    ) {
    }

    public record Valuation(
            BigDecimal peTtm,
            BigDecimal peForward,
            BigDecimal earningsYieldPct,
            LocalDate valuationAsOf
    ) {
    }

    public record Fundamentals(
            BigDecimal revenueGrowthYoYPct,
            BigDecimal grossMarginPct,
            BigDecimal roePct,
            BigDecimal roicPct,
            BigDecimal debtToEquity,
            BigDecimal freeCashFlow,
            LocalDate fundamentalsAsOf
    ) {
    }

    public record Performance(
            BigDecimal unrealizedPnl,
            BigDecimal unrealizedPnlPct,
            BigDecimal realizedPnl,
            BigDecimal dividendIncome,
            BigDecimal optionPremiumIncome,
            BigDecimal totalReturn,
            BigDecimal totalReturnPct
    ) {
    }

    public record OptionDetails(
            String type,
            BigDecimal strike,
            LocalDate expiration,
            BigDecimal contractSize,
            BigDecimal contracts,
            Long daysToExpiration,
            BigDecimal moneyness,
            BigDecimal intrinsicValue,
            BigDecimal timeValue,
            BigDecimal notionalExposure,
            BigDecimal maxLoss
    ) {
    }

    public record DataQuality(
            String priceSource,
            String fundamentalSource,
            String fxSource,
            OffsetDateTime lastPriceUpdatedAt,
            OffsetDateTime lastFundamentalUpdatedAt,
            boolean hasStaleData,
            List<String> missingFields
    ) {
    }
}
