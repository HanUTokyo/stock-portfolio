package com.stockportfolio.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;

public record FieldSourceResponse(
        String sourceCode,
        String sourceName,
        LocalDate sourceDate,
        OffsetDateTime reviewedAt
) {
}
