package com.stockportfolio.controller;

import com.stockportfolio.dto.DataReviewPatchRequest;
import com.stockportfolio.dto.DataReviewBatchPreviewResponse;
import com.stockportfolio.dto.DataReviewPageResponse;
import com.stockportfolio.dto.DataReviewRowResponse;
import com.stockportfolio.dto.DataReviewSourceResponse;
import com.stockportfolio.service.DataReviewService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DataReviewControllerTest {

    @Test
    void returnsReviewSources() throws Exception {
        DataReviewService service = mock(DataReviewService.class);
        when(service.getSources()).thenReturn(List.of(new DataReviewSourceResponse(
                "company_profiles",
                "Company Profiles",
                "positions",
                List.of("id", "symbol", "sector"),
                List.of("sector")
        )));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new DataReviewController(service)).build();

        mockMvc.perform(get("/api/admin/data-review/sources"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("company_profiles"))
                .andExpect(jsonPath("$[0].rawTable").value("positions"))
                .andExpect(jsonPath("$[0].editableFields[0]").value("sector"));
    }

    @Test
    void patchesReviewRow() throws Exception {
        DataReviewService service = mock(DataReviewService.class);
        when(service.patchRow(eq("company_profiles"), eq("7"), any(DataReviewPatchRequest.class)))
                .thenReturn(new DataReviewRowResponse(
                        "company_profiles",
                        "7",
                        Map.of("id", 7, "symbol", "AAPL", "sector", "Tech"),
                        Map.of("sector", "Technology"),
                        Map.of("id", 7, "symbol", "AAPL", "sector", "Technology"),
                        "corrected",
                        "Corrected sector manually.",
                        "manual_admin",
                        OffsetDateTime.parse("2026-06-04T12:00:00Z"),
                        List.of(),
                        List.of(),
                        "0"
                ));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new DataReviewController(service)).build();

        mockMvc.perform(patch("/api/admin/data-review/company_profiles/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "changes": { "sector": "Technology" },
                                  "reviewStatus": "corrected",
                                  "note": "Corrected sector manually."
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordId").value("7"))
                .andExpect(jsonPath("$.reviewStatus").value("corrected"))
                .andExpect(jsonPath("$.reviewedValues.sector").value("Technology"))
                .andExpect(jsonPath("$.reviewer").value("manual_admin"));
    }

    @Test
    void forwardsAttentionQueueAndSeverityFilters() throws Exception {
        DataReviewService service = mock(DataReviewService.class);
        when(service.getRows(eq("market_data"), eq(0), eq(25), eq(null), eq("pending"), eq("priority"), eq("asc"), eq(false), eq("attention"), eq("urgent")))
                .thenReturn(new DataReviewPageResponse("market_data", 0, 25, 0, 0, List.of()));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new DataReviewController(service)).build();

        mockMvc.perform(get("/api/admin/data-review/market_data")
                        .param("status", "pending")
                        .param("sortBy", "priority")
                        .param("queue", "attention")
                        .param("severity", "urgent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows").isEmpty());

        verify(service).getRows("market_data", 0, 25, null, "pending", "priority", "asc", false, "attention", "urgent");
    }

    @Test
    void returnsReadOnlyBatchPreviewWithExpectedRevisions() throws Exception {
        DataReviewService service = mock(DataReviewService.class);
        when(service.previewBatchStatus(eq("company_profiles"), any()))
                .thenReturn(new DataReviewBatchPreviewResponse(
                        "company_profiles",
                        "uncertain",
                        "missing_or_stale",
                        1,
                        Map.of("urgent", 1L),
                        List.of()
                ));
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new DataReviewController(service)).build();

        mockMvc.perform(post("/api/admin/data-review/company_profiles/batch-preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "recordIds": ["7"],
                                  "reviewStatus": "uncertain",
                                  "reasonCode": "missing_or_stale",
                                  "expectedRevisions": { "7": "3" }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.affectedCount").value(1))
                .andExpect(jsonPath("$.riskCounts.urgent").value(1))
                .andExpect(jsonPath("$.reasonCode").value("missing_or_stale"));
    }
}
