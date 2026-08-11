package com.stockportfolio.dto;
import java.math.BigDecimal;
import java.time.*;
public record WaccReferenceSelection(String provider, BigDecimal ratePct, String sourceUrl,
                                     LocalDate providerAsOf, OffsetDateTime retrievedAt) { }
