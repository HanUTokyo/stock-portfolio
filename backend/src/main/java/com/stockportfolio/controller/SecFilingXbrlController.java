package com.stockportfolio.controller;

import com.stockportfolio.service.SecCompanyFactsService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Map;

/** Controlled, idempotent rebuild for SEC filing evidence only. */
@RestController
@RequestMapping("/api/portfolio/history/fundamentals")
public class SecFilingXbrlController {
    private final SecCompanyFactsService companyFacts;
    public SecFilingXbrlController(SecCompanyFactsService companyFacts) { this.companyFacts = companyFacts; }

    @PostMapping("/rebuild-sec-filing-graph")
    public Map<String, Object> rebuild(@RequestParam String symbol,
                                       @RequestParam(defaultValue = "2") int years) {
        if (years < 1 || years > 15) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "years must be between 1 and 15");
        LocalDate to = LocalDate.now(); LocalDate from = to.minusYears(years);
        try {
            companyFacts.rebuildFilingCashFlowGraph(symbol.trim().toUpperCase(), from, to);
            return Map.of("symbol", symbol.trim().toUpperCase(), "from", from, "to", to, "status", "COMPLETED");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "SEC filing graph rebuild failed", e);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "SEC filing graph rebuild failed", e);
        }
    }

    @PostMapping("/rebuild-sec-share-count-bridge")
    public Map<String, Object> rebuildShareCountBridge(@RequestParam String symbol,
                                                        @RequestParam(defaultValue = "2") int years) {
        if (years < 1 || years > 15) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "years must be between 1 and 15");
        LocalDate to = LocalDate.now(); LocalDate from = to.minusYears(years);
        try {
            companyFacts.rebuildShareCountBridge(symbol.trim().toUpperCase(), from, to);
            return Map.of("symbol", symbol.trim().toUpperCase(), "from", from, "to", to, "status", "COMPLETED");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "SEC share-count bridge rebuild failed", e);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "SEC share-count bridge rebuild failed", e);
        }
    }
}
