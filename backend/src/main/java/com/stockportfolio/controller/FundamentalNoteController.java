package com.stockportfolio.controller;

import com.stockportfolio.dto.FundamentalNoteRequest;
import com.stockportfolio.dto.FundamentalNoteResponse;
import com.stockportfolio.service.PortfolioService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/fundamental-notes")
public class FundamentalNoteController {

    private final PortfolioService portfolioService;

    public FundamentalNoteController(PortfolioService portfolioService) {
        this.portfolioService = portfolioService;
    }

    @GetMapping
    public List<FundamentalNoteResponse> list() {
        return portfolioService.listFundamentalNotes();
    }

    @PutMapping("/{symbol}")
    public FundamentalNoteResponse upsert(@PathVariable String symbol, @Valid @RequestBody FundamentalNoteRequest request) {
        return portfolioService.upsertFundamentalNote(symbol, request);
    }
}
