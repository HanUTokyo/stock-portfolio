package com.stockportfolio.controller;

import com.stockportfolio.dto.DividendRequest;
import com.stockportfolio.dto.DividendResponse;
import com.stockportfolio.dto.DividendCsvImportResponse;
import com.stockportfolio.dto.MonthlyDividendResponse;
import com.stockportfolio.service.PortfolioService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/dividends")
public class DividendController {
    private final PortfolioService portfolioService;

    public DividendController(PortfolioService portfolioService) {
        this.portfolioService = portfolioService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DividendResponse create(@Valid @RequestBody DividendRequest request) {
        return portfolioService.recordDividend(request);
    }

    @GetMapping
    public List<DividendResponse> list() {
        return portfolioService.listDividends();
    }

    @GetMapping("/monthly")
    public List<MonthlyDividendResponse> monthly() {
        return portfolioService.getMonthlyDividends();
    }

    @PostMapping(value = "/import-csv", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DividendCsvImportResponse importCsv(@RequestParam("file") MultipartFile file) {
        return portfolioService.importDividendsFromCsv(file);
    }
}
