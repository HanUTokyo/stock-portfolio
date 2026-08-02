package com.stockportfolio.controller;

import com.stockportfolio.dto.MarketAssumptionsResponse;
import com.stockportfolio.service.MarketAssumptionsService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MarketAssumptionsControllerTest {

    @Test
    void returnsMarketAssumptionsEndpointPayload() throws Exception {
        MarketAssumptionsService service = mock(MarketAssumptionsService.class);
        when(service.getMarketAssumptions("AAPL")).thenReturn(new MarketAssumptionsResponse(
                "AAPL",
                new BigDecimal("4.50"),
                "10Y",
                LocalDate.of(2026, 5, 22),
                "U.S. Treasury daily par yield curve",
                new BigDecimal("1.25"),
                "Yahoo quoteSummary best effort",
                List.of()
        ));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new MarketAssumptionsController(service)).build();

        mockMvc.perform(get("/api/portfolio/market-assumptions").param("symbol", "AAPL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("AAPL"))
                .andExpect(jsonPath("$.riskFreeRate").value(4.50))
                .andExpect(jsonPath("$.riskFreeMaturity").value("10Y"))
                .andExpect(jsonPath("$.riskFreeDate[0]").value(2026))
                .andExpect(jsonPath("$.riskFreeDate[1]").value(5))
                .andExpect(jsonPath("$.riskFreeDate[2]").value(22))
                .andExpect(jsonPath("$.beta").value(1.25));
    }

    @Test
    void returnsBestEffortUnavailablePayload() throws Exception {
        MarketAssumptionsService service = mock(MarketAssumptionsService.class);
        when(service.getMarketAssumptions("AAPL")).thenReturn(new MarketAssumptionsResponse(
                "AAPL",
                null,
                "10Y",
                null,
                "U.S. Treasury daily par yield curve",
                null,
                "Yahoo quoteSummary best effort",
                List.of("Risk-free rate unavailable from U.S. Treasury 10Y par yield.", "Beta unavailable from Yahoo quoteSummary; use manual fallback.")
        ));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new MarketAssumptionsController(service)).build();

        mockMvc.perform(get("/api/portfolio/market-assumptions").param("symbol", "AAPL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.riskFreeRate").doesNotExist())
                .andExpect(jsonPath("$.beta").doesNotExist())
                .andExpect(jsonPath("$.warnings[0]").value(containsString("Risk-free rate unavailable")))
                .andExpect(jsonPath("$.warnings[1]").value(containsString("Beta unavailable")));
    }
}
