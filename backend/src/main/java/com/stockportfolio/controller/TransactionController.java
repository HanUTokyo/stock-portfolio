package com.stockportfolio.controller;

import com.stockportfolio.dto.TransactionCsvImportResponse;
import com.stockportfolio.dto.TransactionRequest;
import com.stockportfolio.dto.TransactionResponse;
import com.stockportfolio.service.PortfolioService;
import jakarta.validation.Valid;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
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

    @DeleteMapping("/{transactionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long transactionId) {
        portfolioService.deleteTransaction(transactionId);
    }

    @PutMapping("/{transactionId}")
    public TransactionResponse update(@PathVariable Long transactionId, @Valid @RequestBody TransactionRequest request) {
        return portfolioService.updateTransaction(transactionId, request);
    }

    @PostMapping(value = "/import-csv", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public TransactionCsvImportResponse importCsv(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "dryRun", defaultValue = "false") boolean dryRun
    ) {
        return portfolioService.importTransactionsFromCsv(file, dryRun);
    }

    @PostMapping(value = "/import-csv/errors", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> exportCsvImportErrors(@RequestParam("file") MultipartFile file) {
        byte[] csv = portfolioService.exportFailedRowsCsv(file);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .header("Content-Disposition", ContentDisposition.attachment().filename("failed-rows.csv").build().toString())
                .body(csv);
    }
}
