package com.stockportfolio.service;

import com.stockportfolio.dto.MarketAssumptionsResponse;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Service
public class MarketAssumptionsService {
    private final TreasuryYieldService treasuryYieldService;
    private final YahooFinancePriceService yahooFinancePriceService;

    public MarketAssumptionsService(TreasuryYieldService treasuryYieldService,
                                    YahooFinancePriceService yahooFinancePriceService) {
        this.treasuryYieldService = treasuryYieldService;
        this.yahooFinancePriceService = yahooFinancePriceService;
    }

    public MarketAssumptionsResponse getMarketAssumptions(String symbol) {
        String normalizedSymbol = symbol == null ? "" : symbol.trim().toUpperCase();
        List<String> warnings = new ArrayList<>();

        BigDecimal riskFreeRate = null;
        String riskFreeMaturity = "10Y";
        java.time.LocalDate riskFreeDate = null;
        String riskFreeSource = "U.S. Treasury daily par yield curve";
        try {
            var riskFree = treasuryYieldService.fetchTenYearParYield();
            if (riskFree.isPresent()) {
                riskFreeRate = riskFree.get().rate();
                riskFreeMaturity = riskFree.get().maturity();
                riskFreeDate = riskFree.get().date();
                riskFreeSource = riskFree.get().source();
            } else {
                warnings.add("Risk-free rate unavailable from U.S. Treasury 10Y par yield.");
            }
        } catch (Exception e) {
            warnings.add("Risk-free rate unavailable from U.S. Treasury 10Y par yield.");
        }

        BigDecimal beta = null;
        String betaSource = "Yahoo quoteSummary best effort";
        if (!normalizedSymbol.isBlank()) {
            try {
                beta = yahooFinancePriceService.fetchBeta(normalizedSymbol).orElse(null);
                if (beta == null) {
                    warnings.add("Beta unavailable from Yahoo quoteSummary; use manual fallback.");
                }
            } catch (Exception e) {
                warnings.add("Beta unavailable from Yahoo quoteSummary; use manual fallback.");
            }
        } else {
            warnings.add("Beta unavailable because symbol is missing.");
        }

        return new MarketAssumptionsResponse(
                normalizedSymbol,
                riskFreeRate,
                riskFreeMaturity,
                riskFreeDate,
                riskFreeSource,
                beta,
                betaSource,
                warnings
        );
    }
}
