package com.stockportfolio.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stockportfolio.dto.SyncMutationResult;
import com.stockportfolio.model.MobileSyncMutation;
import com.stockportfolio.repository.MobileSyncMutationRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

@Service
public class MobileSyncImportService {
    private final PortfolioService portfolioService;
    private final MobileSyncMutationRepository ledgerRepository;
    private final ObjectMapper objectMapper;

    public MobileSyncImportService(
            PortfolioService portfolioService,
            MobileSyncMutationRepository ledgerRepository,
            ObjectMapper objectMapper
    ) {
        this.portfolioService = portfolioService;
        this.ledgerRepository = ledgerRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public synchronized SyncMutationResult process(
            String rawType,
            String mutationId,
            String deviceId,
            MultipartFile file
    ) {
        if (mutationId == null || mutationId.isBlank() || deviceId == null || deviceId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mutationId and deviceId are required");
        }
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CSV file is required");
        }
        String type = rawType.trim().toLowerCase(Locale.ROOT);
        String hash = fileHash(type, file);
        var prior = ledgerRepository.findById(mutationId);
        if (prior.isPresent()) {
            if (!prior.get().getRequestHash().equals(hash)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "mutationId was already used with different file content");
            }
            return deserialize(prior.get().getResponseJson());
        }

        Object importResult = switch (type) {
            case "transactions" -> portfolioService.importTransactionsFromCsv(file, false);
            case "dividends" -> portfolioService.importDividendsFromCsv(file);
            case "cash" -> portfolioService.importCashAdjustmentsFromCsv(file);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported import type: " + type);
        };
        SyncMutationResult result = SyncMutationResult.applied(mutationId, null, null, null, importResult);
        MobileSyncMutation ledger = new MobileSyncMutation();
        ledger.setMutationId(mutationId);
        ledger.setDeviceId(deviceId);
        ledger.setRequestHash(hash);
        ledger.setStatus(result.status());
        try {
            ledger.setResponseJson(objectMapper.writeValueAsString(result));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Could not serialize import result", error);
        }
        ledgerRepository.save(ledger);
        return result;
    }

    private String fileHash(String type, MultipartFile file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(type.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(file.getBytes());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException error) {
            throw new IllegalStateException("Could not hash import", error);
        }
    }

    private SyncMutationResult deserialize(String json) {
        try {
            return objectMapper.readValue(json, SyncMutationResult.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Stored import result is invalid", error);
        }
    }
}
