package com.stockportfolio.dto;
import java.math.BigDecimal;
import java.time.*;
public record ExternalWaccReferenceResponse(String provider, BigDecimal ratePct, BigDecimal differenceFromSystemPct,
                                            String sourceUrl, LocalDate providerAsOf, OffsetDateTime retrievedAt,
                                            String status, String errorMessage, boolean selectable) { }
