package com.stockportfolio.controller;

import com.stockportfolio.dto.ExplicitOperatingForecastApiResponse;
import com.stockportfolio.dto.ForecastPreviewRequest;
import com.stockportfolio.dto.ForecastPreviewResponse;
import com.stockportfolio.dto.ForecastTemplateResponse;
import com.stockportfolio.service.ForecastArchitectureService;
import com.stockportfolio.valuation.explicit.ExplicitOperatingForecastRequest;
import com.stockportfolio.valuation.explicit.ExplicitOperatingForecastService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Locale;

@RestController
@RequestMapping("/api/valuations")
public class ExplicitOperatingForecastController {
    private final ExplicitOperatingForecastService forecastService;
    private final ForecastArchitectureService forecastArchitectureService;

    public ExplicitOperatingForecastController(ExplicitOperatingForecastService forecastService,
                                               ForecastArchitectureService forecastArchitectureService) {
        this.forecastService = forecastService;
        this.forecastArchitectureService = forecastArchitectureService;
    }

    @PostMapping("/{symbol}/explicit-forecast")
    public ExplicitOperatingForecastApiResponse forecast(
            @PathVariable String symbol,
            @RequestBody ExplicitOperatingForecastRequest request
    ) {
        if (symbol == null || symbol.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "symbol is required");
        }
        try {
            return new ExplicitOperatingForecastApiResponse(
                    symbol.trim().toUpperCase(Locale.ROOT),
                    "valuation-java-3.0.0-explicit",
                    "DUAL_TRACK",
                    forecastService.forecast(request)
            );
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage(), exception);
        }
    }

    @GetMapping("/{symbol}/forecast-template")
    public ForecastTemplateResponse template(@PathVariable String symbol) {
        return forecastArchitectureService.template(symbol);
    }

    @PostMapping("/{symbol}/forecast-preview")
    public ForecastPreviewResponse preview(@PathVariable String symbol,
                                           @RequestBody ForecastPreviewRequest request) {
        return forecastArchitectureService.preview(symbol, request);
    }

    @PutMapping("/{symbol}/forecast-scenarios")
    public ForecastPreviewResponse saveForecastSnapshot(@PathVariable String symbol,
                                                        @RequestBody ForecastPreviewRequest request) {
        return forecastArchitectureService.saveSnapshot(symbol, request);
    }

    @DeleteMapping("/{symbol}/forecast-scenarios")
    public void resetForecastSnapshot(@PathVariable String symbol) {
        forecastArchitectureService.resetSnapshot(symbol);
    }
}
