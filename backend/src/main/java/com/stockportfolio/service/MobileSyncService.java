package com.stockportfolio.service;

import com.stockportfolio.dto.SyncMutationBatchRequest;
import com.stockportfolio.dto.SyncMutationBatchResponse;
import com.stockportfolio.dto.SyncMutationResult;
import org.springframework.stereotype.Service;

@Service
public class MobileSyncService {
    private final MobileSyncMutationProcessor processor;

    public MobileSyncService(MobileSyncMutationProcessor processor) {
        this.processor = processor;
    }

    public SyncMutationBatchResponse process(SyncMutationBatchRequest request) {
        return new SyncMutationBatchResponse(request.mutations().stream().map(processor::process).toList());
    }
}
