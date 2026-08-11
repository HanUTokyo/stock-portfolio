package com.stockportfolio.dto;
import java.math.BigDecimal;
import java.util.List;
public record WaccReferencesResponse(String symbol, BigDecimal systemWaccPct,
                                     List<ExternalWaccReferenceResponse> references) { }
