package com.stockportfolio.controller;

import com.stockportfolio.dto.DataReviewAuditLogResponse;
import com.stockportfolio.dto.DataReviewBatchStatusRequest;
import com.stockportfolio.dto.DataReviewBatchStatusResponse;
import com.stockportfolio.dto.DataReviewBatchPreviewResponse;
import com.stockportfolio.dto.DataReviewPageResponse;
import com.stockportfolio.dto.DataReviewPatchRequest;
import com.stockportfolio.dto.DataReviewRowResponse;
import com.stockportfolio.dto.DataReviewSourceResponse;
import com.stockportfolio.dto.DataReviewStatusRequest;
import com.stockportfolio.dto.DataReviewSummaryResponse;
import com.stockportfolio.service.DataReviewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/data-review")
public class DataReviewController {

    private final DataReviewService dataReviewService;

    public DataReviewController(DataReviewService dataReviewService) {
        this.dataReviewService = dataReviewService;
    }

    @GetMapping("/sources")
    public List<DataReviewSourceResponse> sources() {
        return dataReviewService.getSources();
    }

    @GetMapping("/summary")
    public DataReviewSummaryResponse summary() {
        return dataReviewService.getSummary();
    }

    @GetMapping("/{source}")
    public DataReviewPageResponse rows(
            @PathVariable String source,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "id") String sortBy,
            @RequestParam(defaultValue = "asc") String sortDirection,
            @RequestParam(defaultValue = "false") boolean anomalyOnly,
            @RequestParam(defaultValue = "all") String queue,
            @RequestParam(defaultValue = "all") String severity
    ) {
        return dataReviewService.getRows(source, page, size, search, status, sortBy, sortDirection, anomalyOnly, queue, severity);
    }

    @PatchMapping("/{source}/{id}")
    public DataReviewRowResponse patch(
            @PathVariable String source,
            @PathVariable String id,
            @RequestBody DataReviewPatchRequest request
    ) {
        return dataReviewService.patchRow(source, id, request);
    }

    @PostMapping("/{source}/{id}/approve")
    public DataReviewRowResponse approve(
            @PathVariable String source,
            @PathVariable String id,
            @RequestBody(required = false) DataReviewStatusRequest request
    ) {
        return dataReviewService.updateStatus(source, id, "approved", request == null ? null : request.note(), request == null ? null : request.reasonCode(), request == null ? null : request.expectedRevision());
    }

    @PostMapping("/{source}/{id}/reject")
    public DataReviewRowResponse reject(
            @PathVariable String source,
            @PathVariable String id,
            @RequestBody(required = false) DataReviewStatusRequest request
    ) {
        return dataReviewService.updateStatus(source, id, "rejected", request == null ? null : request.note(), request == null ? null : request.reasonCode(), request == null ? null : request.expectedRevision());
    }

    @PostMapping("/{source}/{id}/uncertain")
    public DataReviewRowResponse uncertain(
            @PathVariable String source,
            @PathVariable String id,
            @RequestBody(required = false) DataReviewStatusRequest request
    ) {
        return dataReviewService.updateStatus(source, id, "uncertain", request == null ? null : request.note(), request == null ? null : request.reasonCode(), request == null ? null : request.expectedRevision());
    }

    @PostMapping("/{source}/batch-status")
    public DataReviewBatchStatusResponse batchStatus(
            @PathVariable String source,
            @RequestBody DataReviewBatchStatusRequest request
    ) {
        return dataReviewService.batchUpdateStatus(source, request);
    }

    @PostMapping("/{source}/batch-preview")
    public DataReviewBatchPreviewResponse batchPreview(
            @PathVariable String source,
            @RequestBody DataReviewBatchStatusRequest request
    ) {
        return dataReviewService.previewBatchStatus(source, request);
    }

    @PostMapping("/{source}/{id}/rollback/{auditId}")
    public DataReviewRowResponse rollback(
            @PathVariable String source,
            @PathVariable String id,
            @PathVariable Long auditId,
            @RequestBody(required = false) DataReviewStatusRequest request
    ) {
        return dataReviewService.rollback(
                source,
                id,
                auditId,
                request == null ? null : request.note(),
                request == null ? null : request.reasonCode(),
                request == null ? null : request.expectedRevision()
        );
    }

    @GetMapping("/{source}/{id}/history")
    public List<DataReviewAuditLogResponse> history(@PathVariable String source, @PathVariable String id) {
        return dataReviewService.getHistory(source, id);
    }
}
