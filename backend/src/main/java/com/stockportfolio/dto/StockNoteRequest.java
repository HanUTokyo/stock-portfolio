package com.stockportfolio.dto;

import jakarta.validation.constraints.NotNull;

public record StockNoteRequest(@NotNull String note) {
}
