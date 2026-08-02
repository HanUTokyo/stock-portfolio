package com.stockportfolio.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record SyncMutationBatchRequest(@NotEmpty List<@Valid SyncMutationRequest> mutations) {
}
