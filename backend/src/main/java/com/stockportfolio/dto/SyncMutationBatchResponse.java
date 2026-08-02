package com.stockportfolio.dto;

import java.util.List;

public record SyncMutationBatchResponse(List<SyncMutationResult> results) {
}
