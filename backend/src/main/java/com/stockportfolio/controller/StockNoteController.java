package com.stockportfolio.controller;

import com.stockportfolio.dto.StockNoteRequest;
import com.stockportfolio.dto.StockNoteResponse;
import com.stockportfolio.service.PortfolioService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/stock-notes")
public class StockNoteController {

    private final PortfolioService portfolioService;

    public StockNoteController(PortfolioService portfolioService) {
        this.portfolioService = portfolioService;
    }

    @GetMapping
    public List<StockNoteResponse> list() {
        return portfolioService.listStockNotes();
    }

    @PutMapping("/{symbol}")
    public StockNoteResponse upsert(@PathVariable String symbol, @Valid @RequestBody StockNoteRequest request) {
        return portfolioService.upsertStockNote(symbol, request);
    }
}
