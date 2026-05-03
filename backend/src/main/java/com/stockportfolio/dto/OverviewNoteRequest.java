package com.stockportfolio.dto;

import jakarta.validation.constraints.NotNull;

public record OverviewNoteRequest(@NotNull String note) {
}
