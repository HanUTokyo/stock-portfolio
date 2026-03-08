package com.stockportfolio.controller;

import com.stockportfolio.dto.TransactionRequest;
import com.stockportfolio.dto.TransactionResponse;
import com.stockportfolio.service.PortfolioService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final PortfolioService portfolioService;

    public TransactionController(PortfolioService portfolioService) {
        this.portfolioService = portfolioService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionResponse create(@Valid @RequestBody TransactionRequest request) {
        return portfolioService.recordTransaction(request);
    }

    @GetMapping
    public List<TransactionResponse> list() {
        return portfolioService.listTransactions();
    }
}
