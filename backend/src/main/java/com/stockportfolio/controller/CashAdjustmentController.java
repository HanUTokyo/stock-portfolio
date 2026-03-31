package com.stockportfolio.controller;

import com.stockportfolio.dto.CashAdjustmentRequest;
import com.stockportfolio.dto.CashAdjustmentResponse;
import com.stockportfolio.service.PortfolioService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/cash-adjustments")
public class CashAdjustmentController {

    private final PortfolioService portfolioService;

    public CashAdjustmentController(PortfolioService portfolioService) {
        this.portfolioService = portfolioService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CashAdjustmentResponse create(@Valid @RequestBody CashAdjustmentRequest request) {
        return portfolioService.recordCashAdjustment(request);
    }

    @GetMapping
    public List<CashAdjustmentResponse> list() {
        return portfolioService.listCashAdjustments();
    }
}
