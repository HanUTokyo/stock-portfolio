package com.stockportfolio.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record GrowthReferenceResponse(
        String type,
        BigDecimal valuePct,
        String status,
        String confidence,
        String sourceCode,
        String sourceName,
        OffsetDateTime sourceDate,
        Integer sampleCount,
        List<Component> components
) {
    public record Component(String metric, BigDecimal valuePct, Integer sampleCount, String period) { }
}
