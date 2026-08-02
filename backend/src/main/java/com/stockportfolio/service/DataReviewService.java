package com.stockportfolio.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockportfolio.dto.DataReviewAnomalyResponse;
import com.stockportfolio.dto.DataReviewAuditLogResponse;
import com.stockportfolio.dto.DataReviewBatchStatusRequest;
import com.stockportfolio.dto.DataReviewBatchStatusResponse;
import com.stockportfolio.dto.DataReviewBatchPreviewResponse;
import com.stockportfolio.dto.DataReviewPageResponse;
import com.stockportfolio.dto.DataReviewPatchRequest;
import com.stockportfolio.dto.DataReviewRowResponse;
import com.stockportfolio.dto.DataReviewSourceResponse;
import com.stockportfolio.dto.DataReviewSourceSummaryResponse;
import com.stockportfolio.dto.DataReviewSummaryResponse;
import com.stockportfolio.model.DataReviewAuditLog;
import com.stockportfolio.model.DataReviewRecord;
import com.stockportfolio.repository.DataReviewAuditLogRepository;
import com.stockportfolio.repository.DataReviewRecordRepository;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class DataReviewService {

    private static final String REVIEWER = "manual_admin";
    private static final Set<String> REVIEW_STATUSES = Set.of("pending", "approved", "corrected", "rejected", "uncertain");
    private static final Set<String> REVIEW_REASON_CODES = Set.of(
            "data_error", "source_conflict", "missing_or_stale", "outlier",
            "classification_fix", "manual_verification", "other"
    );
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final DataReviewRecordRepository recordRepository;
    private final DataReviewAuditLogRepository auditLogRepository;
    private final Map<String, SourceConfig> sources;

    public DataReviewService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            DataReviewRecordRepository recordRepository,
            DataReviewAuditLogRepository auditLogRepository
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.recordRepository = recordRepository;
        this.auditLogRepository = auditLogRepository;
        this.sources = buildSources();
    }

    public List<DataReviewSourceResponse> getSources() {
        return sources.values().stream()
                .map(SourceConfig::toResponse)
                .toList();
    }

    public DataReviewSummaryResponse getSummary() {
        List<DataReviewSourceSummaryResponse> summaries = sources.values().stream()
                .map(this::buildSourceSummary)
                .toList();
        return new DataReviewSummaryResponse(summaries);
    }

    public DataReviewPageResponse getRows(
            String sourceName,
            int page,
            int size,
            String search,
            String status,
            String sortBy,
            String sortDirection
    ) {
        return getRows(sourceName, page, size, search, status, sortBy, sortDirection, false, "all", "all");
    }

    public DataReviewPageResponse getRows(
            String sourceName,
            int page,
            int size,
            String search,
            String status,
            String sortBy,
            String sortDirection,
            boolean anomalyOnly
    ) {
        return getRows(sourceName, page, size, search, status, sortBy, sortDirection, anomalyOnly, "all", "all");
    }

    public DataReviewPageResponse getRows(
            String sourceName,
            int page,
            int size,
            String search,
            String status,
            String sortBy,
            String sortDirection,
            boolean anomalyOnly,
            String queue,
            String severity
    ) {
        SourceConfig source = getSource(sourceName);
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        String normalizedStatus = normalizeOptional(status);
        if (normalizedStatus != null && !"all".equals(normalizedStatus) && !REVIEW_STATUSES.contains(normalizedStatus)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported review status: " + status);
        }

        String normalizedQueue = normalizeQueue(queue);
        String effectiveStatus = normalizedStatus;
        boolean attentionQueue = "attention".equals(normalizedQueue);
        if ("pending".equals(normalizedQueue) && (effectiveStatus == null || "all".equals(effectiveStatus))) {
            effectiveStatus = "pending";
        }
        if (attentionQueue && (effectiveStatus == null || "all".equals(effectiveStatus))) {
            effectiveStatus = "pending";
        }
        String normalizedSeverity = normalizeSeverity(severity);
        ReviewQuery query = buildReviewQuery(source, search, effectiveStatus, anomalyOnly || attentionQueue || !"all".equals(normalizedSeverity), normalizedSeverity, "t");
        long totalElements = countRows(source, query);
        List<Map<String, Object>> rawRows = fetchRawRowsPage(source, query, safePage, safeSize, sortBy, sortDirection);
        List<String> recordIds = rawRows.stream()
                .map((row) -> stringValue(row.get("id")))
                .filter(Objects::nonNull)
                .toList();
        Map<String, DataReviewRecord> reviewById = recordIds.isEmpty()
                ? Map.of()
                : recordRepository.findBySourceNameAndRecordIdIn(source.name(), recordIds).stream()
                        .collect(Collectors.toMap(DataReviewRecord::getRecordId, Function.identity()));

        List<DataReviewRowResponse> rows = rawRows.stream()
                .map((row) -> buildRow(source, row, reviewById.get(stringValue(row.get("id")))))
                .filter(row -> (anomalyOnly || attentionQueue || !"all".equals(normalizedSeverity)) ? !row.anomalyFlags().isEmpty() : true)
                .filter(row -> "all".equals(normalizedSeverity) || normalizedSeverity.equals(riskLevel(row.anomalies())))
                .toList();
        int totalPages = totalElements == 0 ? 0 : (int) Math.ceil(totalElements / (double) safeSize);

        return new DataReviewPageResponse(source.name(), safePage, safeSize, totalElements, totalPages, rows);
    }

    @Transactional
    public DataReviewBatchStatusResponse batchUpdateStatus(String sourceName, DataReviewBatchStatusRequest request) {
        SourceConfig source = getSource(sourceName);
        BatchValidation validation = validateBatch(source, request);
        List<DataReviewRowResponse> rows = validation.recordIds().stream()
                .map(recordId -> updateStatus(source.name(), recordId, validation.status(), request.note(), validation.reasonCode(), expectedRevision(request, recordId)))
                .toList();
        return new DataReviewBatchStatusResponse(source.name(), validation.status(), rows.size(), rows);
    }

    public DataReviewBatchPreviewResponse previewBatchStatus(String sourceName, DataReviewBatchStatusRequest request) {
        SourceConfig source = getSource(sourceName);
        BatchValidation validation = validateBatch(source, request);
        List<DataReviewRowResponse> rows = validation.recordIds().stream()
                .map(recordId -> buildCurrentRow(source, recordId))
                .toList();
        Map<String, Long> riskCounts = rows.stream().collect(Collectors.groupingBy(DataReviewRowResponse::riskLevel, LinkedHashMap::new, Collectors.counting()));
        return new DataReviewBatchPreviewResponse(source.name(), validation.status(), validation.reasonCode(), rows.size(), riskCounts, rows);
    }

    @Transactional
    public DataReviewRowResponse patchRow(String sourceName, String recordId, DataReviewPatchRequest request) {
        SourceConfig source = getSource(sourceName);
        Map<String, Object> rawRow = fetchRawRow(source, recordId);
        DataReviewRecord record = getOrCreateRecord(source.name(), recordId);
        Map<String, Object> reviewedValues = parseReviewedValues(record.getReviewedValueJson());
        Map<String, Object> effectiveValues = new LinkedHashMap<>(rawRow);
        effectiveValues.putAll(reviewedValues);

        Map<String, Object> requestedChanges = request == null || request.changes() == null ? Map.of() : request.changes();
        for (String fieldName : requestedChanges.keySet()) {
            if (!source.isEditable(fieldName)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Field is not editable for " + source.name() + ": " + fieldName);
            }
        }

        String nextStatus = normalizeStatus(request == null ? null : request.reviewStatus(), requestedChanges.isEmpty() ? "pending" : "corrected");
        String note = request == null ? null : request.note();
        String reasonCode = normalizeReason(request == null ? null : request.reasonCode(), nextStatus);
        ensureRevision(record, request == null ? null : request.expectedRevision());
        boolean wroteFieldAudit = false;

        for (Map.Entry<String, Object> entry : requestedChanges.entrySet()) {
            String fieldName = entry.getKey();
            Object oldValue = effectiveValues.get(fieldName);
            Object newValue = entry.getValue();
            reviewedValues.put(fieldName, newValue);
            effectiveValues.put(fieldName, newValue);

            if (!Objects.equals(normalizeForAudit(oldValue), normalizeForAudit(newValue))) {
                auditLogRepository.save(auditLog(source.name(), recordId, fieldName, oldValue, newValue, "correct", nextStatus, note, reasonCode));
                wroteFieldAudit = true;
            }
        }

        String previousStatus = record.getReviewStatus();
        record.setReviewStatus(nextStatus);
        record.setReviewedValueJson(writeReviewedValues(reviewedValues));
        record.setNote(note);
        record.setReasonCode(reasonCode);
        record.setReviewer(REVIEWER);
        DataReviewRecord saved = recordRepository.save(record);

        if (!wroteFieldAudit || !Objects.equals(previousStatus, nextStatus)) {
            auditLogRepository.save(auditLog(source.name(), recordId, "_status", previousStatus, nextStatus, "save", nextStatus, note, reasonCode));
        }

        return buildRow(source, rawRow, saved);
    }

    @Transactional
    public DataReviewRowResponse updateStatus(String sourceName, String recordId, String reviewStatus, String note) {
        return updateStatus(sourceName, recordId, reviewStatus, note, null, null);
    }

    @Transactional
    public DataReviewRowResponse updateStatus(String sourceName, String recordId, String reviewStatus, String note, String reasonCode, String expectedRevision) {
        SourceConfig source = getSource(sourceName);
        Map<String, Object> rawRow = fetchRawRow(source, recordId);
        String nextStatus = normalizeStatus(reviewStatus, "pending");
        DataReviewRecord record = getOrCreateRecord(source.name(), recordId);
        ensureRevision(record, expectedRevision);
        String normalizedReason = normalizeReason(reasonCode, nextStatus);
        String previousStatus = record.getReviewStatus();
        record.setReviewStatus(nextStatus);
        record.setNote(note);
        record.setReasonCode(normalizedReason);
        record.setReviewer(REVIEWER);
        DataReviewRecord saved = recordRepository.save(record);

        auditLogRepository.save(auditLog(source.name(), recordId, "_status", previousStatus, nextStatus, actionForStatus(nextStatus), nextStatus, note, normalizedReason));

        return buildRow(source, rawRow, saved);
    }

    @Transactional
    public DataReviewRowResponse rollback(String sourceName, String recordId, Long auditLogId, String note) {
        return rollback(sourceName, recordId, auditLogId, note, null, null);
    }

    @Transactional
    public DataReviewRowResponse rollback(String sourceName, String recordId, Long auditLogId, String note, String reasonCode, String expectedRevision) {
        SourceConfig source = getSource(sourceName);
        DataReviewAuditLog audit = auditLogRepository.findById(auditLogId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Audit event not found."));
        if (!source.name().equals(audit.getSourceName()) || !recordId.equals(audit.getRecordId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Audit event does not belong to this review row.");
        }
        Map<String, Object> rawRow = fetchRawRow(source, recordId);
        DataReviewRecord record = getOrCreateRecord(source.name(), recordId);
        ensureRevision(record, expectedRevision);
        String normalizedReason = normalizeReason(reasonCode, "corrected");
        Map<String, Object> reviewedValues = parseReviewedValues(record.getReviewedValueJson());
        String previousStatus = record.getReviewStatus();

        if ("_status".equals(audit.getFieldName())) {
            String restoredStatus = normalizeStatus(audit.getOldValue(), "pending");
            record.setReviewStatus(restoredStatus);
            record.setNote(note);
            record.setReasonCode(normalizedReason);
            record.setReviewer(REVIEWER);
            DataReviewRecord saved = recordRepository.save(record);
            auditLogRepository.save(auditLog(source.name(), recordId, "_status", previousStatus, restoredStatus, "rollback", restoredStatus, note, normalizedReason));
            return buildRow(source, rawRow, saved);
        }

        if (!source.isEditable(audit.getFieldName())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This audit field cannot be rolled back.");
        }
        Object beforeRollback = reviewedValues.getOrDefault(audit.getFieldName(), rawRow.get(audit.getFieldName()));
        reviewedValues.put(audit.getFieldName(), audit.getOldValue());
        record.setReviewedValueJson(writeReviewedValues(reviewedValues));
        record.setReviewStatus("corrected");
        record.setNote(note == null || note.isBlank() ? "Rolled back " + audit.getFieldName() + " from audit event #" + auditLogId + "." : note);
        record.setReviewer(REVIEWER);
        record.setReasonCode(normalizedReason);
        DataReviewRecord saved = recordRepository.save(record);
        auditLogRepository.save(auditLog(source.name(), recordId, audit.getFieldName(), beforeRollback, audit.getOldValue(), "rollback", "corrected", record.getNote(), normalizedReason));
        return buildRow(source, rawRow, saved);
    }

    public List<DataReviewAuditLogResponse> getHistory(String sourceName, String recordId) {
        SourceConfig source = getSource(sourceName);
        fetchRawRow(source, recordId);
        return auditLogRepository.findBySourceNameAndRecordIdOrderByCreatedAtDesc(source.name(), recordId).stream()
                .map((log) -> new DataReviewAuditLogResponse(
                        log.getId(),
                        log.getSourceName(),
                        log.getRecordId(),
                        log.getFieldName(),
                        log.getOldValue(),
                        log.getNewValue(),
                        log.getAction(),
                        log.getReviewStatus(),
                        log.getReviewer(),
                        log.getNote(),
                        log.getReasonCode(),
                        log.getCreatedAt()
                ))
                .toList();
    }

    private List<Map<String, Object>> fetchRawRowsPage(
            SourceConfig source,
            ReviewQuery query,
            int page,
            int size,
            String sortBy,
            String sortDirection
    ) {
        String sql = "SELECT " + source.selectClause("t")
                + " FROM " + source.tableName() + " t"
                + reviewJoin("t")
                + query.whereClause()
                + " ORDER BY " + sortExpression(source, sortBy) + " " + sortDirection(sortDirection) + ", t.id ASC"
                + " LIMIT ? OFFSET ?";
        List<Object> params = new ArrayList<>();
        params.add(source.name());
        params.addAll(query.params());
        params.add(size);
        params.add((long) page * size);
        return jdbcTemplate.queryForList(sql, params.toArray()).stream()
                .map((row) -> normalizeRow(source, row))
                .toList();
    }

    private long countRows(SourceConfig source, ReviewQuery query) {
        String sql = "SELECT COUNT(*) FROM " + source.tableName() + " t" + reviewJoin("t") + query.whereClause();
        List<Object> params = new ArrayList<>();
        params.add(source.name());
        params.addAll(query.params());
        Number count = jdbcTemplate.queryForObject(sql, params.toArray(), Number.class);
        return count == null ? 0 : count.longValue();
    }

    private ReviewQuery buildReviewQuery(
            SourceConfig source,
            String search,
            String status,
            boolean anomalyOnly,
            String severity,
            String alias
    ) {
        List<String> predicates = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        String normalizedSearch = normalizeOptional(search);
        if (normalizedSearch != null) {
            predicates.add("(" + source.searchableFields().stream()
                    .map((field) -> "LOWER(CAST(" + alias + "." + field.columnName() + " AS TEXT)) LIKE ?")
                    .collect(Collectors.joining(" OR ")) + ")");
            String needle = "%" + normalizedSearch.toLowerCase(Locale.ROOT) + "%";
            for (int i = 0; i < source.searchableFields().size(); i += 1) {
                params.add(needle);
            }
        }
        if (status != null && !"all".equals(status)) {
            predicates.add("COALESCE(rr.review_status, 'pending') = ?");
            params.add(status);
        }
        if (anomalyOnly) {
            AnomalyOverrides overrides = correctedAnomalyOverrides(source);
            String rawAnomaly = severityPredicate(source, severity, alias);
            if (!overrides.resolvedRecordIds().isEmpty()) {
                rawAnomaly += " AND " + alias + ".id NOT IN (" + placeholders(overrides.resolvedRecordIds().size()) + ")";
                params.addAll(overrides.resolvedRecordIds());
            }
            if (!overrides.introducedRecordIds().isEmpty()) {
                rawAnomaly = "(" + rawAnomaly + " OR " + alias + ".id IN ("
                        + placeholders(overrides.introducedRecordIds().size()) + "))";
                params.addAll(overrides.introducedRecordIds());
            }
            predicates.add("(" + rawAnomaly + ")");
        }
        return new ReviewQuery(predicates.isEmpty() ? "" : " WHERE " + String.join(" AND ", predicates), params);
    }

    private String reviewJoin(String alias) {
        return " LEFT JOIN data_review_records rr ON rr.source_name = ? AND rr.record_id = CAST(" + alias + ".id AS TEXT)";
    }

    private String sortExpression(SourceConfig source, String sortBy) {
        String fieldName = normalizeOptional(sortBy);
        if ("priority".equals(fieldName)) {
            return "CASE WHEN " + anomalyPredicate(source, "t") + " THEN 0 ELSE 1 END";
        }
        if ("reviewStatus".equals(fieldName)) {
            return "COALESCE(rr.review_status, 'pending')";
        }
        if ("updatedAt".equals(fieldName) && !source.hasField("updatedAt")) {
            return "rr.updated_at";
        }
        return "t." + source.columnNameFor(fieldName == null ? "id" : fieldName);
    }

    private String sortDirection(String sortDirection) {
        return "desc".equalsIgnoreCase(sortDirection) ? "DESC" : "ASC";
    }

    private Map<String, Object> fetchRawRow(SourceConfig source, String recordId) {
        String sql = "SELECT " + source.selectClause("t") + " FROM " + source.tableName() + " t WHERE t.id = ?";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, recordIdParameter(recordId)).stream()
                .map((row) -> normalizeRow(source, row))
                .toList();
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Review source record not found.");
        }
        return rows.get(0);
    }

    private Object recordIdParameter(String recordId) {
        try {
            return Long.parseLong(recordId);
        } catch (NumberFormatException ignored) {
            return recordId;
        }
    }

    private Map<String, Object> normalizeRow(SourceConfig source, Map<String, Object> row) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (SourceField field : source.fields()) {
            normalized.put(field.fieldName(), row.get(field.fieldName()));
        }
        return normalized;
    }

    private DataReviewRowResponse buildRow(SourceConfig source, Map<String, Object> rawRow, DataReviewRecord reviewRecord) {
        Map<String, Object> reviewedValues = reviewRecord == null
                ? Map.of()
                : parseReviewedValues(reviewRecord.getReviewedValueJson());
        Map<String, Object> effectiveValues = new LinkedHashMap<>(rawRow);
        if (reviewRecord != null && "corrected".equals(reviewRecord.getReviewStatus())) {
            effectiveValues.putAll(reviewedValues);
        }
        List<String> anomalyFlags = detectAnomalies(source.name(), effectiveValues);
        String reviewStatus = reviewRecord == null
                ? "pending"
                : reviewRecord.getReviewStatus();

        return new DataReviewRowResponse(
                source.name(),
                stringValue(rawRow.get("id")),
                rawRow,
                reviewedValues,
                effectiveValues,
                reviewStatus,
                reviewRecord == null ? null : reviewRecord.getNote(),
                reviewRecord == null ? null : reviewRecord.getReviewer(),
                reviewRecord == null ? null : reviewRecord.getUpdatedAt(),
                reviewRecord == null ? null : reviewRecord.getReasonCode(),
                riskLevel(anomalyResponses(anomalyFlags)),
                anomalyFlags.size(),
                anomalyFlags,
                anomalyResponses(anomalyFlags),
                reviewRecord == null ? "0" : String.valueOf(reviewRecord.getVersion())
        );
    }

    private DataReviewSourceSummaryResponse buildSourceSummary(SourceConfig source) {
        String sql = "SELECT "
                + "COUNT(*) AS total, "
                + "COALESCE(SUM(CASE WHEN COALESCE(rr.review_status, 'pending') = 'pending' THEN 1 ELSE 0 END), 0) AS pending, "
                + "COALESCE(SUM(CASE WHEN rr.review_status = 'approved' THEN 1 ELSE 0 END), 0) AS approved, "
                + "COALESCE(SUM(CASE WHEN rr.review_status = 'corrected' THEN 1 ELSE 0 END), 0) AS corrected, "
                + "COALESCE(SUM(CASE WHEN rr.review_status = 'rejected' THEN 1 ELSE 0 END), 0) AS rejected, "
                + "COALESCE(SUM(CASE WHEN rr.review_status = 'uncertain' THEN 1 ELSE 0 END), 0) AS uncertain "
                + "FROM " + source.tableName() + " t" + reviewJoin("t");
        Map<String, Object> counts = jdbcTemplate.queryForMap(sql, source.name());
        long anomalies = countRawAnomalies(source) + correctedAnomalyAdjustment(source);
        return new DataReviewSourceSummaryResponse(
                source.name(),
                source.label(),
                source.tableName(),
                countValue(counts, "total"),
                countValue(counts, "pending"),
                countValue(counts, "approved"),
                countValue(counts, "corrected"),
                countValue(counts, "rejected"),
                countValue(counts, "uncertain"),
                anomalies,
                anomalies,
                countValue(counts, "approved") + countValue(counts, "corrected") + countValue(counts, "rejected")
        );
    }

    private long countRawAnomalies(SourceConfig source) {
        Number count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + source.tableName() + " t WHERE " + anomalyPredicate(source, "t"),
                Number.class
        );
        return count == null ? 0 : count.longValue();
    }

    private long correctedAnomalyAdjustment(SourceConfig source) {
        AnomalyOverrides overrides = correctedAnomalyOverrides(source);
        return overrides.introducedRecordIds().size() - overrides.resolvedRecordIds().size();
    }

    private AnomalyOverrides correctedAnomalyOverrides(SourceConfig source) {
        List<Object> resolvedRecordIds = new ArrayList<>();
        List<Object> introducedRecordIds = new ArrayList<>();
        for (DataReviewRecord record : recordRepository.findBySourceNameAndReviewStatus(source.name(), "corrected")) {
            try {
                Map<String, Object> rawValues = fetchRawRow(source, record.getRecordId());
                boolean rawHasAnomaly = !detectAnomalies(source.name(), rawValues).isEmpty();
                Map<String, Object> effectiveValues = new LinkedHashMap<>(rawValues);
                effectiveValues.putAll(parseReviewedValues(record.getReviewedValueJson()));
                boolean effectiveHasAnomaly = !detectAnomalies(source.name(), effectiveValues).isEmpty();
                if (rawHasAnomaly && !effectiveHasAnomaly) {
                    resolvedRecordIds.add(recordIdParameter(record.getRecordId()));
                } else if (!rawHasAnomaly && effectiveHasAnomaly) {
                    introducedRecordIds.add(recordIdParameter(record.getRecordId()));
                }
            } catch (ResponseStatusException ignored) {
                // Keep an audit record even when its original source row was deleted.
            }
        }
        return new AnomalyOverrides(resolvedRecordIds, introducedRecordIds);
    }

    private long countValue(Map<String, Object> counts, String key) {
        Object value = counts.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? 0 : Long.parseLong(String.valueOf(value));
    }

    private String anomalyPredicate(SourceConfig source, String alias) {
        return switch (source.name()) {
            case "company_profiles" -> "(" + alias + ".symbol IS NULL OR TRIM(" + alias + ".symbol) = ''"
                    + " OR " + alias + ".latest_price IS NULL OR " + alias + ".shares_outstanding IS NULL)";
            case "financial_metrics" -> alias + ".trailing_pe < 0";
            case "market_data" -> "(" + alias + ".close_price IS NULL OR " + alias + ".close_price <= 0)";
            case "fundamentals" -> "(" + alias + ".gross_margin > 100 OR " + alias + ".gross_margin < -100"
                    + " OR " + alias + ".revenue IS NULL OR " + alias + ".currency_code IS NULL"
                    + " OR TRIM(" + alias + ".currency_code) = '')";
            default -> "FALSE";
        };
    }

    private String severityPredicate(SourceConfig source, String severity, String alias) {
        if ("all".equals(severity)) {
            return anomalyPredicate(source, alias);
        }
        return switch (source.name()) {
            case "company_profiles" -> "medium".equals(severity)
                    ? anomalyPredicate(source, alias) : "FALSE";
            case "financial_metrics" -> "high".equals(severity) ? alias + ".trailing_pe < 0" : "FALSE";
            case "market_data" -> "high".equals(severity)
                    ? alias + ".close_price <= 0"
                    : "medium".equals(severity) ? alias + ".close_price IS NULL" : "FALSE";
            case "fundamentals" -> "high".equals(severity)
                    ? "(" + alias + ".gross_margin > 100 OR " + alias + ".gross_margin < -100)"
                    : "medium".equals(severity)
                    ? "(" + alias + ".revenue IS NULL OR " + alias + ".currency_code IS NULL OR TRIM(" + alias + ".currency_code) = '')"
                    : "FALSE";
            default -> "FALSE";
        };
    }

    private String placeholders(int count) {
        StringBuilder placeholders = new StringBuilder();
        for (int index = 0; index < count; index += 1) {
            if (index > 0) {
                placeholders.append(", ");
            }
            placeholders.append('?');
        }
        return placeholders.toString();
    }

    private List<DataReviewAnomalyResponse> anomalyResponses(List<String> flags) {
        return flags.stream().map(flag -> {
            String normalized = flag.toLowerCase(Locale.ROOT);
            String severity = normalized.contains("<=") || normalized.contains("> 100") || normalized.contains("< -100")
                    ? "high"
                    : normalized.contains("missing") ? "medium" : "low";
            String code = normalized.replaceAll("[^a-z0-9]+", "_").replaceAll("(^_|_$)", "");
            return new DataReviewAnomalyResponse(code, severity, flag);
        }).toList();
    }

    private String riskLevel(List<DataReviewAnomalyResponse> anomalies) {
        if (anomalies.stream().anyMatch(anomaly -> "high".equals(anomaly.severity()))) return "urgent";
        if (anomalies.stream().anyMatch(anomaly -> "medium".equals(anomaly.severity()))) return "high";
        if (!anomalies.isEmpty()) return "normal";
        return "normal";
    }

    private String normalizeQueue(String queue) {
        String normalized = normalizeOptional(queue);
        if (normalized == null) return "all";
        if (!Set.of("attention", "pending", "all").contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported review queue: " + queue);
        }
        return normalized;
    }

    private String normalizeSeverity(String severity) {
        String normalized = normalizeOptional(severity);
        if (normalized == null) return "all";
        if (!Set.of("all", "high", "medium", "low").contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported severity: " + severity);
        }
        return normalized;
    }

    private String normalizeReason(String reasonCode, String reviewStatus) {
        String normalized = normalizeOptional(reasonCode);
        if (normalized == null && Set.of("corrected", "rejected", "uncertain").contains(reviewStatus)) {
            // Compatibility for existing clients; the new workbench always sends an explicit reason.
            return "manual_verification";
        }
        if (normalized != null && !REVIEW_REASON_CODES.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported review reason: " + reasonCode);
        }
        return normalized;
    }

    private void ensureRevision(DataReviewRecord record, String expectedRevision) {
        String expected = normalizeOptional(expectedRevision);
        if (expected == null) return;
        String actual = record.getVersion() == null ? "0" : String.valueOf(record.getVersion());
        if (!actual.equals(expected)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This review record changed. Refresh it before saving.");
        }
    }

    private DataReviewRowResponse buildCurrentRow(SourceConfig source, String recordId) {
        Map<String, Object> rawRow = fetchRawRow(source, recordId);
        DataReviewRecord record = recordRepository.findBySourceNameAndRecordId(source.name(), recordId).orElse(null);
        return buildRow(source, rawRow, record);
    }

    private BatchValidation validateBatch(SourceConfig source, DataReviewBatchStatusRequest request) {
        if (request == null || request.recordIds() == null || request.recordIds().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose at least one review row.");
        }
        String status = normalizeStatus(request.reviewStatus(), "pending");
        String reasonCode = normalizeReason(request.reasonCode(), status);
        List<String> recordIds = request.recordIds().stream().filter(Objects::nonNull).distinct().toList();
        if (recordIds.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose at least one review row.");
        for (String recordId : recordIds) {
            fetchRawRow(source, recordId);
            DataReviewRecord record = recordRepository.findBySourceNameAndRecordId(source.name(), recordId).orElseGet(() -> {
                DataReviewRecord fresh = new DataReviewRecord();
                fresh.setSourceName(source.name());
                fresh.setRecordId(recordId);
                fresh.setReviewStatus("pending");
                return fresh;
            });
            ensureRevision(record, expectedRevision(request, recordId));
        }
        return new BatchValidation(recordIds, status, reasonCode);
    }

    private String expectedRevision(DataReviewBatchStatusRequest request, String recordId) {
        return request.expectedRevisions() == null ? null : request.expectedRevisions().get(recordId);
    }

    private List<String> detectAnomalies(String sourceName, Map<String, Object> values) {
        List<String> flags = new ArrayList<>();
        if ("company_profiles".equals(sourceName)) {
            if (isBlank(values.get("symbol"))) {
                flags.add("company name missing");
            }
            if (values.get("latestPrice") == null || values.get("sharesOutstanding") == null) {
                flags.add("market cap missing");
            }
        }
        if ("financial_metrics".equals(sourceName)) {
            BigDecimal trailingPe = decimalValue(values.get("trailingPe"));
            if (trailingPe != null && trailingPe.compareTo(BigDecimal.ZERO) < 0) {
                flags.add("PE < 0");
            }
        }
        if ("market_data".equals(sourceName)) {
            BigDecimal closePrice = decimalValue(values.get("closePrice"));
            if (closePrice == null) {
                flags.add("market price missing");
            } else if (closePrice.compareTo(BigDecimal.ZERO) <= 0) {
                flags.add("market price <= 0");
            }
        }
        if ("fundamentals".equals(sourceName)) {
            BigDecimal grossMargin = decimalValue(values.get("grossMargin"));
            if (grossMargin != null && grossMargin.compareTo(new BigDecimal("100")) > 0) {
                flags.add("gross margin > 100");
            }
            if (grossMargin != null && grossMargin.compareTo(new BigDecimal("-100")) < 0) {
                flags.add("gross margin < -100");
            }
            if (values.get("revenue") == null) {
                flags.add("revenue missing");
            }
            if (isBlank(values.get("currencyCode"))) {
                flags.add("currency missing");
            }
        }
        return flags;
    }

    private SourceConfig getSource(String sourceName) {
        SourceConfig source = sources.get(sourceName);
        if (source == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown review source: " + sourceName);
        }
        return source;
    }

    private DataReviewRecord getOrCreateRecord(String sourceName, String recordId) {
        return recordRepository.findBySourceNameAndRecordId(sourceName, recordId)
                .orElseGet(() -> {
                    DataReviewRecord record = new DataReviewRecord();
                    record.setSourceName(sourceName);
                    record.setRecordId(recordId);
                    record.setReviewStatus("pending");
                    record.setReviewer(REVIEWER);
                    return record;
                });
    }

    private Map<String, Object> parseReviewedValues(String reviewedValueJson) {
        if (reviewedValueJson == null || reviewedValueJson.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return new LinkedHashMap<>(objectMapper.readValue(reviewedValueJson, MAP_TYPE));
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Stored reviewed value JSON is invalid.", e);
        }
    }

    private String writeReviewedValues(Map<String, Object> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not write reviewed value JSON.", e);
        }
    }

    private DataReviewAuditLog auditLog(
            String sourceName,
            String recordId,
            String fieldName,
            Object oldValue,
            Object newValue,
            String action,
            String reviewStatus,
            String note,
            String reasonCode
    ) {
        DataReviewAuditLog log = new DataReviewAuditLog();
        log.setSourceName(sourceName);
        log.setRecordId(recordId);
        log.setFieldName(fieldName);
        log.setOldValue(normalizeForAudit(oldValue));
        log.setNewValue(normalizeForAudit(newValue));
        log.setAction(action);
        log.setReviewStatus(reviewStatus);
        log.setReviewer(REVIEWER);
        log.setNote(note);
        log.setReasonCode(reasonCode);
        return log;
    }

    private String actionForStatus(String reviewStatus) {
        return switch (reviewStatus) {
            case "approved" -> "approve";
            case "rejected" -> "reject";
            case "uncertain" -> "uncertain";
            default -> "status";
        };
    }

    private String normalizeStatus(String reviewStatus, String fallback) {
        String normalized = normalizeOptional(reviewStatus);
        if (normalized == null) {
            normalized = fallback;
        }
        if (!REVIEW_STATUSES.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported review status: " + reviewStatus);
        }
        return normalized;
    }

    private String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private String normalizeForAudit(Object value) {
        if (value == null) {
            return null;
        }
        return String.valueOf(value);
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean isBlank(Object value) {
        return value == null || String.valueOf(value).isBlank();
    }

    private BigDecimal decimalValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private Map<String, SourceConfig> buildSources() {
        Map<String, SourceConfig> configs = new LinkedHashMap<>();
        configs.put("company_profiles", new SourceConfig(
                "company_profiles",
                "Company Profiles",
                "positions",
                List.of(
                        field("id", "id", false, false),
                        field("symbol", "symbol", false, true),
                        field("latestPrice", "latest_price", true, false),
                        field("latestPe", "latest_pe", true, false),
                        field("sharesOutstanding", "shares_outstanding", true, false),
                        field("sharesOutstandingOverride", "shares_outstanding_override", true, false),
                        field("sharesOutstandingSource", "shares_outstanding_source", false, false),
                        field("assetClass", "asset_class", true, true),
                        field("instrumentType", "instrument_type", true, true),
                        field("underlying", "underlying", true, true),
                        field("sector", "sector", true, true),
                        field("region", "region", true, true),
                        field("priceUpdatedAt", "price_updated_at", false, false),
                        field("metadataUpdatedAt", "metadata_updated_at", false, false),
                        field("updatedAt", "updated_at", false, false)
                ),
                List.of("id", "symbol", "assetClass", "instrumentType", "sector", "region", "latestPrice", "latestPe")
        ));
        configs.put("financial_metrics", new SourceConfig(
                "financial_metrics",
                "Financial Metrics",
                "pe_history",
                List.of(
                        field("id", "id", false, false),
                        field("symbol", "symbol", false, true),
                        field("tradeDate", "trade_date", false, true),
                        field("trailingPe", "trailing_pe", true, false),
                        field("capturedAt", "captured_at", false, false)
                ),
                List.of("id", "symbol", "tradeDate", "trailingPe", "capturedAt")
        ));
        configs.put("market_data", new SourceConfig(
                "market_data",
                "Market Data",
                "price_history",
                List.of(
                        field("id", "id", false, false),
                        field("symbol", "symbol", false, true),
                        field("tradeDate", "trade_date", false, true),
                        field("closePrice", "close_price", true, false),
                        field("capturedAt", "captured_at", false, false)
                ),
                List.of("id", "symbol", "tradeDate", "closePrice", "capturedAt")
        ));
        configs.put("fundamentals", new SourceConfig(
                "fundamentals",
                "Fundamentals",
                "earnings_history",
                List.of(
                        field("id", "id", false, false),
                        field("symbol", "symbol", false, true),
                        field("asOfDate", "as_of_date", false, true),
                        field("currencyCode", "currency_code", true, true),
                        field("basicEps", "basic_eps", true, false),
                        field("dilutedEps", "diluted_eps", true, false),
                        field("dilutedWeightedAverageShares", "diluted_weighted_average_shares", true, false),
                        field("sourceEps", "source_eps", true, false),
                        field("epsInQuote", "eps_in_quote", true, false),
                        field("ttmEps", "ttm_eps", true, false),
                        field("forwardEps", "forward_eps", true, false),
                        field("revenue", "revenue", true, false),
                        field("grossMargin", "gross_margin", true, false),
                        field("grossProfit", "gross_profit", true, false),
                        field("operatingIncome", "operating_income", true, false),
                        field("interestExpense", "interest_expense", true, false),
                        field("netIncome", "net_income", true, false),
                        field("cashFlow", "cash_flow", true, false),
                        field("fcf", "fcf", true, false),
                        field("capex", "capex", true, false),
                        field("adjustedFcf", "adjusted_fcf", true, false),
                        field("depreciationAmortization", "depreciation_amortization", true, false),
                        field("changeInWorkingCapital", "change_in_working_capital", true, false),
                        field("netBorrowing", "net_borrowing", true, false),
                        field("stockholdersEquity", "stockholders_equity", true, false),
                        field("totalDebt", "total_debt", true, false),
                        field("cashAndEquivalents", "cash_and_equivalents", true, false),
                        field("shortTermInvestments", "short_term_investments", true, false),
                        field("noncurrentMarketableSecurities", "noncurrent_marketable_securities", true, false),
                        field("taxProvision", "tax_provision", true, false),
                        field("pretaxIncome", "pretax_income", true, false),
                        field("investedCapital", "invested_capital", true, false),
                        field("totalAssets", "total_assets", true, false),
                        field("fiscalYear", "fiscal_year", true, false),
                        field("fiscalPeriod", "fiscal_period", true, false),
                        field("filingDate", "filing_date", true, false),
                        field("roe", "roe", true, false),
                        field("roic", "roic", true, false),
                        field("capturedAt", "captured_at", false, false)
                ),
                List.of("id", "symbol", "asOfDate", "currencyCode", "revenue", "grossMargin", "ttmEps", "forwardEps")
        ));
        return configs;
    }

    private SourceField field(String fieldName, String columnName, boolean editable, boolean searchable) {
        return new SourceField(fieldName, columnName, editable, searchable);
    }

    private record ReviewQuery(String whereClause, List<Object> params) {
    }

    private record AnomalyOverrides(List<Object> resolvedRecordIds, List<Object> introducedRecordIds) {
    }

    private record BatchValidation(List<String> recordIds, String status, String reasonCode) {
    }

    private record SourceConfig(
            String name,
            String label,
            String tableName,
            List<SourceField> fields,
            List<String> displayFields
    ) {
        String selectClause(String alias) {
            String prefix = alias == null || alias.isBlank() ? "" : alias + ".";
            return fields.stream()
                    .map((field) -> prefix + field.columnName() + " AS \"" + field.fieldName() + "\"")
                    .collect(Collectors.joining(", "));
        }

        List<String> editableFields() {
            return fields.stream()
                    .filter(SourceField::editable)
                    .map(SourceField::fieldName)
                    .toList();
        }

        List<SourceField> searchableFields() {
            return fields.stream()
                    .filter(SourceField::searchable)
                    .toList();
        }

        boolean isEditable(String fieldName) {
            return fields.stream().anyMatch((field) -> field.editable() && field.fieldName().equals(fieldName));
        }

        boolean hasField(String fieldName) {
            return fields.stream().anyMatch((field) -> field.fieldName().equals(fieldName));
        }

        String columnNameFor(String fieldName) {
            return fields.stream()
                    .filter((field) -> field.fieldName().equals(fieldName))
                    .map(SourceField::columnName)
                    .findFirst()
                    .orElse("id");
        }

        DataReviewSourceResponse toResponse() {
            return new DataReviewSourceResponse(name, label, tableName, displayFields, editableFields());
        }
    }

    private record SourceField(String fieldName, String columnName, boolean editable, boolean searchable) {
    }
}
