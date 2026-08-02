package com.stockportfolio.controller;

import com.stockportfolio.dto.MarketAssumptionsResponse;
import com.stockportfolio.service.MarketAssumptionsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/portfolio")
public class MarketAssumptionsController {
    private final MarketAssumptionsService marketAssumptionsService;

    public MarketAssumptionsController(MarketAssumptionsService marketAssumptionsService) {
        this.marketAssumptionsService = marketAssumptionsService;
    }

    @GetMapping("/market-assumptions")
    public MarketAssumptionsResponse marketAssumptions(@RequestParam String symbol) {
        return marketAssumptionsService.getMarketAssumptions(symbol);
    }
}
