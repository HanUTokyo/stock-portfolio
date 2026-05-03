package com.stockportfolio.controller;

import com.stockportfolio.dto.OverviewNoteRequest;
import com.stockportfolio.dto.OverviewNoteResponse;
import com.stockportfolio.model.OverviewNoteType;
import com.stockportfolio.service.PortfolioService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/overview-notes")
public class OverviewNoteController {

    private final PortfolioService portfolioService;

    public OverviewNoteController(PortfolioService portfolioService) {
        this.portfolioService = portfolioService;
    }

    @GetMapping
    public List<OverviewNoteResponse> list() {
        return portfolioService.listOverviewNotes();
    }

    @PutMapping("/{noteType}")
    public OverviewNoteResponse upsert(@PathVariable OverviewNoteType noteType,
                                       @Valid @RequestBody OverviewNoteRequest request) {
        return portfolioService.upsertOverviewNote(noteType, request);
    }
}
