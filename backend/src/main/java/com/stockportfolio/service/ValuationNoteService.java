package com.stockportfolio.service;

import com.stockportfolio.dto.FundamentalNoteRequest;
import com.stockportfolio.dto.FundamentalNoteResponse;
import com.stockportfolio.model.ValuationNote;
import com.stockportfolio.repository.ValuationNoteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
public class ValuationNoteService {

    private final ValuationNoteRepository valuationNoteRepository;

    public ValuationNoteService(ValuationNoteRepository valuationNoteRepository) {
        this.valuationNoteRepository = valuationNoteRepository;
    }

    @Transactional(readOnly = true)
    public List<FundamentalNoteResponse> list() {
        return valuationNoteRepository.findAllByOrderBySymbolAsc().stream().map(this::toResponse).toList();
    }

    public FundamentalNoteResponse upsert(String symbolRaw, FundamentalNoteRequest request) {
        String symbol = normalizeSymbol(symbolRaw);
        String note = request.note() == null ? "" : request.note().trim();
        ValuationNote valuationNote = valuationNoteRepository.findBySymbolIgnoreCase(symbol).orElseGet(ValuationNote::new);
        valuationNote.setSymbol(symbol);
        valuationNote.setNote(note);
        return toResponse(valuationNoteRepository.save(valuationNote));
    }

    private FundamentalNoteResponse toResponse(ValuationNote note) {
        return new FundamentalNoteResponse(note.getSymbol(), note.getNote(), note.getUpdatedAt(), note.getVersion());
    }

    private String normalizeSymbol(String raw) {
        String symbol = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (symbol.isBlank() || symbol.length() > 20) throw new IllegalArgumentException("Invalid symbol");
        return symbol;
    }
}
