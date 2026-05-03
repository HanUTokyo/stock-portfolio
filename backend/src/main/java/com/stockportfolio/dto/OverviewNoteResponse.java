package com.stockportfolio.dto;

import com.stockportfolio.model.OverviewNoteType;

import java.time.OffsetDateTime;

public record OverviewNoteResponse(
        OverviewNoteType noteType,
        String note,
        OffsetDateTime updatedAt
) {
}
