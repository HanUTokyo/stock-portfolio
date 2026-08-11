package com.stockportfolio.service;

import com.stockportfolio.dto.SecDebtRebuildResponse;
import com.stockportfolio.model.EarningsHistory;
import com.stockportfolio.model.FundamentalRebuildAudit;
import com.stockportfolio.repository.EarningsHistoryRepository;
import com.stockportfolio.repository.FundamentalRebuildAuditRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.BAD_GATEWAY;

@Service
public class SecDebtRebuildService {
    private final EarningsHistoryRepository earningsHistoryRepository;
    private final FundamentalRebuildAuditRepository auditRepository;
    private final SecCompanyFactsService secCompanyFactsService;

    public SecDebtRebuildService(
            EarningsHistoryRepository earningsHistoryRepository,
            FundamentalRebuildAuditRepository auditRepository,
            SecCompanyFactsService secCompanyFactsService
    ) {
        this.earningsHistoryRepository = earningsHistoryRepository;
        this.auditRepository = auditRepository;
        this.secCompanyFactsService = secCompanyFactsService;
    }

    @Transactional
    public SecDebtRebuildResponse rebuild(String symbolsCsv, int years, boolean dryRun, String trigger) {
        if (years < 1 || years > 30) {
            throw new ResponseStatusException(BAD_REQUEST, "years must be between 1 and 30");
        }
        List<String> symbols = resolveSymbols(symbolsCsv);
        if (symbols.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "No symbols found. Provide ?symbols=AAPL,MSFT or backfill fundamentals first");
        }

        LocalDate toDate = LocalDate.now();
        LocalDate fromDate = toDate.minusYears(years);
        String runId = UUID.randomUUID().toString();
        List<SecDebtRebuildResponse.FieldChange> changes = new ArrayList<>();
        List<FundamentalRebuildAudit> audits = new ArrayList<>();
        int scannedSecRows = 0;
        int matchedRows = 0;
        int changedRows = 0;
        int unmatchedRows = 0;
        int duplicateFiscalPeriods = 0;

        for (String symbol : symbols) {
            List<EarningsHistory> existing = earningsHistoryRepository
                    .findBySymbolAndAsOfDateBetweenOrderByAsOfDateAsc(symbol, fromDate.minusYears(1), toDate);
            Map<String, List<EarningsHistory>> byPeriod = new LinkedHashMap<>();
            for (EarningsHistory row : existing) {
                byPeriod.computeIfAbsent(periodKey(row.getFiscalYear(), row.getFiscalPeriod(), row.getAsOfDate()),
                        ignored -> new ArrayList<>()).add(row);
            }
            duplicateFiscalPeriods += (int) byPeriod.values().stream().filter(rows -> rows.size() > 1).count();

            List<YahooFinancePriceService.QuarterlyFundamentalPoint> secRows;
            try {
                secRows = secCompanyFactsService
                        .fetchQuarterlyFundamentalsHistory(symbol, fromDate.minusYears(1), toDate).stream()
                        .filter(row -> !row.asOfDate().isBefore(fromDate))
                        .toList();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new ResponseStatusException(BAD_GATEWAY,
                        "SEC Company Facts rebuild interrupted for " + symbol, exception);
            } catch (IOException exception) {
                throw new ResponseStatusException(BAD_GATEWAY,
                        "SEC Company Facts rebuild failed for " + symbol, exception);
            }
            scannedSecRows += secRows.size();

            for (YahooFinancePriceService.QuarterlyFundamentalPoint sec : secRows) {
                String key = periodKey(sec.fiscalYear(), sec.fiscalPeriod(), sec.asOfDate());
                EarningsHistory target = selectCanonical(byPeriod.get(key), sec.asOfDate());
                if (target == null) {
                    unmatchedRows++;
                    continue;
                }
                matchedRows++;
                int before = changes.size();
                collectChange(runId, trigger, symbol, target, key, "totalDebt",
                        target.getTotalDebt(), sec.totalDebt(), changes, audits);
                collectChange(runId, trigger, symbol, target, key, "netBorrowing",
                        target.getNetBorrowing(), sec.netBorrowing(), changes, audits);
                collectChange(runId, trigger, symbol, target, key, "investedCapital",
                        target.getInvestedCapital(), sec.investedCapital(), changes, audits);
                if (changes.size() > before) {
                    changedRows++;
                    if (!dryRun) {
                        target.setTotalDebt(scaledOrExisting(sec.totalDebt(), target.getTotalDebt()));
                        target.setNetBorrowing(scaledOrExisting(sec.netBorrowing(), target.getNetBorrowing()));
                        target.setInvestedCapital(scaledOrExisting(sec.investedCapital(), target.getInvestedCapital()));
                        earningsHistoryRepository.save(target);
                    }
                }
            }
        }

        if (!dryRun && !audits.isEmpty()) {
            auditRepository.saveAll(audits);
        }
        return new SecDebtRebuildResponse(runId, dryRun, fromDate, toDate, symbols.size(), scannedSecRows,
                matchedRows, changedRows, changes.size(), unmatchedRows, duplicateFiscalPeriods,
                List.copyOf(changes), trigger);
    }

    private List<String> resolveSymbols(String symbolsCsv) {
        if (symbolsCsv == null || symbolsCsv.isBlank()) {
            return earningsHistoryRepository.findDistinctSymbols().stream()
                    .filter(Objects::nonNull).map(String::trim).map(String::toUpperCase).distinct().toList();
        }
        return java.util.Arrays.stream(symbolsCsv.split(","))
                .map(String::trim).filter(value -> !value.isBlank()).map(String::toUpperCase).distinct().toList();
    }

    private EarningsHistory selectCanonical(List<EarningsHistory> candidates, LocalDate secDate) {
        if (candidates == null || candidates.isEmpty()) return null;
        return candidates.stream().max(Comparator
                .comparing((EarningsHistory row) -> row.getAsOfDate().equals(secDate))
                .thenComparing(row -> row.getFilingDate() == null ? LocalDate.MIN : row.getFilingDate())
                .thenComparing(EarningsHistory::getAsOfDate)).orElse(null);
    }

    private void collectChange(String runId, String trigger, String symbol, EarningsHistory target, String key,
                               String field, BigDecimal before, BigDecimal after,
                               List<SecDebtRebuildResponse.FieldChange> changes,
                               List<FundamentalRebuildAudit> audits) {
        if (after == null || equalValue(before, after)) return;
        BigDecimal scaledAfter = after.setScale(4, RoundingMode.HALF_UP);
        changes.add(new SecDebtRebuildResponse.FieldChange(symbol, target.getAsOfDate(), key, field,
                before, scaledAfter));
        FundamentalRebuildAudit audit = new FundamentalRebuildAudit();
        audit.setRunId(runId);
        audit.setSymbol(symbol);
        audit.setAsOfDate(target.getAsOfDate());
        audit.setFiscalPeriodKey(key);
        audit.setFieldName(field);
        audit.setBeforeValue(before);
        audit.setAfterValue(scaledAfter);
        audit.setTrigger(trigger);
        audits.add(audit);
    }

    private boolean equalValue(BigDecimal left, BigDecimal right) {
        return left != null && right != null && left.compareTo(right) == 0;
    }

    private BigDecimal scaledOrExisting(BigDecimal candidate, BigDecimal existing) {
        return candidate == null ? existing : candidate.setScale(4, RoundingMode.HALF_UP);
    }

    static String periodKey(Integer fiscalYear, String fiscalPeriod, LocalDate asOfDate) {
        if (fiscalYear != null && fiscalPeriod != null && !fiscalPeriod.isBlank()) {
            return fiscalYear + "-" + fiscalPeriod.trim().toUpperCase();
        }
        return PortfolioService.quarterKey(asOfDate);
    }
}
