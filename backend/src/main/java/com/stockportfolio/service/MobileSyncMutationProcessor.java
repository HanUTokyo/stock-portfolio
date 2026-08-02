package com.stockportfolio.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.stockportfolio.dto.CashAdjustmentRequest;
import com.stockportfolio.dto.CashAdjustmentResponse;
import com.stockportfolio.dto.DividendRequest;
import com.stockportfolio.dto.DividendResponse;
import com.stockportfolio.dto.DataReviewPatchRequest;
import com.stockportfolio.dto.DataReviewRowResponse;
import com.stockportfolio.dto.FundamentalNoteRequest;
import com.stockportfolio.dto.FundamentalNoteResponse;
import com.stockportfolio.dto.OverviewNoteRequest;
import com.stockportfolio.dto.OverviewNoteResponse;
import com.stockportfolio.dto.PositionMetadataRequest;
import com.stockportfolio.dto.PositionResponse;
import com.stockportfolio.dto.SharesOutstandingOverrideRequest;
import com.stockportfolio.dto.StockNoteRequest;
import com.stockportfolio.dto.StockNoteResponse;
import com.stockportfolio.dto.SyncMutationRequest;
import com.stockportfolio.dto.SyncMutationResult;
import com.stockportfolio.dto.TransactionRequest;
import com.stockportfolio.dto.TransactionResponse;
import com.stockportfolio.model.MobileSyncMutation;
import com.stockportfolio.model.OverviewNoteType;
import com.stockportfolio.repository.CashAdjustmentRepository;
import com.stockportfolio.repository.DividendRepository;
import com.stockportfolio.repository.DataReviewRecordRepository;
import com.stockportfolio.repository.FundamentalNoteRepository;
import com.stockportfolio.repository.MobileSyncMutationRepository;
import com.stockportfolio.repository.OverviewNoteRepository;
import com.stockportfolio.repository.PositionRepository;
import com.stockportfolio.repository.StockNoteRepository;
import com.stockportfolio.repository.TransactionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class MobileSyncMutationProcessor {
    private final ObjectMapper objectMapper;
    private final ObjectMapper canonicalMapper;
    private final MobileSyncMutationRepository ledgerRepository;
    private final PortfolioService portfolioService;
    private final TransactionRepository transactionRepository;
    private final DividendRepository dividendRepository;
    private final CashAdjustmentRepository cashRepository;
    private final PositionRepository positionRepository;
    private final StockNoteRepository stockNoteRepository;
    private final FundamentalNoteRepository fundamentalNoteRepository;
    private final OverviewNoteRepository overviewNoteRepository;
    private final DataReviewRecordRepository dataReviewRecordRepository;
    private final DataReviewService dataReviewService;

    public MobileSyncMutationProcessor(
            ObjectMapper objectMapper,
            MobileSyncMutationRepository ledgerRepository,
            PortfolioService portfolioService,
            TransactionRepository transactionRepository,
            DividendRepository dividendRepository,
            CashAdjustmentRepository cashRepository,
            PositionRepository positionRepository,
            StockNoteRepository stockNoteRepository,
            FundamentalNoteRepository fundamentalNoteRepository,
            OverviewNoteRepository overviewNoteRepository,
            DataReviewRecordRepository dataReviewRecordRepository,
            DataReviewService dataReviewService
    ) {
        this.objectMapper = objectMapper;
        this.canonicalMapper = objectMapper.copy()
                .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);
        this.ledgerRepository = ledgerRepository;
        this.portfolioService = portfolioService;
        this.transactionRepository = transactionRepository;
        this.dividendRepository = dividendRepository;
        this.cashRepository = cashRepository;
        this.positionRepository = positionRepository;
        this.stockNoteRepository = stockNoteRepository;
        this.fundamentalNoteRepository = fundamentalNoteRepository;
        this.overviewNoteRepository = overviewNoteRepository;
        this.dataReviewRecordRepository = dataReviewRecordRepository;
        this.dataReviewService = dataReviewService;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public synchronized SyncMutationResult process(SyncMutationRequest request) {
        String hash = requestHash(request);
        Optional<MobileSyncMutation> prior = ledgerRepository.findById(request.mutationId());
        if (prior.isPresent()) {
            if (!prior.get().getRequestHash().equals(hash)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "mutationId was already used with different content");
            }
            return readStored(prior.get());
        }

        SyncMutationResult result;
        try {
            result = dispatch(request);
        } catch (ResponseStatusException error) {
            result = SyncMutationResult.rejected(request.mutationId(), error.getReason());
        } catch (RuntimeException error) {
            result = SyncMutationResult.rejected(request.mutationId(), safeMessage(error));
        }
        store(request, hash, result);
        return result;
    }

    private SyncMutationResult dispatch(SyncMutationRequest request) {
        String entity = request.entityType().trim().toUpperCase(Locale.ROOT);
        String action = request.action().trim().toUpperCase(Locale.ROOT);
        return switch (entity) {
            case "TRANSACTION" -> transaction(request, action);
            case "DIVIDEND" -> dividend(request, action);
            case "CASH_ADJUSTMENT" -> cash(request, action);
            case "STOCK_NOTE" -> stockNote(request);
            case "FUNDAMENTAL_NOTE" -> fundamentalNote(request);
            case "OVERVIEW_NOTE" -> overviewNote(request);
            case "POSITION_METADATA" -> positionMetadata(request);
            case "SHARES_OVERRIDE" -> sharesOverride(request);
            case "DATA_REVIEW" -> dataReview(request, action);
            default -> SyncMutationResult.rejected(request.mutationId(), "Unsupported entityType: " + entity);
        };
    }

    private SyncMutationResult transaction(SyncMutationRequest request, String action) {
        if ("CREATE".equals(action)) {
            TransactionResponse response = portfolioService.recordTransaction(payload(request, TransactionRequest.class));
            transactionRepository.flush();
            long version = transactionRepository.findById(response.id()).orElseThrow().getVersion();
            return applied(request, response.id(), version, response);
        }
        long id = numericId(request);
        var existing = transactionRepository.findById(id);
        if (existing.isEmpty()) return missingConflict(request, id);
        SyncMutationResult conflict = versionConflict(request, id, existing.get().getVersion(), existing.get());
        if (conflict != null) return conflict;
        if ("DELETE".equals(action)) {
            portfolioService.deleteTransaction(id);
            return SyncMutationResult.applied(request.mutationId(), String.valueOf(id), null, null, null);
        }
        if (!"UPDATE".equals(action)) return unsupportedAction(request, action);
        TransactionResponse response = portfolioService.updateTransaction(id, payload(request, TransactionRequest.class));
        transactionRepository.flush();
        long version = transactionRepository.findById(id).orElseThrow().getVersion();
        return applied(request, id, version, response);
    }

    private SyncMutationResult dividend(SyncMutationRequest request, String action) {
        if ("CREATE".equals(action)) {
            DividendResponse response = portfolioService.recordDividend(payload(request, DividendRequest.class));
            dividendRepository.flush();
            long version = dividendRepository.findById(response.id()).orElseThrow().getVersion();
            return applied(request, response.id(), version, response);
        }
        long id = numericId(request);
        var existing = dividendRepository.findById(id);
        if (existing.isEmpty()) return missingConflict(request, id);
        SyncMutationResult conflict = versionConflict(request, id, existing.get().getVersion(), existing.get());
        if (conflict != null) return conflict;
        if ("DELETE".equals(action)) {
            portfolioService.deleteDividend(id);
            return SyncMutationResult.applied(request.mutationId(), String.valueOf(id), null, null, null);
        }
        if (!"UPDATE".equals(action)) return unsupportedAction(request, action);
        DividendResponse response = portfolioService.updateDividend(id, payload(request, DividendRequest.class));
        dividendRepository.flush();
        long version = dividendRepository.findById(id).orElseThrow().getVersion();
        return applied(request, id, version, response);
    }

    private SyncMutationResult cash(SyncMutationRequest request, String action) {
        if ("CREATE".equals(action)) {
            CashAdjustmentResponse response = portfolioService.recordCashAdjustment(payload(request, CashAdjustmentRequest.class));
            cashRepository.flush();
            long version = cashRepository.findById(response.id()).orElseThrow().getVersion();
            return applied(request, response.id(), version, response);
        }
        long id = numericId(request);
        var existing = cashRepository.findById(id);
        if (existing.isEmpty()) return missingConflict(request, id);
        SyncMutationResult conflict = versionConflict(request, id, existing.get().getVersion(), existing.get());
        if (conflict != null) return conflict;
        if ("DELETE".equals(action)) {
            portfolioService.deleteCashAdjustment(id);
            return SyncMutationResult.applied(request.mutationId(), String.valueOf(id), null, null, null);
        }
        if (!"UPDATE".equals(action)) return unsupportedAction(request, action);
        CashAdjustmentResponse response = portfolioService.updateCashAdjustment(id, payload(request, CashAdjustmentRequest.class));
        cashRepository.flush();
        long version = cashRepository.findById(id).orElseThrow().getVersion();
        return applied(request, id, version, response);
    }

    private SyncMutationResult stockNote(SyncMutationRequest request) {
        String symbol = requiredEntityId(request);
        var existing = stockNoteRepository.findBySymbolIgnoreCase(symbol);
        SyncMutationResult conflict = keyedConflict(request, symbol, existing.map(note -> note.getVersion()).orElse(null), existing.orElse(null));
        if (conflict != null) return conflict;
        StockNoteResponse response = portfolioService.upsertStockNote(symbol, payload(request, StockNoteRequest.class));
        stockNoteRepository.flush();
        long version = stockNoteRepository.findBySymbolIgnoreCase(symbol).orElseThrow().getVersion();
        return applied(request, symbol, version, response);
    }

    private SyncMutationResult fundamentalNote(SyncMutationRequest request) {
        String symbol = requiredEntityId(request);
        var existing = fundamentalNoteRepository.findBySymbolIgnoreCase(symbol);
        SyncMutationResult conflict = keyedConflict(request, symbol, existing.map(note -> note.getVersion()).orElse(null), existing.orElse(null));
        if (conflict != null) return conflict;
        FundamentalNoteResponse response = portfolioService.upsertFundamentalNote(symbol, payload(request, FundamentalNoteRequest.class));
        fundamentalNoteRepository.flush();
        long version = fundamentalNoteRepository.findBySymbolIgnoreCase(symbol).orElseThrow().getVersion();
        return applied(request, symbol, version, response);
    }

    private SyncMutationResult overviewNote(SyncMutationRequest request) {
        OverviewNoteType type = OverviewNoteType.valueOf(requiredEntityId(request).toUpperCase(Locale.ROOT));
        var existing = overviewNoteRepository.findByNoteType(type);
        SyncMutationResult conflict = keyedConflict(request, type.name(), existing.map(note -> note.getVersion()).orElse(null), existing.orElse(null));
        if (conflict != null) return conflict;
        OverviewNoteResponse response = portfolioService.upsertOverviewNote(type, payload(request, OverviewNoteRequest.class));
        overviewNoteRepository.flush();
        long version = overviewNoteRepository.findByNoteType(type).orElseThrow().getVersion();
        return applied(request, type.name(), version, response);
    }

    private SyncMutationResult positionMetadata(SyncMutationRequest request) {
        String symbol = requiredEntityId(request);
        var existing = positionRepository.findBySymbolIgnoreCase(symbol);
        SyncMutationResult conflict = keyedConflict(request, symbol, existing.map(position -> position.getVersion()).orElse(null), existing.orElse(null));
        if (conflict != null) return conflict;
        PositionResponse response = portfolioService.updatePositionMetadata(symbol, payload(request, PositionMetadataRequest.class));
        positionRepository.flush();
        long version = positionRepository.findBySymbolIgnoreCase(symbol).orElseThrow().getVersion();
        return applied(request, symbol, version, response);
    }

    private SyncMutationResult sharesOverride(SyncMutationRequest request) {
        String symbol = requiredEntityId(request);
        var existing = positionRepository.findBySymbolIgnoreCase(symbol);
        SyncMutationResult conflict = keyedConflict(request, symbol, existing.map(position -> position.getVersion()).orElse(null), existing.orElse(null));
        if (conflict != null) return conflict;
        PositionResponse response = portfolioService.updateSharesOutstandingOverride(symbol, payload(request, SharesOutstandingOverrideRequest.class));
        positionRepository.flush();
        long version = positionRepository.findBySymbolIgnoreCase(symbol).orElseThrow().getVersion();
        return applied(request, symbol, version, response);
    }

    private SyncMutationResult dataReview(SyncMutationRequest request, String action) {
        String source = String.valueOf(request.payload().getOrDefault("source", "")).trim();
        String recordId = requiredEntityId(request);
        if (source.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "payload.source is required");
        var existing = dataReviewRecordRepository.findBySourceNameAndRecordId(source, recordId);
        String actualRevision = existing.map(row -> String.valueOf(row.getVersion())).orElse("0");
        if (request.baseRevision() == null || !actualRevision.equals(request.baseRevision())) {
            return SyncMutationResult.conflict(
                    request.mutationId(), recordId, existing.map(row -> row.getVersion()).orElse(null),
                    actualRevision, null, "Data review row changed since it was cached"
            );
        }

        DataReviewRowResponse response = switch (action) {
            case "PATCH" -> dataReviewService.patchRow(
                    source,
                    recordId,
                    new DataReviewPatchRequest(
                            objectMapper.convertValue(request.payload().get("changes"), Map.class),
                            optionalText(request, "reviewStatus"),
                            optionalText(request, "note")
                    )
            );
            case "APPROVE" -> dataReviewService.updateStatus(source, recordId, "approved", optionalText(request, "note"));
            case "REJECT" -> dataReviewService.updateStatus(source, recordId, "rejected", optionalText(request, "note"));
            case "UNCERTAIN" -> dataReviewService.updateStatus(source, recordId, "uncertain", optionalText(request, "note"));
            case "ROLLBACK" -> dataReviewService.rollback(source, recordId, requiredLong(request, "auditId"), optionalText(request, "note"));
            default -> null;
        };
        if (response == null) return unsupportedAction(request, action);
        dataReviewRecordRepository.flush();
        var saved = dataReviewRecordRepository.findBySourceNameAndRecordId(source, recordId).orElseThrow();
        String revision = String.valueOf(saved.getVersion());
        Map<String, Object> value = objectMapper.convertValue(response, LinkedHashMap.class);
        value.put("revision", revision);
        return SyncMutationResult.applied(request.mutationId(), recordId, saved.getVersion(), revision, value);
    }

    private String optionalText(SyncMutationRequest request, String key) {
        Object value = request.payload().get(key);
        return value == null ? null : String.valueOf(value);
    }

    private long requiredLong(SyncMutationRequest request, String key) {
        Object value = request.payload().get(key);
        if (value instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "payload." + key + " must be a number");
        }
    }

    private SyncMutationResult keyedConflict(SyncMutationRequest request, String id, Long actualVersion, Object value) {
        if (actualVersion == null && request.baseVersion() == null) return null;
        if (actualVersion == null || request.baseVersion() == null || !actualVersion.equals(request.baseVersion())) {
            return SyncMutationResult.conflict(request.mutationId(), id, actualVersion, null, value, "Server value changed since it was cached");
        }
        return null;
    }

    private SyncMutationResult versionConflict(SyncMutationRequest request, Object id, Long actualVersion, Object value) {
        if (request.baseVersion() == null || !actualVersion.equals(request.baseVersion())) {
            return SyncMutationResult.conflict(request.mutationId(), String.valueOf(id), actualVersion, null, value, "Server version does not match baseVersion");
        }
        return null;
    }

    private SyncMutationResult missingConflict(SyncMutationRequest request, Object id) {
        return SyncMutationResult.conflict(request.mutationId(), String.valueOf(id), null, null, null, "Server record no longer exists");
    }

    private SyncMutationResult applied(SyncMutationRequest request, Object id, Long version, Object response) {
        Map<String, Object> value = objectMapper.convertValue(response, LinkedHashMap.class);
        value.put("version", version);
        return SyncMutationResult.applied(request.mutationId(), String.valueOf(id), version, null, value);
    }

    private SyncMutationResult unsupportedAction(SyncMutationRequest request, String action) {
        return SyncMutationResult.rejected(request.mutationId(), "Unsupported action: " + action);
    }

    private long numericId(SyncMutationRequest request) {
        try {
            return Long.parseLong(requiredEntityId(request));
        } catch (NumberFormatException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "entityId must be a number");
        }
    }

    private String requiredEntityId(SyncMutationRequest request) {
        if (request.entityId() == null || request.entityId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "entityId is required");
        }
        return request.entityId().trim();
    }

    private <T> T payload(SyncMutationRequest request, Class<T> type) {
        return objectMapper.convertValue(request.payload(), type);
    }

    private void store(SyncMutationRequest request, String hash, SyncMutationResult result) {
        MobileSyncMutation ledger = new MobileSyncMutation();
        ledger.setMutationId(request.mutationId());
        ledger.setDeviceId(request.deviceId());
        ledger.setRequestHash(hash);
        ledger.setStatus(result.status());
        try {
            ledger.setResponseJson(objectMapper.writeValueAsString(result));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Could not serialize sync result", error);
        }
        ledgerRepository.save(ledger);
    }

    private SyncMutationResult readStored(MobileSyncMutation ledger) {
        try {
            return objectMapper.readValue(ledger.getResponseJson(), SyncMutationResult.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Stored sync result is invalid", error);
        }
    }

    private String requestHash(SyncMutationRequest request) {
        try {
            byte[] json = canonicalMapper.writeValueAsBytes(request);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json);
            return java.util.HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException | NoSuchAlgorithmException error) {
            throw new IllegalStateException("Could not hash mutation", error);
        }
    }

    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return error.getClass().getSimpleName();
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
