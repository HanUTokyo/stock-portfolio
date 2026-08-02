package com.stockportfolio.controller;

import com.stockportfolio.dto.*;
import com.stockportfolio.service.ValuationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/valuations")
public class ValuationController {
    private final ValuationService service;
    public ValuationController(ValuationService service) { this.service = service; }

    @GetMapping("/{symbol}")
    public ValuationResponse get(@PathVariable String symbol) { return service.get(symbol); }

    @PostMapping("/{symbol}/evaluate")
    public ValuationEvaluationResponse evaluate(@PathVariable String symbol,
                                                @Valid @RequestBody ValuationEvaluateRequest request) {
        return service.evaluate(symbol, request);
    }

    @PutMapping("/{symbol}/scenarios/{scenarioType}")
    public ValuationScenarioResponse save(@PathVariable String symbol,
                                          @PathVariable String scenarioType,
                                          @Valid @RequestBody ValuationSaveRequest request) {
        return service.save(symbol, scenarioType, request);
    }

    @DeleteMapping("/{symbol}/scenarios/{scenarioType}")
    public ValuationScenarioResponse reset(@PathVariable String symbol, @PathVariable String scenarioType) {
        return service.reset(symbol, scenarioType);
    }
}
