package com.stockportfolio.controller;

import com.stockportfolio.dto.SyncMutationBatchRequest;
import com.stockportfolio.dto.SyncMutationBatchResponse;
import com.stockportfolio.service.MobileSyncService;
import com.stockportfolio.service.MobileSyncImportService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/sync")
public class SyncController {
    private final MobileSyncService syncService;
    private final MobileSyncImportService importService;

    public SyncController(MobileSyncService syncService, MobileSyncImportService importService) {
        this.syncService = syncService;
        this.importService = importService;
    }

    @PostMapping("/mutations")
    public SyncMutationBatchResponse mutations(@Valid @RequestBody SyncMutationBatchRequest request) {
        return syncService.process(request);
    }

    @PostMapping(value = "/imports/{type}", consumes = "multipart/form-data")
    public com.stockportfolio.dto.SyncMutationResult importFile(
            @PathVariable String type,
            @RequestParam String mutationId,
            @RequestParam String deviceId,
            @RequestParam("file") MultipartFile file
    ) {
        return importService.process(type, mutationId, deviceId, file);
    }
}
