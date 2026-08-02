package com.stockportfolio.controller;

import com.stockportfolio.dto.CashAdjustmentRequest;
import com.stockportfolio.dto.CashAdjustmentResponse;
import com.stockportfolio.dto.CashAdjustmentCsvImportResponse;
import com.stockportfolio.service.PortfolioService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

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

    @PutMapping("/{adjustmentId}")
    public CashAdjustmentResponse update(@PathVariable Long adjustmentId, @Valid @RequestBody CashAdjustmentRequest request) {
        return portfolioService.updateCashAdjustment(adjustmentId, request);
    }

    @DeleteMapping("/{adjustmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long adjustmentId) {
        portfolioService.deleteCashAdjustment(adjustmentId);
    }

    @GetMapping
    public List<CashAdjustmentResponse> list() {
        return portfolioService.listCashAdjustments();
    }

    @PostMapping(value = "/import-csv", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CashAdjustmentCsvImportResponse importCsv(@RequestParam("file") MultipartFile file) {
        return portfolioService.importCashAdjustmentsFromCsv(file);
    }
}
