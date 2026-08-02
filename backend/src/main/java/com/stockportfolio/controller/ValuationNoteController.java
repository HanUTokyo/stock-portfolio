package com.stockportfolio.controller;

import com.stockportfolio.dto.FundamentalNoteRequest;
import com.stockportfolio.dto.FundamentalNoteResponse;
import com.stockportfolio.service.ValuationNoteService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/valuation-notes")
public class ValuationNoteController {

    private final ValuationNoteService valuationNoteService;

    public ValuationNoteController(ValuationNoteService valuationNoteService) {
        this.valuationNoteService = valuationNoteService;
    }

    @GetMapping
    public List<FundamentalNoteResponse> list() {
        return valuationNoteService.list();
    }

    @PutMapping("/{symbol}")
    public FundamentalNoteResponse upsert(@PathVariable String symbol, @Valid @RequestBody FundamentalNoteRequest request) {
        return valuationNoteService.upsert(symbol, request);
    }
}
