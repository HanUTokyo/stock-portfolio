package com.stockportfolio.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockportfolio.dto.ValuationAssumptions;
import com.stockportfolio.dto.ValuationScenarioResponse;
import com.stockportfolio.service.ValuationService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ValuationControllerTest {
    @Test
    void savesValidatedAutoScenario() throws Exception {
        ValuationService service = mock(ValuationService.class);
        ValuationAssumptions assumptions = new ValuationAssumptions(new BigDecimal("100"), new BigDecimal("8"),
                new BigDecimal("10"), new BigDecimal("2.5"), 10, new BigDecimal("20"), null);
        when(service.save(eq("AAPL"), eq("BASE"), any())).thenReturn(new ValuationScenarioResponse(
                "BASE", "AUTO", "FCFF", "SAVED", assumptions, true, new BigDecimal("150"),
                new BigDecimal("120"), null, null, null, List.of(), List.of(), null));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ValuationController(service)).build();
        String body = new ObjectMapper().writeValueAsString(java.util.Map.of("modelMode", "AUTO", "assumptions", assumptions));

        mvc.perform(put("/api/valuations/AAPL/scenarios/BASE").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selectedModel").value("FCFF"))
                .andExpect(jsonPath("$.intrinsicValuePerShare").value(150));
    }
}
