package com.stockportfolio.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockportfolio.repository.TransactionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SyncControllerIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired TransactionRepository transactionRepository;

    @Test
    void repeatedCreateMutationIsIdempotent() throws Exception {
        long before = transactionRepository.count();
        String mutationId = UUID.randomUUID().toString();
        String symbol = "ID" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String body = createMutation(mutationId, symbol, "1.25000000");

        mockMvc.perform(post("/api/sync/mutations").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("APPLIED"))
                .andExpect(jsonPath("$.results[0].serverVersion").isNumber());
        long afterFirst = transactionRepository.count();

        mockMvc.perform(post("/api/sync/mutations").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("APPLIED"));

        assertThat(afterFirst).isEqualTo(before + 1);
        assertThat(transactionRepository.count()).isEqualTo(afterFirst);
    }

    @Test
    void staleBaseVersionReturnsConflictWithServerValue() throws Exception {
        String symbol = "CV" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        JsonNode created = objectMapper.readTree(mockMvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transactionPayload(symbol, "1.00000000")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        long id = created.get("id").asLong();
        long staleVersion = created.get("version").asLong();

        mockMvc.perform(put("/api/transactions/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transactionPayload(symbol, "2.00000000")))
                .andExpect(status().isOk());

        String mutation = """
                {"mutations":[{
                  "mutationId":"%s","deviceId":"%s","entityType":"TRANSACTION","action":"UPDATE",
                  "entityId":"%d","baseVersion":%d,"payload":%s,"clientOccurredAt":"2026-07-13T12:00:00Z"
                }]}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), id, staleVersion, transactionPayload(symbol, "3.00000000"));

        mockMvc.perform(post("/api/sync/mutations").contentType(MediaType.APPLICATION_JSON).content(mutation))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("CONFLICT"))
                .andExpect(jsonPath("$.results[0].entityId").value(String.valueOf(id)))
                .andExpect(jsonPath("$.results[0].serverValue.quantity").value(2.0));
    }

    @Test
    void mutationIdCannotBeReusedForDifferentContent() throws Exception {
        String mutationId = UUID.randomUUID().toString();
        String symbol = "RH" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        mockMvc.perform(post("/api/sync/mutations").contentType(MediaType.APPLICATION_JSON)
                        .content(createMutation(mutationId, symbol, "1.00000000")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/sync/mutations").contentType(MediaType.APPLICATION_JSON)
                        .content(createMutation(mutationId, symbol, "2.00000000")))
                .andExpect(status().isConflict());
    }

    private String createMutation(String mutationId, String symbol, String quantity) {
        return """
                {"mutations":[{
                  "mutationId":"%s","deviceId":"%s","entityType":"TRANSACTION","action":"CREATE",
                  "payload":%s,"clientOccurredAt":"2026-07-13T12:00:00Z"
                }]}
                """.formatted(mutationId, UUID.randomUUID(), transactionPayload(symbol, quantity));
    }

    private String transactionPayload(String symbol, String quantity) {
        return """
                {"symbol":"%s","type":"BUY","quantity":%s,"price":12.3400,
                 "note":"sync test","executedAt":"2026-07-13T00:00:00Z"}
                """.formatted(symbol, quantity);
    }
}
