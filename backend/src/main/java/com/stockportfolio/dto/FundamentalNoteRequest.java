package com.stockportfolio.dto;

import jakarta.validation.constraints.NotNull;

public record FundamentalNoteRequest(@NotNull String note) {
}
