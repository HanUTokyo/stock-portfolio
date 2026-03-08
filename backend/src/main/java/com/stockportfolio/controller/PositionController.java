package com.stockportfolio.controller;

import com.stockportfolio.dto.PositionRequest;
import com.stockportfolio.dto.PositionResponse;
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
}
