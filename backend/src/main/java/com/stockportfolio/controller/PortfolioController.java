package com.stockportfolio.controller;

import com.stockportfolio.dto.AssetCurvePointResponse;
import com.stockportfolio.dto.HoldingResponse;
import com.stockportfolio.dto.PortfolioSummaryResponse;
import com.stockportfolio.service.PortfolioService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/portfolio")
public class PortfolioController {

    private final PortfolioService portfolioService;

    public PortfolioController(PortfolioService portfolioService) {
        this.portfolioService = portfolioService;
    }

    @GetMapping("/holdings")
    public List<HoldingResponse> holdings() {
        return portfolioService.getHoldings();
    }

    @GetMapping("/summary")
    public PortfolioSummaryResponse summary() {
        return portfolioService.getSummary();
    }

    @GetMapping("/asset-curve")
    public List<AssetCurvePointResponse> assetCurve() {
        return portfolioService.getAssetCurve();
    }
}
