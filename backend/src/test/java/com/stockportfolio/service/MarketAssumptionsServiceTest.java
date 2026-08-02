package com.stockportfolio.service;

import com.stockportfolio.dto.MarketAssumptionsResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MarketAssumptionsServiceTest {

    @Test
    void returnsRiskFreeMetadataAndBestEffortBeta() throws Exception {
        TreasuryYieldService treasury = mock(TreasuryYieldService.class);
        YahooFinancePriceService yahoo = mock(YahooFinancePriceService.class);
        when(treasury.fetchTenYearParYield()).thenReturn(Optional.of(new TreasuryYieldService.RiskFreeRate(
                new BigDecimal("4.50"),
                "10Y",
                LocalDate.of(2026, 5, 22),
                "U.S. Treasury daily par yield curve"
        )));
        when(yahoo.fetchBeta("AAPL")).thenReturn(Optional.of(new BigDecimal("1.25")));

        MarketAssumptionsResponse response = new MarketAssumptionsService(treasury, yahoo).getMarketAssumptions("aapl");

        assertThat(response.symbol()).isEqualTo("AAPL");
        assertThat(response.riskFreeRate()).isEqualByComparingTo("4.50");
        assertThat(response.riskFreeMaturity()).isEqualTo("10Y");
        assertThat(response.riskFreeDate()).isEqualTo(LocalDate.of(2026, 5, 22));
        assertThat(response.beta()).isEqualByComparingTo("1.25");
        assertThat(response.warnings()).isEmpty();
    }

    @Test
    void keepsResponseUsableWhenRiskFreeAndBetaFetchFail() throws Exception {
        TreasuryYieldService treasury = mock(TreasuryYieldService.class);
        YahooFinancePriceService yahoo = mock(YahooFinancePriceService.class);
        when(treasury.fetchTenYearParYield()).thenThrow(new java.io.IOException("down"));
        when(yahoo.fetchBeta("AAPL")).thenThrow(new java.io.IOException("down"));

        MarketAssumptionsResponse response = new MarketAssumptionsService(treasury, yahoo).getMarketAssumptions("AAPL");

        assertThat(response.riskFreeRate()).isNull();
        assertThat(response.beta()).isNull();
        assertThat(response.warnings()).hasSize(2);
        assertThat(response.warnings().get(0)).contains("Risk-free rate unavailable");
        assertThat(response.warnings().get(1)).contains("Beta unavailable");
    }
}
