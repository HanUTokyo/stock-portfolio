package com.stockportfolio.controller;

import com.stockportfolio.dto.PositionRequest;
import com.stockportfolio.dto.PositionMetadataRequest;
import com.stockportfolio.dto.PositionResponse;
import com.stockportfolio.dto.SharesOutstandingOverrideRequest;
import com.stockportfolio.service.PortfolioService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/positions")
public class PositionController {

    private final PortfolioService portfolioService;

    public PositionController(PortfolioService portfolioService) {
        this.portfolioService = portfolioService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PositionResponse addOrUpdate(@Valid @RequestBody PositionRequest request) {
        return portfolioService.addOrUpdatePosition(request);
    }

    @GetMapping
    public List<PositionResponse> list() {
        return portfolioService.listPositions();
    }

    @GetMapping("/{symbol}")
    public PositionResponse get(@PathVariable String symbol) {
        return portfolioService.getPosition(symbol);
    }

    @PutMapping("/{symbol}/shares-outstanding-override")
    public PositionResponse updateSharesOutstandingOverride(
            @PathVariable String symbol,
            @RequestBody SharesOutstandingOverrideRequest request
    ) {
        return portfolioService.updateSharesOutstandingOverride(symbol, request);
    }

    @PutMapping("/{symbol}/metadata")
    public PositionResponse updateMetadata(
            @PathVariable String symbol,
            @RequestBody PositionMetadataRequest request
    ) {
        return portfolioService.updatePositionMetadata(symbol, request);
    }
}
