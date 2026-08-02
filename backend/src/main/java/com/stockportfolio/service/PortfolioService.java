package com.stockportfolio.service;

import com.stockportfolio.dto.*;
import com.stockportfolio.model.CashAdjustment;
import com.stockportfolio.model.CashAdjustmentType;
import com.stockportfolio.model.Dividend;
import com.stockportfolio.model.EarningsEstimate;
import com.stockportfolio.model.EarningsHistory;
import com.stockportfolio.model.FundamentalNote;
import com.stockportfolio.model.FundamentalFactObservation;
import com.stockportfolio.model.NonGaapEpsHistory;
import com.stockportfolio.model.OverviewNote;
import com.stockportfolio.model.OverviewNoteType;
import com.stockportfolio.model.Position;
import com.stockportfolio.model.PriceHistory;
import com.stockportfolio.model.PeHistory;
import com.stockportfolio.model.Transaction;
import com.stockportfolio.model.TransactionType;
import com.stockportfolio.model.StockSplit;
import com.stockportfolio.repository.CashAdjustmentRepository;
import com.stockportfolio.repository.DividendRepository;
import com.stockportfolio.repository.EarningsEstimateRepository;
import com.stockportfolio.repository.EarningsHistoryRepository;
import com.stockportfolio.repository.FundamentalNoteRepository;
import com.stockportfolio.repository.FundamentalFactObservationRepository;
import com.stockportfolio.repository.NonGaapEpsHistoryRepository;
import com.stockportfolio.repository.OverviewNoteRepository;
import com.stockportfolio.repository.PositionRepository;
import com.stockportfolio.repository.PriceHistoryRepository;
import com.stockportfolio.repository.PeHistoryRepository;
import com.stockportfolio.repository.StockNoteRepository;
import com.stockportfolio.repository.TransactionRepository;
import com.stockportfolio.repository.StockSplitRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
@Transactional
public class PortfolioService {
    private static final DateTimeFormatter CSV_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd H:mm:ss");
    private static final Pattern OCC_OPTION_SYMBOL = Pattern.compile("^([A-Z]+)(\\d{6})([CP])(\\d{8})$");
    private static final long QUARTER_END_TOLERANCE_DAYS = 10;
    private static final int MONEY_SCALE = 4;
    private static final int QUANTITY_SCALE = 8;

    private final PositionRepository positionRepository;
    private final TransactionRepository transactionRepository;
    private final DividendRepository dividendRepository;
    private final CashAdjustmentRepository cashAdjustmentRepository;
    private final PriceHistoryRepository priceHistoryRepository;
    private final PeHistoryRepository peHistoryRepository;
    private final EarningsEstimateRepository earningsEstimateRepository;
    private final EarningsHistoryRepository earningsHistoryRepository;
    private final StockSplitRepository stockSplitRepository;
    private final NonGaapEpsHistoryRepository nonGaapEpsHistoryRepository;
    private final FundamentalNoteRepository fundamentalNoteRepository;
    private final FundamentalFactObservationRepository fundamentalFactObservationRepository;
    private final StockNoteRepository stockNoteRepository;
    private final OverviewNoteRepository overviewNoteRepository;
    private final ReviewedDataResolver reviewedDataResolver;
    private final YahooFinancePriceService yahooFinancePriceService;
    private final SecCompanyFactsService secCompanyFactsService;
    private final int retryMaxAttempts;
    private final long retryBackoffMs;
    private final ZoneId marketZone;

    public PortfolioService(
            PositionRepository positionRepository,
            TransactionRepository transactionRepository,
            DividendRepository dividendRepository,
            CashAdjustmentRepository cashAdjustmentRepository,
            PriceHistoryRepository priceHistoryRepository,
            PeHistoryRepository peHistoryRepository,
            EarningsEstimateRepository earningsEstimateRepository,
            EarningsHistoryRepository earningsHistoryRepository,
            StockSplitRepository stockSplitRepository,
            NonGaapEpsHistoryRepository nonGaapEpsHistoryRepository,
            FundamentalNoteRepository fundamentalNoteRepository,
            FundamentalFactObservationRepository fundamentalFactObservationRepository,
            StockNoteRepository stockNoteRepository,
            OverviewNoteRepository overviewNoteRepository,
            ReviewedDataResolver reviewedDataResolver,
            YahooFinancePriceService yahooFinancePriceService,
            SecCompanyFactsService secCompanyFactsService,
            @Value("${app.pricing.retry.max-attempts:3}") int retryMaxAttempts,
            @Value("${app.pricing.retry.backoff-ms:1000}") long retryBackoffMs,
            @Value("${app.pricing.timezone:America/New_York}") String marketTimezone
    ) {
        this.positionRepository = positionRepository;
        this.transactionRepository = transactionRepository;
        this.dividendRepository = dividendRepository;
        this.cashAdjustmentRepository = cashAdjustmentRepository;
        this.priceHistoryRepository = priceHistoryRepository;
        this.peHistoryRepository = peHistoryRepository;
        this.earningsEstimateRepository = earningsEstimateRepository;
        this.earningsHistoryRepository = earningsHistoryRepository;
        this.stockSplitRepository = stockSplitRepository;
        this.nonGaapEpsHistoryRepository = nonGaapEpsHistoryRepository;
        this.fundamentalNoteRepository = fundamentalNoteRepository;
        this.fundamentalFactObservationRepository = fundamentalFactObservationRepository;
        this.stockNoteRepository = stockNoteRepository;
        this.overviewNoteRepository = overviewNoteRepository;
        this.reviewedDataResolver = reviewedDataResolver;
        this.yahooFinancePriceService = yahooFinancePriceService;
        this.secCompanyFactsService = secCompanyFactsService;
        this.retryMaxAttempts = Math.max(1, retryMaxAttempts);
        this.retryBackoffMs = Math.max(0, retryBackoffMs);
        this.marketZone = ZoneId.of(marketTimezone);
    }

    public PositionResponse addOrUpdatePosition(PositionRequest request) {
        String symbol = normalizeSymbol(request.symbol());
        Position saved = ensurePositionCache(symbol);
        return toPositionResponse(saved);
    }

    @Transactional(readOnly = true)
    public PositionResponse getPosition(String symbolRaw) {
        String symbol = normalizeSymbol(symbolRaw);
        Position position = positionRepository.findBySymbolIgnoreCase(symbol)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Position not found"));
        return toPositionResponse(position);
    }

    public List<PositionResponse> listPositions() {
        return positionRepository.findAll().stream().map(this::toPositionResponse).toList();
    }

    public PositionResponse updateSharesOutstandingOverride(String symbolRaw, SharesOutstandingOverrideRequest request) {
        String symbol = normalizeSymbol(symbolRaw);
        Position position = ensurePositionCache(symbol);
        BigDecimal override = request.sharesOutstandingOverride();
        if (override != null && override.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(BAD_REQUEST, "sharesOutstandingOverride must be positive or null");
        }
        position.setSharesOutstandingOverride(override == null ? null : override.setScale(4, RoundingMode.HALF_UP));
        return toPositionResponse(positionRepository.save(position));
    }

    public PositionResponse updatePositionMetadata(String symbolRaw, PositionMetadataRequest request) {
        String symbol = normalizeSymbol(symbolRaw);
        Position position = ensurePositionCache(symbol);
        position.setAssetClass(cleanMetadataText(request.assetClass(), false));
        position.setInstrumentType(cleanMetadataText(request.instrumentType(), false));
        position.setUnderlying(cleanMetadataText(request.underlying(), true));
        position.setSector(cleanMetadataText(request.sector(), false));
        position.setRegion(cleanMetadataText(request.region(), false));
        position.setMetadataUpdatedAt(OffsetDateTime.now());
        return toPositionResponse(positionRepository.save(position));
    }

    public TransactionResponse recordTransaction(TransactionRequest request) {
        String symbol = normalizeSymbol(request.symbol());
        if (request.type() == TransactionType.SELL) {
            validateSellQuantityOrThrow(symbol, request.quantity().setScale(QUANTITY_SCALE, RoundingMode.HALF_UP), null);
        }

        Transaction transaction = new Transaction();
        transaction.setSymbol(symbol);
        transaction.setType(request.type());
        transaction.setQuantity(request.quantity().setScale(QUANTITY_SCALE, RoundingMode.HALF_UP));
        transaction.setPrice(request.price().setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        transaction.setNote(normalizeNote(request.note()));
        transaction.setExecutedAt(request.executedAt() == null ? OffsetDateTime.now() : request.executedAt());

        Transaction saved = transactionRepository.save(transaction);
        ensurePositionCache(symbol);
        upsertCashAdjustmentForTransaction(saved);
        return toTransactionResponse(saved);
    }

    public List<TransactionResponse> listTransactions() {
        return transactionRepository.findAll().stream().map(this::toTransactionResponse).toList();
    }

    public void deleteTransaction(Long transactionId) {
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Transaction not found: " + transactionId));

        String deletedSymbol = transaction.getSymbol();
        cashAdjustmentRepository.deleteByTransactionId(transactionId);
        transactionRepository.delete(transaction);
        ensurePositionCache(deletedSymbol);
    }

    public TransactionResponse updateTransaction(Long transactionId, TransactionRequest request) {
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Transaction not found: " + transactionId));

        String oldSymbol = transaction.getSymbol();
        String newSymbol = normalizeSymbol(request.symbol());
        if (request.type() == TransactionType.SELL) {
            validateSellQuantityOrThrow(newSymbol, request.quantity().setScale(QUANTITY_SCALE, RoundingMode.HALF_UP), transactionId);
        }

        transaction.setSymbol(newSymbol);
        transaction.setType(request.type());
        transaction.setQuantity(request.quantity().setScale(QUANTITY_SCALE, RoundingMode.HALF_UP));
        transaction.setPrice(request.price().setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        transaction.setNote(normalizeNote(request.note()));
        transaction.setExecutedAt(request.executedAt() == null ? OffsetDateTime.now() : request.executedAt());

        Transaction saved = transactionRepository.save(transaction);
        ensurePositionCache(oldSymbol);
        ensurePositionCache(saved.getSymbol());
        upsertCashAdjustmentForTransaction(saved);
        return toTransactionResponse(saved);
    }

    public TransactionCsvImportResponse importTransactionsFromCsv(MultipartFile file, boolean dryRun) {
        CsvImportAnalysis analysis = analyzeCsv(file, dryRun);
        return new TransactionCsvImportResponse(
                dryRun,
                analysis.totalRows,
                analysis.importedRows,
                analysis.skippedRows,
                analysis.failedRows,
                analysis.sampleErrors,
                analysis.failedRowsDetail
        );
    }

    public byte[] exportFailedRowsCsv(MultipartFile file) {
        CsvImportAnalysis analysis = analyzeCsv(file, true);
        StringBuilder csv = new StringBuilder("rowNumber,reason,rawLine\n");

        for (TransactionCsvFailedRow failedRow : analysis.failedRowsDetail) {
            csv.append(failedRow.rowNumber()).append(',')
                    .append(escapeCsv(failedRow.reason())).append(',')
                    .append(escapeCsv(failedRow.rawLine())).append('\n');
        }

        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Transactional(readOnly = true)
    public List<HoldingResponse> getHoldings() {
        List<Transaction> transactions = transactionRepository.findAllByOrderByExecutedAtAscIdAsc();
        java.util.Map<String, PositionSnapshot> snapshots = buildPositionSnapshots(transactions);
        java.util.Map<String, Position> cacheBySymbol = positionRepository.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Position::getSymbol,
                        p -> p,
                        (left, right) -> left
                ));

        return snapshots.entrySet().stream()
                .filter(e -> e.getValue().quantity.compareTo(BigDecimal.ZERO) > 0)
                .sorted(java.util.Map.Entry.comparingByKey())
                .map(entry -> {
                    String symbol = entry.getKey();
                    PositionSnapshot snapshot = entry.getValue();
                    BigDecimal quantity = snapshot.quantity.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
                    BigDecimal averageCost = snapshot.averageCost.setScale(4, RoundingMode.HALF_UP);
                    BigDecimal costBasis = quantity.multiply(averageCost).setScale(4, RoundingMode.HALF_UP);

                    Position cache = cacheBySymbol.get(symbol);
                    BigDecimal latestPrice = cache == null ? null : effectiveLatestPrice(cache);
                    BigDecimal latestPe = cache == null ? null : effectiveLatestPe(cache);
                    BigDecimal marketValue = latestPrice == null
                            ? null
                            : quantity.multiply(latestPrice).setScale(4, RoundingMode.HALF_UP);
                    BigDecimal unrealizedPnl = marketValue == null
                            ? null
                            : marketValue.subtract(costBasis).setScale(4, RoundingMode.HALF_UP);

                    return new HoldingResponse(
                            symbol,
                            quantity,
                            averageCost,
                            costBasis,
                            latestPrice,
                            latestPe,
                            null,
                            marketValue,
                            unrealizedPnl
                    );
                })
                .toList();
    }

    public DividendResponse recordDividend(DividendRequest request) {
        Dividend dividend = new Dividend();
        dividend.setSymbol(normalizeSymbol(request.symbol()));
        dividend.setAmount(request.amount().setScale(4, RoundingMode.HALF_UP));
        dividend.setPaidDate(request.paidDate());

        Dividend saved = dividendRepository.save(dividend);
        return toDividendResponse(saved);
    }

    public DividendResponse updateDividend(Long dividendId, DividendRequest request) {
        Dividend dividend = dividendRepository.findById(dividendId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Dividend not found"));
        dividend.setSymbol(normalizeSymbol(request.symbol()));
        dividend.setAmount(request.amount().setScale(4, RoundingMode.HALF_UP));
        dividend.setPaidDate(request.paidDate());
        return toDividendResponse(dividendRepository.save(dividend));
    }

    public void deleteDividend(Long dividendId) {
        Dividend dividend = dividendRepository.findById(dividendId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Dividend not found"));
        dividendRepository.delete(dividend);
    }

    @Transactional(readOnly = true)
    public List<DividendResponse> listDividends() {
        return dividendRepository.findAllByOrderByPaidDateAscIdAsc()
                .stream()
                .map(this::toDividendResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<StockNoteResponse> listStockNotes() {
        return stockNoteRepository.findAllByOrderBySymbolAsc()
                .stream()
                .map(this::toStockNoteResponse)
                .toList();
    }

    public StockNoteResponse upsertStockNote(String symbolRaw, StockNoteRequest request) {
        String symbol = normalizeSymbol(symbolRaw);
        String note = normalizeNote(request.note());

        com.stockportfolio.model.StockNote stockNote = stockNoteRepository.findBySymbolIgnoreCase(symbol)
                .orElseGet(com.stockportfolio.model.StockNote::new);
        stockNote.setSymbol(symbol);
        stockNote.setNote(note == null ? "" : note);

        com.stockportfolio.model.StockNote saved = stockNoteRepository.save(stockNote);
        return toStockNoteResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<FundamentalNoteResponse> listFundamentalNotes() {
        return fundamentalNoteRepository.findAllByOrderBySymbolAsc()
                .stream()
                .map(this::toFundamentalNoteResponse)
                .toList();
    }

    public FundamentalNoteResponse upsertFundamentalNote(String symbolRaw, FundamentalNoteRequest request) {
        String symbol = normalizeSymbol(symbolRaw);
        String note = normalizeNote(request.note());

        FundamentalNote fundamentalNote = fundamentalNoteRepository.findBySymbolIgnoreCase(symbol)
                .orElseGet(FundamentalNote::new);
        fundamentalNote.setSymbol(symbol);
        fundamentalNote.setNote(note == null ? "" : note);

        FundamentalNote saved = fundamentalNoteRepository.save(fundamentalNote);
        return toFundamentalNoteResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<OverviewNoteResponse> listOverviewNotes() {
        return overviewNoteRepository.findAllByOrderByNoteTypeAsc()
                .stream()
                .map(this::toOverviewNoteResponse)
                .toList();
    }

    public OverviewNoteResponse upsertOverviewNote(OverviewNoteType noteType, OverviewNoteRequest request) {
        if (noteType == null) {
            throw new ResponseStatusException(BAD_REQUEST, "noteType is required");
        }
        String note = normalizeNote(request.note());

        OverviewNote overviewNote = overviewNoteRepository.findByNoteType(noteType)
                .orElseGet(OverviewNote::new);
        overviewNote.setNoteType(noteType);
        overviewNote.setNote(note == null ? "" : note);

        OverviewNote saved = overviewNoteRepository.save(overviewNote);
        return toOverviewNoteResponse(saved);
    }

    public CashAdjustmentResponse recordCashAdjustment(CashAdjustmentRequest request) {
        CashAdjustment adjustment = new CashAdjustment();
        adjustment.setType(request.type());
        adjustment.setAmount(request.amount().setScale(4, RoundingMode.HALF_UP));
        adjustment.setOccurredAt(request.occurredAt() == null ? OffsetDateTime.now() : request.occurredAt());
        adjustment.setTransactionId(null);
        CashAdjustment saved = cashAdjustmentRepository.save(adjustment);
        return toCashAdjustmentResponse(saved);
    }

    public CashAdjustmentResponse updateCashAdjustment(Long adjustmentId, CashAdjustmentRequest request) {
        CashAdjustment adjustment = findManualCashAdjustment(adjustmentId);
        adjustment.setType(request.type());
        adjustment.setAmount(request.amount().setScale(4, RoundingMode.HALF_UP));
        adjustment.setOccurredAt(request.occurredAt() == null ? adjustment.getOccurredAt() : request.occurredAt());
        return toCashAdjustmentResponse(cashAdjustmentRepository.save(adjustment));
    }

    public void deleteCashAdjustment(Long adjustmentId) {
        CashAdjustment adjustment = findManualCashAdjustment(adjustmentId);
        cashAdjustmentRepository.delete(adjustment);
    }

    @Transactional(readOnly = true)
    public List<CashAdjustmentResponse> listCashAdjustments() {
        return cashAdjustmentRepository.findAllByOrderByOccurredAtAscIdAsc()
                .stream()
                .map(this::toCashAdjustmentResponse)
                .toList();
    }

    public CashAdjustmentCsvImportResponse importCashAdjustmentsFromCsv(MultipartFile file) {
        int totalRows = 0;
        int importedRows = 0;
        int skippedRows = 0;
        int failedRows = 0;
        List<String> sampleErrors = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            if (line == null) {
                return new CashAdjustmentCsvImportResponse(0, 0, 0, 0, sampleErrors);
            }

            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    skippedRows++;
                    continue;
                }

                totalRows++;
                List<String> cells = parseCsvLine(line);
                if (cells.size() < 3) {
                    failedRows++;
                    if (sampleErrors.size() < 10) sampleErrors.add("Row " + lineNumber + ": expected occurredAt, type, amount");
                    continue;
                }

                try {
                    CashAdjustmentType type = CashAdjustmentType.valueOf(cells.get(1).trim().toUpperCase());
                    BigDecimal amount = new BigDecimal(cells.get(2).trim());
                    recordCashAdjustment(new CashAdjustmentRequest(type, amount, parseCashOccurredAt(cells.get(0).trim())));
                    importedRows++;
                } catch (Exception e) {
                    failedRows++;
                    if (sampleErrors.size() < 10) sampleErrors.add("Row " + lineNumber + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new ResponseStatusException(BAD_REQUEST, "Unable to read CSV file: " + e.getMessage());
        }

        return new CashAdjustmentCsvImportResponse(totalRows, importedRows, skippedRows, failedRows, sampleErrors);
    }

    public DividendCsvImportResponse importDividendsFromCsv(MultipartFile file) {
        int totalRows = 0;
        int importedRows = 0;
        int skippedRows = 0;
        int failedRows = 0;
        List<String> sampleErrors = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line = reader.readLine(); // header
            if (line == null) {
                return new DividendCsvImportResponse(totalRows, importedRows, skippedRows, failedRows, sampleErrors);
            }

            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;

                if (line.isBlank()) {
                    skippedRows++;
                    continue;
                }

                totalRows++;
                List<String> cells = parseCsvLine(line);
                if (cells.size() < 6) {
                    failedRows++;
                    if (sampleErrors.size() < 10) {
                        sampleErrors.add("Row " + lineNumber + ": invalid column count");
                    }
                    continue;
                }

                try {
                    String symbol = extractSymbolFromRaw(cells.get(0).trim());
                    String side = cells.get(1).trim();
                    if (!"DIVIDEND".equalsIgnoreCase(side)) {
                        skippedRows++;
                        continue;
                    }

                    BigDecimal amount = new BigDecimal(cells.get(2).trim());
                    LocalDate paidDate = LocalDate.parse(cells.get(5).trim());
                    recordDividend(new DividendRequest(symbol, amount, paidDate));
                    importedRows++;
                } catch (Exception e) {
                    failedRows++;
                    if (sampleErrors.size() < 10) {
                        sampleErrors.add("Row " + lineNumber + ": " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            throw new ResponseStatusException(BAD_REQUEST, "Unable to read CSV file: " + e.getMessage());
        }

        return new DividendCsvImportResponse(totalRows, importedRows, skippedRows, failedRows, sampleErrors);
    }

    @Transactional(readOnly = true)
    public List<MonthlyDividendResponse> getMonthlyDividends() {
        java.util.Map<YearMonth, BigDecimal> grouped = new java.util.TreeMap<>();
        for (Dividend dividend : dividendRepository.findAllByOrderByPaidDateAscIdAsc()) {
            YearMonth key = YearMonth.from(dividend.getPaidDate());
            grouped.put(
                    key,
                    grouped.getOrDefault(key, BigDecimal.ZERO)
                            .add(dividend.getAmount())
                            .setScale(4, RoundingMode.HALF_UP)
            );
        }

        return grouped.entrySet().stream()
                .map(e -> new MonthlyDividendResponse(e.getKey().toString(), e.getValue()))
                .toList();
    }

    @Transactional(readOnly = true)
    public PortfolioSummaryResponse getSummary() {
        List<Transaction> transactions = transactionRepository.findAllByOrderByExecutedAtAscIdAsc();
        java.util.Map<String, Position> cacheBySymbol = positionRepository.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Position::getSymbol,
                        p -> p,
                        (left, right) -> left
                ));

        PortfolioMetrics metrics = calculatePortfolioMetrics(cacheBySymbol, transactions);
        java.util.Map<String, PositionSnapshot> snapshots = buildPositionSnapshots(transactions);
        int currentHoldings = (int) snapshots.values().stream()
                .filter(s -> s.quantity.compareTo(BigDecimal.ZERO) > 0)
                .count();
        int trackedSymbols = (int) transactions.stream()
                .map(Transaction::getSymbol)
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .count();

        return new PortfolioSummaryResponse(
                trackedSymbols,
                trackedSymbols,
                currentHoldings,
                metrics.totalCostBasis(),
                metrics.totalUnits(),
                metrics.totalMarketValue(),
                metrics.totalUnrealizedPnl(),
                metrics.totalRealizedGain()
        );
    }

    @Transactional(readOnly = true)
    public PortfolioExportResponse exportPortfolio() {
        List<Transaction> transactions = transactionRepository.findAllByOrderByExecutedAtAscIdAsc();
        java.util.Map<String, Position> cacheBySymbol = positionRepository.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Position::getSymbol,
                        p -> p,
                        (left, right) -> left
                ));
        java.util.Map<String, BigDecimal> dividendBySymbol = dividendRepository.findAllByOrderByPaidDateAscIdAsc()
                .stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        Dividend::getSymbol,
                        java.util.stream.Collectors.reducing(
                                BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP),
                                Dividend::getAmount,
                                (left, right) -> left.add(right).setScale(4, RoundingMode.HALF_UP)
                        )
                ));
        java.util.Map<String, String> noteBySymbol = stockNoteRepository.findAllByOrderBySymbolAsc()
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        com.stockportfolio.model.StockNote::getSymbol,
                        com.stockportfolio.model.StockNote::getNote,
                        (left, right) -> left
                ));

        PortfolioMetrics metrics = calculatePortfolioMetrics(cacheBySymbol, transactions);
        BigDecimal totalAssets = getAssetCurve().stream()
                .reduce((first, second) -> second)
                .map(AssetCurvePointResponse::totalAssets)
                .orElse(metrics.totalMarketValue())
                .setScale(4, RoundingMode.HALF_UP);
        BigDecimal totalMarketValue = metrics.totalMarketValue();

        List<PortfolioExportHoldingResponse> holdings = buildPositionSnapshots(transactions).entrySet()
                .stream()
                .filter(entry -> entry.getValue().quantity.compareTo(BigDecimal.ZERO) > 0)
                .sorted(java.util.Map.Entry.comparingByKey())
                .map(entry -> {
                    String symbol = entry.getKey();
                    PositionSnapshot snapshot = entry.getValue();
                    Position position = cacheBySymbol.get(symbol);
                    BigDecimal latestPrice = position == null ? null : effectiveLatestPrice(position);
                    BigDecimal latestPe = position == null ? null : effectiveLatestPe(position);
                    BigDecimal marketPrice = latestPrice == null ? snapshot.averageCost : latestPrice;
                    BigDecimal costBasis = snapshot.costBasis();
                    BigDecimal marketValue = snapshot.quantity.multiply(marketPrice).setScale(4, RoundingMode.HALF_UP);
                    BigDecimal unrealizedPnl = marketValue.subtract(costBasis).setScale(4, RoundingMode.HALF_UP);
                    BigDecimal dividendIncome = dividendBySymbol
                            .getOrDefault(symbol, BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP))
                            .setScale(4, RoundingMode.HALF_UP);

                    return new PortfolioExportHoldingResponse(
                            symbol,
                            snapshot.quantity.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP),
                            latestPrice == null ? null : latestPrice.setScale(4, RoundingMode.HALF_UP),
                            marketValue,
                            percent(marketValue, totalMarketValue),
                            unrealizedPnl,
                            percent(unrealizedPnl, costBasis),
                            latestPe == null ? null : latestPe.setScale(4, RoundingMode.HALF_UP),
                            dividendIncome,
                            percent(dividendIncome, costBasis),
                            noteBySymbol.getOrDefault(symbol, "")
                    );
                })
                .toList();

        return new PortfolioExportResponse(
                OffsetDateTime.now(),
                "USD",
                new PortfolioExportSummaryResponse(totalAssets, totalMarketValue, metrics.totalCostBasis()),
                holdings
        );
    }

    @Transactional(readOnly = true)
    public PortfolioExportV2Response exportPortfolioV2() {
        List<Transaction> transactions = transactionRepository.findAllByOrderByExecutedAtAscIdAsc();
        java.util.Map<String, Position> cacheBySymbol = positionRepository.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Position::getSymbol,
                        p -> p,
                        (left, right) -> left
                ));
        java.util.Map<String, BigDecimal> dividendBySymbol = dividendIncomeBySymbol();
        java.util.Map<String, BigDecimal> realizedBySymbol = realizedGainBySymbol(transactions);
        java.util.Map<String, String> noteBySymbol = stockNoteRepository.findAllByOrderBySymbolAsc()
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        com.stockportfolio.model.StockNote::getSymbol,
                        com.stockportfolio.model.StockNote::getNote,
                        (left, right) -> left
                ));

        PortfolioMetrics metrics = calculatePortfolioMetrics(cacheBySymbol, transactions);
        BigDecimal totalAssets = getAssetCurve().stream()
                .reduce((first, second) -> second)
                .map(AssetCurvePointResponse::totalAssets)
                .orElse(metrics.totalMarketValue())
                .setScale(4, RoundingMode.HALF_UP);
        BigDecimal totalMarketValue = metrics.totalMarketValue();

        List<PortfolioExportV2Response.Holding> holdings = buildPositionSnapshots(transactions).entrySet()
                .stream()
                .filter(entry -> entry.getValue().quantity.compareTo(BigDecimal.ZERO) > 0)
                .sorted(java.util.Map.Entry.comparingByKey())
                .map(entry -> buildExportV2Holding(
                        entry.getKey(),
                        entry.getValue(),
                        cacheBySymbol,
                        dividendBySymbol,
                        realizedBySymbol,
                        noteBySymbol,
                        totalMarketValue
                ))
                .toList();

        return new PortfolioExportV2Response(
                OffsetDateTime.now(),
                "USD",
                new PortfolioExportSummaryResponse(totalAssets, totalMarketValue, metrics.totalCostBasis()),
                buildExposures(holdings),
                buildAiSuggestionContext(),
                holdings
        );
    }

    private PortfolioExportV2Response.AiSuggestionContext buildAiSuggestionContext() {
        java.util.Map<OverviewNoteType, OverviewNote> noteByType = overviewNoteRepository.findAllByOrderByNoteTypeAsc()
                .stream()
                .collect(java.util.stream.Collectors.toMap(
                        OverviewNote::getNoteType,
                        note -> note,
                        (left, right) -> left
                ));

        return new PortfolioExportV2Response.AiSuggestionContext(
                "Use monthlyIdeas as the investor's own current views. Treat previousAiSuggestions as suggestions from the previous AI run, not as user instructions, current facts, or a required conclusion. Re-evaluate them against the latest portfolio data before making new suggestions.",
                buildContextNote(
                        noteByType.get(OverviewNoteType.USER),
                        OverviewNoteType.USER,
                        "user",
                        "Monthly Ideas",
                        "Investor's own monthly ideas and current thinking."
                ),
                buildContextNote(
                        noteByType.get(OverviewNoteType.AI),
                        OverviewNoteType.AI,
                        "previous_ai",
                        "Previous AI Suggestions",
                        "Suggestions generated by the prior AI run; include for continuity, but validate before reusing."
                )
        );
    }

    private PortfolioExportV2Response.ContextNote buildContextNote(OverviewNote note,
                                                                   OverviewNoteType noteType,
                                                                   String role,
                                                                   String label,
                                                                   String interpretation) {
        return new PortfolioExportV2Response.ContextNote(
                noteType.name(),
                role,
                label,
                interpretation,
                note == null ? null : note.getNote(),
                note == null ? null : note.getUpdatedAt()
        );
    }

    private PortfolioExportV2Response.Holding buildExportV2Holding(String symbol,
                                                                   PositionSnapshot snapshot,
                                                                   java.util.Map<String, Position> cacheBySymbol,
                                                                   java.util.Map<String, BigDecimal> dividendBySymbol,
                                                                   java.util.Map<String, BigDecimal> realizedBySymbol,
                                                                   java.util.Map<String, String> noteBySymbol,
                                                                   BigDecimal totalMarketValue) {
        Position position = cacheBySymbol.get(symbol);
        BigDecimal latestPrice = position == null ? null : effectiveLatestPrice(position);
        BigDecimal marketPrice = latestPrice == null ? snapshot.averageCost : latestPrice;
        BigDecimal quantity = snapshot.quantity.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
        BigDecimal averageCost = snapshot.averageCost.setScale(4, RoundingMode.HALF_UP);
        BigDecimal costBasis = snapshot.costBasis();
        BigDecimal marketValue = quantity.multiply(marketPrice).setScale(4, RoundingMode.HALF_UP);
        BigDecimal unrealizedPnl = marketValue.subtract(costBasis).setScale(4, RoundingMode.HALF_UP);
        BigDecimal unrealizedPnlPct = percent(unrealizedPnl, costBasis);
        BigDecimal dividendIncome = dividendBySymbol.getOrDefault(symbol, BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP));
        BigDecimal realizedPnl = realizedBySymbol.getOrDefault(symbol, BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP));
        BigDecimal optionPremiumIncome = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        BigDecimal totalReturn = unrealizedPnl.add(realizedPnl).add(dividendIncome).add(optionPremiumIncome).setScale(4, RoundingMode.HALF_UP);
        OptionSymbol optionSymbol = parseOptionSymbol(symbol).orElse(null);
        PortfolioExportV2Response.OptionDetails optionDetails = optionSymbol == null
                ? null
                : buildOptionDetails(optionSymbol, quantity, latestPrice, costBasis, cacheBySymbol);
        PortfolioExportV2Response.Classification classification = buildClassification(position, optionSymbol);
        FundamentalSnapshot fundamentals = latestFundamentalSnapshot(symbol);
        PortfolioExportV2Response.Valuation valuation = buildValuation(symbol, position, latestPrice, fundamentals);
        List<String> missingFields = new ArrayList<>();
        addMissingClassificationFields(missingFields, classification);
        addMissingValuationFields(missingFields, valuation);
        addMissingFundamentalFields(missingFields, fundamentals.response());
        if (optionSymbol != null) {
            missingFields.add("delta");
            missingFields.add("theta");
            missingFields.add("impliedVolatility");
        }

        return new PortfolioExportV2Response.Holding(
                symbol,
                new PortfolioExportV2Response.Position(quantity, averageCost, costBasis),
                new PortfolioExportV2Response.Market(
                        latestPrice == null ? null : latestPrice.setScale(4, RoundingMode.HALF_UP),
                        position == null ? null : position.getPriceUpdatedAt(),
                        "USD"
                ),
                new PortfolioExportV2Response.Computed(
                        marketValue,
                        percent(marketValue, totalMarketValue),
                        unrealizedPnl,
                        unrealizedPnlPct
                ),
                classification,
                valuation,
                fundamentals.response(),
                new PortfolioExportV2Response.Performance(
                        unrealizedPnl,
                        unrealizedPnlPct,
                        realizedPnl,
                        dividendIncome.setScale(4, RoundingMode.HALF_UP),
                        optionPremiumIncome,
                        totalReturn,
                        percent(totalReturn, costBasis)
                ),
                optionDetails,
                new PortfolioExportV2Response.DataQuality(
                        latestPrice == null ? null : "yahoo_finance",
                        fundamentals.response().fundamentalsAsOf() == null ? null : "yahoo_finance",
                        "manual",
                        position == null ? null : position.getPriceUpdatedAt(),
                        fundamentals.updatedAt(),
                        hasStaleData(position == null ? null : position.getPriceUpdatedAt(), fundamentals.updatedAt()),
                        missingFields
                ),
                noteBySymbol.getOrDefault(symbol, "")
        );
    }

    private PortfolioExportV2Response.Exposures buildExposures(List<PortfolioExportV2Response.Holding> holdings) {
        return new PortfolioExportV2Response.Exposures(
                exposureBy(holdings, holding -> holding.classification().assetClass()),
                exposureBy(holdings, holding -> holding.classification().sector()),
                exposureBy(holdings, holding -> holding.classification().region())
        );
    }

    private java.util.Map<String, BigDecimal> exposureBy(List<PortfolioExportV2Response.Holding> holdings,
                                                         Function<PortfolioExportV2Response.Holding, String> classifier) {
        java.util.Map<String, BigDecimal> result = new java.util.TreeMap<>();
        for (PortfolioExportV2Response.Holding holding : holdings) {
            String key = classifier.apply(holding);
            if (key == null || key.isBlank()) {
                key = "UNKNOWN";
            }
            BigDecimal weight = holding.computed().weightPct() == null
                    ? BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)
                    : holding.computed().weightPct();
            result.put(key, result.getOrDefault(key, BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)).add(weight).setScale(4, RoundingMode.HALF_UP));
        }
        return result;
    }

    private PortfolioExportV2Response.Classification buildClassification(Position position, OptionSymbol optionSymbol) {
        String inferredAssetClass = optionSymbol == null ? null : "equity_option";
        String inferredInstrumentType = optionSymbol == null ? null : ("call".equals(optionSymbol.type()) ? "call_option" : "put_option");
        String inferredUnderlying = optionSymbol == null ? null : optionSymbol.underlying();
        return new PortfolioExportV2Response.Classification(
                chooseMetadata(position == null ? null : effectiveAssetClass(position), inferredAssetClass),
                chooseMetadata(position == null ? null : effectiveInstrumentType(position), inferredInstrumentType),
                chooseMetadata(position == null ? null : effectiveUnderlying(position), inferredUnderlying),
                position == null ? null : effectiveSector(position),
                position == null ? null : effectiveRegion(position)
        );
    }

    private PortfolioExportV2Response.Valuation buildValuation(String symbol, Position position, BigDecimal latestPrice, FundamentalSnapshot fundamentals) {
        BigDecimal peTtm = position == null ? null : effectiveLatestPe(position);
        BigDecimal annualForwardEps = resolveCurrentAnnualForwardEps(symbol);
        BigDecimal peForward = dividePriceByEps(latestPrice, annualForwardEps);
        BigDecimal earningsYieldPct = peTtm == null || peTtm.compareTo(BigDecimal.ZERO) <= 0
                ? null
                : BigDecimal.valueOf(100).divide(peTtm, 4, RoundingMode.HALF_UP);
        return new PortfolioExportV2Response.Valuation(
                peTtm == null ? null : peTtm.setScale(4, RoundingMode.HALF_UP),
                peForward,
                earningsYieldPct,
                fundamentals.response().fundamentalsAsOf()
        );
    }

    private FundamentalSnapshot latestFundamentalSnapshot(String symbol) {
        LocalDate today = LocalDate.now(marketZone);
        List<EarningsHistory> rows = earningsHistoryRepository
                .findBySymbolAndAsOfDateBetweenOrderByAsOfDateAsc(symbol, today.minusYears(2), today);
        if (rows.isEmpty()) {
            return new FundamentalSnapshot(new PortfolioExportV2Response.Fundamentals(null, null, null, null, null, null, null), null);
        }
        EarningsHistory latest = rows.get(rows.size() - 1);
        EarningsHistory previousYear = rows.stream()
                .filter(row -> row.getAsOfDate().isBefore(latest.getAsOfDate().minusMonths(9)))
                .reduce((first, second) -> second)
                .orElse(null);
        BigDecimal revenueGrowth = previousYear == null ? null : growthPct(latest.getRevenue(), previousYear.getRevenue());
        BigDecimal debtToEquity = latest.getTotalDebt() == null || latest.getStockholdersEquity() == null
                || latest.getStockholdersEquity().compareTo(BigDecimal.ZERO) <= 0
                ? null
                : latest.getTotalDebt().divide(latest.getStockholdersEquity(), 4, RoundingMode.HALF_UP);
        BigDecimal freeCashFlow = choose(latest.getAdjustedFcf(), latest.getFcf());
        return new FundamentalSnapshot(
                new PortfolioExportV2Response.Fundamentals(
                        scale(revenueGrowth),
                        scale(latest.getGrossMargin()),
                        scale(latest.getRoe()),
                        scale(latest.getRoic()),
                        debtToEquity,
                        scale(freeCashFlow),
                        latest.getAsOfDate()
                ),
                latest.getCapturedAt()
        );
    }

    private BigDecimal growthPct(BigDecimal current, BigDecimal previous) {
        if (current == null || previous == null || previous.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        return current.subtract(previous)
                .divide(previous.abs(), 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    private PortfolioExportV2Response.OptionDetails buildOptionDetails(OptionSymbol optionSymbol,
                                                                       BigDecimal quantity,
                                                                       BigDecimal optionPrice,
                                                                       BigDecimal costBasis,
                                                                       java.util.Map<String, Position> cacheBySymbol) {
        BigDecimal contractSize = BigDecimal.valueOf(100).setScale(4, RoundingMode.HALF_UP);
        BigDecimal contracts = quantity.divide(contractSize, 4, RoundingMode.HALF_UP);
        Position underlyingPosition = cacheBySymbol.get(optionSymbol.underlying());
        BigDecimal underlyingPrice = underlyingPosition == null ? null : effectiveLatestPrice(underlyingPosition);
        BigDecimal notionalExposure = underlyingPrice == null
                ? null
                : quantity.multiply(underlyingPrice).setScale(4, RoundingMode.HALF_UP);
        BigDecimal intrinsicPerShare = null;
        BigDecimal moneyness = null;
        if (underlyingPrice != null) {
            intrinsicPerShare = "call".equals(optionSymbol.type())
                    ? underlyingPrice.subtract(optionSymbol.strike()).max(BigDecimal.ZERO)
                    : optionSymbol.strike().subtract(underlyingPrice).max(BigDecimal.ZERO);
            moneyness = underlyingPrice.divide(optionSymbol.strike(), 4, RoundingMode.HALF_UP);
        }
        BigDecimal intrinsicValue = intrinsicPerShare == null
                ? null
                : intrinsicPerShare.multiply(quantity).setScale(4, RoundingMode.HALF_UP);
        BigDecimal marketValue = optionPrice == null
                ? null
                : optionPrice.multiply(quantity).setScale(4, RoundingMode.HALF_UP);
        BigDecimal timeValue = marketValue == null || intrinsicValue == null
                ? null
                : marketValue.subtract(intrinsicValue).max(BigDecimal.ZERO).setScale(4, RoundingMode.HALF_UP);
        return new PortfolioExportV2Response.OptionDetails(
                optionSymbol.type(),
                optionSymbol.strike(),
                optionSymbol.expiration(),
                contractSize,
                contracts.setScale(4, RoundingMode.HALF_UP),
                ChronoUnit.DAYS.between(LocalDate.now(marketZone), optionSymbol.expiration()),
                moneyness,
                intrinsicValue,
                timeValue,
                notionalExposure,
                costBasis
        );
    }

    private Optional<OptionSymbol> parseOptionSymbol(String symbol) {
        Matcher matcher = OCC_OPTION_SYMBOL.matcher(symbol);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        LocalDate expiration;
        try {
            expiration = LocalDate.parse(matcher.group(2), DateTimeFormatter.ofPattern("yyMMdd"));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
        BigDecimal strike = new BigDecimal(matcher.group(4)).divide(BigDecimal.valueOf(1000), 4, RoundingMode.HALF_UP);
        return Optional.of(new OptionSymbol(
                matcher.group(1),
                expiration,
                "C".equals(matcher.group(3)) ? "call" : "put",
                strike
        ));
    }

    private java.util.Map<String, BigDecimal> dividendIncomeBySymbol() {
        return dividendRepository.findAllByOrderByPaidDateAscIdAsc()
                .stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        Dividend::getSymbol,
                        java.util.stream.Collectors.reducing(
                                BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP),
                                Dividend::getAmount,
                                (left, right) -> left.add(right).setScale(4, RoundingMode.HALF_UP)
                        )
                ));
    }

    private java.util.Map<String, BigDecimal> realizedGainBySymbol(List<Transaction> transactions) {
        java.util.Map<String, PositionSnapshot> snapshots = new java.util.HashMap<>();
        java.util.Map<String, BigDecimal> realized = new java.util.HashMap<>();
        for (Transaction transaction : transactions) {
            PositionSnapshot snapshot = snapshots.computeIfAbsent(transaction.getSymbol(), s -> new PositionSnapshot());
            if (transaction.getType() == TransactionType.SELL) {
                BigDecimal gain = transaction.getQuantity()
                        .multiply(transaction.getPrice().subtract(snapshot.averageCost))
                        .setScale(4, RoundingMode.HALF_UP);
                realized.put(transaction.getSymbol(), realized.getOrDefault(transaction.getSymbol(), BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP)).add(gain).setScale(4, RoundingMode.HALF_UP));
            }
            applyTransactionToSnapshot(snapshot, transaction);
        }
        return realized;
    }

    private void addMissingClassificationFields(List<String> missingFields, PortfolioExportV2Response.Classification classification) {
        if (classification.assetClass() == null) missingFields.add("assetClass");
        if (classification.instrumentType() == null) missingFields.add("instrumentType");
        if (classification.sector() == null) missingFields.add("sector");
        if (classification.region() == null) missingFields.add("region");
    }

    private void addMissingValuationFields(List<String> missingFields, PortfolioExportV2Response.Valuation valuation) {
        if (valuation.peTtm() == null) missingFields.add("peTtm");
        if (valuation.peForward() == null) missingFields.add("peForward");
        if (valuation.earningsYieldPct() == null) missingFields.add("earningsYieldPct");
    }

    private void addMissingFundamentalFields(List<String> missingFields, PortfolioExportV2Response.Fundamentals fundamentals) {
        if (fundamentals.revenueGrowthYoYPct() == null) missingFields.add("revenueGrowthYoYPct");
        if (fundamentals.grossMarginPct() == null) missingFields.add("grossMarginPct");
        if (fundamentals.roePct() == null) missingFields.add("roePct");
        if (fundamentals.roicPct() == null) missingFields.add("roicPct");
        if (fundamentals.debtToEquity() == null) missingFields.add("debtToEquity");
        if (fundamentals.freeCashFlow() == null) missingFields.add("freeCashFlow");
    }

    private boolean hasStaleData(OffsetDateTime priceUpdatedAt, OffsetDateTime fundamentalUpdatedAt) {
        OffsetDateTime now = OffsetDateTime.now();
        return priceUpdatedAt == null
                || priceUpdatedAt.isBefore(now.minusDays(7))
                || (fundamentalUpdatedAt != null && fundamentalUpdatedAt.isBefore(now.minusDays(120)));
    }

    private String chooseMetadata(String manual, String inferred) {
        return manual == null || manual.isBlank() ? inferred : manual;
    }

    private String cleanMetadataText(String value, boolean uppercase) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return uppercase ? trimmed.toUpperCase() : trimmed;
    }

    @Transactional(readOnly = true)
    private PortfolioMetrics calculatePortfolioMetrics(java.util.Map<String, Position> cacheBySymbol,
                                                       List<Transaction> transactions) {
        java.util.Map<String, PositionSnapshot> snapshots = new java.util.HashMap<>();
        BigDecimal totalRealizedGain = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);

        for (Transaction transaction : transactions) {
            PositionSnapshot snapshot = snapshots.computeIfAbsent(transaction.getSymbol(), s -> new PositionSnapshot());
            if (transaction.getType() == TransactionType.BUY) {
                applyTransactionToSnapshot(snapshot, transaction);
                continue;
            }

            BigDecimal sellQuantity = transaction.getQuantity();
            BigDecimal realizedGain = sellQuantity
                    .multiply(transaction.getPrice().subtract(snapshot.averageCost))
                    .setScale(4, RoundingMode.HALF_UP);
            totalRealizedGain = totalRealizedGain.add(realizedGain).setScale(4, RoundingMode.HALF_UP);
            applyTransactionToSnapshot(snapshot, transaction);
        }

        BigDecimal totalUnits = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        BigDecimal totalCostBasis = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        BigDecimal totalMarketValue = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);

        for (java.util.Map.Entry<String, PositionSnapshot> entry : snapshots.entrySet()) {
            String symbol = entry.getKey();
            PositionSnapshot snapshot = entry.getValue();
            if (snapshot.quantity.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            BigDecimal costBasis = snapshot.costBasis();
            Position position = cacheBySymbol.get(symbol);
            BigDecimal latestPrice = position == null ? null : effectiveLatestPrice(position);
            BigDecimal marketPrice = latestPrice == null ? snapshot.averageCost : latestPrice;
            BigDecimal marketValue = snapshot.quantity.multiply(marketPrice).setScale(4, RoundingMode.HALF_UP);

            totalUnits = totalUnits.add(snapshot.quantity).setScale(4, RoundingMode.HALF_UP);
            totalCostBasis = totalCostBasis.add(costBasis).setScale(4, RoundingMode.HALF_UP);
            totalMarketValue = totalMarketValue.add(marketValue).setScale(4, RoundingMode.HALF_UP);
        }

        BigDecimal totalUnrealizedPnl = totalMarketValue.subtract(totalCostBasis).setScale(4, RoundingMode.HALF_UP);
        return new PortfolioMetrics(totalCostBasis, totalUnits, totalMarketValue, totalUnrealizedPnl, totalRealizedGain);
    }

    public PriceRefreshResponse refreshPrices(String trigger) {
        // Keep refresh, close sync, scheduled jobs, and simultaneous browser
        // sessions from updating the same versioned Position rows at once.
        // The database lock is held through transaction commit.
        positionRepository.findAllForMarketSync();
        List<Position> positions = loadOrCreatePositionCaches(activeSymbolsFromTransactions());
        int updated = 0;

        for (Position position : positions) {
            Optional<YahooFinancePriceService.YahooMarketSnapshot> snapshotOpt = fetchSnapshotWithRetry(position.getSymbol());
            if (snapshotOpt.isEmpty()) {
                continue;
            }

            OffsetDateTime now = OffsetDateTime.now();
            YahooFinancePriceService.YahooMarketSnapshot snapshot = snapshotOpt.get();
            boolean changed = applySnapshotToPosition(position, snapshot, now);
            if (refreshSharesOutstanding(position, now)) {
                changed = true;
            }
            if (applyDerivedPeFromLatestEarnings(position, LocalDate.now(marketZone))) {
                changed = true;
            }
            if (changed) {
                updated++;
            }
        }

        positionRepository.saveAll(positions);
        return new PriceRefreshResponse(positions.size(), updated, trigger);
    }

    public MarketCloseSyncResponse syncMarketClose(String trigger) {
        positionRepository.findAllForMarketSync();
        List<Position> positions = loadOrCreatePositionCaches(activeSymbolsFromTransactions());
        LocalDate tradeDate = LocalDate.now(marketZone);
        OffsetDateTime now = OffsetDateTime.now();

        int successful = 0;
        int failed = 0;
        int skipped = 0;
        int priceWrites = 0;
        int peWrites = 0;

        for (Position position : positions) {
            Optional<YahooFinancePriceService.YahooMarketSnapshot> snapshotOpt = fetchSnapshotWithRetry(position.getSymbol());
            if (snapshotOpt.isEmpty()) {
                failed++;
                continue;
            }

            YahooFinancePriceService.YahooMarketSnapshot snapshot = snapshotOpt.get();
            applySnapshotToPosition(position, snapshot, now);
            refreshSharesOutstanding(position, now);

            if (snapshot.regularMarketTime() == null) {
                skipped++;
                continue;
            }

            LocalDate marketDate = snapshot.regularMarketTime().atZoneSameInstant(marketZone).toLocalDate();
            if (!marketDate.equals(tradeDate)) {
                skipped++;
                continue;
            }

            successful++;

            if (snapshot.regularMarketPrice() != null) {
                upsertPriceHistory(position.getSymbol(), tradeDate, snapshot.regularMarketPrice().setScale(4, RoundingMode.HALF_UP));
                priceWrites++;
            }

            if (applyDerivedPeFromLatestEarnings(position, tradeDate)) {
                peWrites++;
            }
        }

        positionRepository.saveAll(positions);

        return new MarketCloseSyncResponse(
                tradeDate,
                positions.size(),
                successful,
                failed,
                skipped,
                priceWrites,
                peWrites,
                trigger
        );
    }

    public PriceHistoryBackfillResponse backfillPriceHistory(String symbolsCsv, int years, String trigger) {
        if (years < 1 || years > 30) {
            throw new ResponseStatusException(BAD_REQUEST, "years must be between 1 and 30");
        }

        LocalDate toDate = LocalDate.now(marketZone);
        LocalDate fromDate = toDate.minusYears(years);
        List<String> symbols = resolveBackfillSymbols(symbolsCsv);

        int successful = 0;
        int failed = 0;
        int skipped = 0;
        int historyPointsWritten = 0;

        for (String symbol : symbols) {
            try {
                List<YahooFinancePriceService.YahooDailyPricePoint> points =
                        yahooFinancePriceService.fetchDailyCloseHistory(symbol, fromDate, toDate);

                if (points.isEmpty()) {
                    skipped++;
                    continue;
                }

                for (YahooFinancePriceService.YahooDailyPricePoint point : points) {
                    upsertPriceHistory(symbol, point.tradeDate(), point.closePrice().setScale(4, RoundingMode.HALF_UP),
                            point.adjustedClosePrice() == null ? null : point.adjustedClosePrice().setScale(4, RoundingMode.HALF_UP));
                    historyPointsWritten++;
                }
                refreshStockSplitsIfAvailable(symbol, fromDate.minusYears(5), toDate);
                successful++;
            } catch (Exception e) {
                failed++;
            }
        }

        return new PriceHistoryBackfillResponse(
                fromDate,
                toDate,
                years,
                symbols.size(),
                successful,
                failed,
                skipped,
                historyPointsWritten,
                trigger
        );
    }

    public PriceHistoryBackfillResponse backfillPeHistory(String symbolsCsv, int years, String trigger) {
        if (years < 1 || years > 30) {
            throw new ResponseStatusException(BAD_REQUEST, "years must be between 1 and 30");
        }

        LocalDate toDate = LocalDate.now(marketZone);
        LocalDate fromDate = toDate.minusYears(years);
        List<String> symbols = resolveBackfillSymbols(symbolsCsv);

        int successful = 0;
        int failed = 0;
        int skipped = 0;
        int historyPointsWritten = 0;

        for (String symbol : symbols) {
            try {
                LocalDate earningsFrom = fromDate.minusYears(1);
                List<YahooFinancePriceService.QuarterlyFundamentalPoint> yahooRows =
                        yahooFinancePriceService.fetchQuarterlyFundamentalsHistory(symbol, earningsFrom, toDate);
                List<YahooFinancePriceService.QuarterlyFundamentalPoint> secRows =
                        secCompanyFactsService.fetchQuarterlyFundamentalsHistory(symbol, earningsFrom, toDate);
                refreshEarningsEstimatesIfAvailable(symbol);
                refreshStockSplitsIfAvailable(symbol, earningsFrom.minusYears(5), toDate);
                List<YahooFinancePriceService.QuarterlyFundamentalPoint> sortedFundamentals =
                        calculateDerivedMetrics(mergeQuarterlyFundamentals(secRows, yahooRows)).stream()
                        .sorted(Comparator.comparing(YahooFinancePriceService.QuarterlyFundamentalPoint::asOfDate))
                        .toList();

                if (sortedFundamentals.isEmpty()) {
                    skipped++;
                    continue;
                }

                int symbolWrites = 0;

                for (YahooFinancePriceService.QuarterlyFundamentalPoint point : sortedFundamentals) {
                    BigDecimal sourceEps = point.basicEps() == null ? null : point.basicEps().setScale(4, RoundingMode.HALF_UP);
                    BigDecimal epsInQuote = convertEpsToQuoteCurrency(
                            sourceEps,
                            point.currencyCode(),
                            "USD",
                            new java.util.HashMap<>()
                    );

                    upsertEarningsHistory(
                            symbol,
                            point.asOfDate(),
                            sourceEps,
                            point.currencyCode(),
                            epsInQuote,
                            point
                    );
                    historyPointsWritten++;
                    symbolWrites++;
                }

                if (symbolWrites == 0) {
                    skipped++;
                } else {
                    successful++;
                }
            } catch (Exception e) {
                failed++;
            }
        }

        return new PriceHistoryBackfillResponse(
                fromDate,
                toDate,
                years,
                symbols.size(),
                successful,
                failed,
                skipped,
                historyPointsWritten,
                trigger
        );
    }

    public FundamentalBackfillResponse backfillMissingFundamentals(String symbolsCsv, int years, String trigger) {
        if (years < 1 || years > 30) {
            throw new ResponseStatusException(BAD_REQUEST, "years must be between 1 and 30");
        }

        LocalDate toDate = LocalDate.now(marketZone);
        LocalDate fromDate = toDate.minusYears(years);
        LocalDate fetchFromDate = fromDate.minusYears(1);
        List<String> symbols = resolveBackfillSymbols(symbolsCsv);

        int successful = 0;
        int failed = 0;
        int skipped = 0;
        int rowsInserted = 0;
        int rowsUpdated = 0;
        int fieldsFilled = 0;

        for (String symbol : symbols) {
            try {
                List<EarningsHistory> existingRows = earningsHistoryRepository
                        .findBySymbolAndAsOfDateBetweenOrderByAsOfDateAsc(symbol, fetchFromDate, toDate);
                Map<String, EarningsHistory> existingByQuarter = new LinkedHashMap<>();
                for (EarningsHistory row : existingRows) {
                    existingByQuarter.putIfAbsent(quarterKey(row.getAsOfDate()), row);
                }

                List<YahooFinancePriceService.QuarterlyFundamentalPoint> yahooRows =
                        yahooFinancePriceService.fetchQuarterlyFundamentalsHistory(symbol, fetchFromDate, toDate);
                List<YahooFinancePriceService.QuarterlyFundamentalPoint> secRows =
                        secCompanyFactsService.fetchQuarterlyFundamentalsHistory(symbol, fetchFromDate, toDate);
                if (yahooRows.isEmpty() && secRows.isEmpty()) {
                    skipped++;
                    continue;
                }

                refreshEarningsEstimatesIfAvailable(symbol);
                refreshStockSplitsIfAvailable(symbol, fetchFromDate.minusYears(5), toDate);
                List<YahooFinancePriceService.QuarterlyFundamentalPoint> existingPoints = existingRows.stream()
                        .map(this::toQuarterlyFundamentalPoint)
                        .toList();
                List<YahooFinancePriceService.QuarterlyFundamentalPoint> fundamentals =
                        calculateDerivedMetrics(mergeQuarterlyFundamentals(
                                mergeQuarterlyFundamentals(secRows, yahooRows),
                                existingPoints
                        )).stream()
                                .filter(point -> !point.asOfDate().isBefore(fromDate) && !point.asOfDate().isAfter(toDate))
                                .toList();

                java.util.Map<String, Optional<BigDecimal>> fxCache = new java.util.HashMap<>();
                int symbolRowsInserted = 0;
                int symbolRowsUpdated = 0;
                int symbolFieldsFilled = 0;

                for (YahooFinancePriceService.QuarterlyFundamentalPoint point : fundamentals) {
                    EarningsHistory existing = existingByQuarter.get(quarterKey(point.asOfDate()));
                    FundamentalFillStats stats = fillMissingEarningsHistory(symbol, existing, point, fxCache);
                    if (stats.rowsInserted() > 0) {
                        existingByQuarter.put(quarterKey(point.asOfDate()),
                                earningsHistoryRepository.findBySymbolAndAsOfDate(symbol, point.asOfDate()).orElse(null));
                    }
                    symbolRowsInserted += stats.rowsInserted();
                    symbolRowsUpdated += stats.rowsUpdated();
                    symbolFieldsFilled += stats.fieldsFilled();
                }

                if (symbolRowsInserted == 0 && symbolRowsUpdated == 0) {
                    skipped++;
                } else {
                    successful++;
                    rowsInserted += symbolRowsInserted;
                    rowsUpdated += symbolRowsUpdated;
                    fieldsFilled += symbolFieldsFilled;
                }
            } catch (Exception e) {
                failed++;
            }
        }

        return new FundamentalBackfillResponse(
                fromDate,
                toDate,
                years,
                symbols.size(),
                successful,
                failed,
                skipped,
                rowsInserted,
                rowsUpdated,
                fieldsFilled,
                trigger
        );
    }

    @Transactional(readOnly = true)
    public List<PriceHistoryPointResponse> getPriceHistory(String symbol, LocalDate from, LocalDate to) {
        LocalDate toDate = to == null ? LocalDate.now(marketZone) : to;
        LocalDate fromDate = from == null ? toDate.minusMonths(12) : from;

        List<PriceHistory> rows = priceHistoryRepository
                .findBySymbolAndTradeDateBetweenOrderByTradeDateAsc(normalizeSymbol(symbol), fromDate, toDate);
        Map<String, Map<String, Object>> corrections = reviewedDataResolver.correctedValues(
                "market_data", rows.stream().map(PriceHistory::getId).toList()
        );
        Set<String> rejected = reviewedDataResolver.rejectedRecordIds(
                "market_data", rows.stream().map(PriceHistory::getId).toList()
        );
        return rows.stream()
                .filter(row -> !rejected.contains(String.valueOf(row.getId())))
                .map(row -> new PriceHistoryPointResponse(
                        row.getTradeDate(),
                        reviewedDataResolver.decimal(corrections.getOrDefault(String.valueOf(row.getId()), Map.of()), "closePrice", row.getClosePrice()),
                        reviewedDataResolver.decimal(corrections.getOrDefault(String.valueOf(row.getId()), Map.of()), "adjustedClosePrice", row.getAdjustedClosePrice())
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<PeHistoryPointResponse> getPeHistory(String symbol, LocalDate from, LocalDate to) {
        String normalized = normalizeSymbol(symbol);
        LocalDate toDate = to == null ? LocalDate.now(marketZone) : to;
        LocalDate fromDate = from == null ? toDate.minusMonths(12) : from;
        List<PriceHistory> prices = priceHistoryRepository
                .findBySymbolAndTradeDateBetweenOrderByTradeDateAsc(normalized, fromDate, toDate);
        if (prices.isEmpty()) {
            return List.of();
        }

        Map<String, Map<String, Object>> priceCorrections = reviewedDataResolver.correctedValues(
                "market_data", prices.stream().map(PriceHistory::getId).toList()
        );
        Set<String> rejectedPrices = reviewedDataResolver.rejectedRecordIds(
                "market_data", prices.stream().map(PriceHistory::getId).toList()
        );
        prices = prices.stream()
                .filter(price -> !rejectedPrices.contains(String.valueOf(price.getId())))
                .toList();
        if (prices.isEmpty()) {
            return List.of();
        }

        List<EarningsHistory> earnings = earningsHistoryRepository
                .findBySymbolAndAsOfDateLessThanEqualOrderByAsOfDateAsc(normalized, toDate);
        Map<String, Map<String, Object>> earningsCorrections = reviewedDataResolver.correctedValues(
                "fundamentals", earnings.stream().map(EarningsHistory::getId).toList()
        );
        Set<String> rejectedEarnings = reviewedDataResolver.rejectedRecordIds(
                "fundamentals", earnings.stream().map(EarningsHistory::getId).toList()
        );
        earnings = earnings.stream()
                .filter(row -> !rejectedEarnings.contains(String.valueOf(row.getId())))
                .filter(row -> row.getFilingDate() != null)
                .sorted(Comparator.comparing(EarningsHistory::getFilingDate).thenComparing(EarningsHistory::getAsOfDate))
                .toList();
        if (earnings.isEmpty()) {
            return List.of();
        }

        List<PeHistory> metricRows = peHistoryRepository
                .findBySymbolAndTradeDateBetweenOrderByTradeDateAsc(normalized, fromDate, toDate);
        Map<String, Map<String, Object>> metricCorrections = reviewedDataResolver.correctedValues(
                "financial_metrics", metricRows.stream().map(PeHistory::getId).toList()
        );
        Set<String> rejectedMetrics = reviewedDataResolver.rejectedRecordIds(
                "financial_metrics", metricRows.stream().map(PeHistory::getId).toList()
        );
        Map<LocalDate, BigDecimal> correctedTtmByDate = metricRows.stream()
                .filter(metric -> !rejectedMetrics.contains(String.valueOf(metric.getId())))
                .filter(metric -> {
                    Map<String, Object> overrides = metricCorrections.get(String.valueOf(metric.getId()));
                    return overrides != null && overrides.containsKey("trailingPe");
                })
                .collect(java.util.stream.Collectors.toMap(
                        PeHistory::getTradeDate,
                        metric -> reviewedDataResolver.decimal(
                                metricCorrections.get(String.valueOf(metric.getId())), "trailingPe", null
                        ),
                        (left, right) -> right
                ));
        List<NonGaapEpsHistory> nonGaapEps = nonGaapEpsHistoryRepository
                .findBySymbolAndAsOfDateLessThanEqualOrderByAsOfDateAsc(normalized, toDate);
        BigDecimal forwardEpsEstimate = resolveCurrentAnnualForwardEps(normalized);
        LocalDate forwardPeStartDate = earnings.stream()
                .map(EarningsHistory::getAsOfDate)
                .max(LocalDate::compareTo)
                .orElse(null);

        List<PeHistoryPointResponse> points = new ArrayList<>();
        java.util.Map<String, Optional<BigDecimal>> fxCache = new java.util.HashMap<>();
        int epsIndex = 0;
        BigDecimal activeTtmEps = null;
        BigDecimal activeQuarterlyEps = null;
        BigDecimal activeForwardEps = null;
        LocalDate activeEarningsPeriod = null;
        int nonGaapIndex = 0;
        List<BigDecimal> activeNonGaapQuarterlyEps = new ArrayList<>();
        for (PriceHistory price : prices) {
            while (epsIndex < earnings.size() && !earnings.get(epsIndex).getFilingDate().isAfter(price.getTradeDate())) {
                EarningsHistory row = earnings.get(epsIndex);
                Map<String, Object> overrides = earningsCorrections.getOrDefault(String.valueOf(row.getId()), Map.of());
                BigDecimal reviewedTtmEps = reviewedDataResolver.decimal(overrides, "ttmEps", row.getTtmEps());
                if (reviewedTtmEps != null && (activeEarningsPeriod == null || !row.getAsOfDate().isBefore(activeEarningsPeriod))) {
                    activeTtmEps = reviewedTtmEps;
                    activeEarningsPeriod = row.getAsOfDate();
                }
                BigDecimal quarterlyEps = resolveEpsInQuote(row, overrides, "USD", fxCache);
                if (quarterlyEps != null) {
                    activeQuarterlyEps = quarterlyEps;
                }
                epsIndex++;
            }
            while (nonGaapIndex < nonGaapEps.size() && !nonGaapEps.get(nonGaapIndex).getAsOfDate().isAfter(price.getTradeDate())) {
                BigDecimal value = nonGaapEps.get(nonGaapIndex).getNonGaapEps();
                if (value != null) {
                    activeNonGaapQuarterlyEps.add(value);
                    if (activeNonGaapQuarterlyEps.size() > 4) {
                        activeNonGaapQuarterlyEps.remove(0);
                    }
                }
                nonGaapIndex++;
            }

            BigDecimal closePrice = reviewedDataResolver.decimal(
                    priceCorrections.getOrDefault(String.valueOf(price.getId()), Map.of()), "closePrice", price.getClosePrice()
            );
            BigDecimal ttmPe = correctedTtmByDate.getOrDefault(price.getTradeDate(), dividePriceByEps(closePrice, activeTtmEps));
            BigDecimal nonGaapTtmPe = dividePriceByEps(closePrice, sumFullTtm(activeNonGaapQuarterlyEps));
            BigDecimal quarterlyPe = dividePriceByEps(closePrice, annualizeEps(activeQuarterlyEps));
            if (forwardEpsEstimate != null && forwardPeStartDate != null && !price.getTradeDate().isBefore(forwardPeStartDate)) {
                activeForwardEps = forwardEpsEstimate;
            }
            BigDecimal forwardPe = dividePriceByEps(closePrice, activeForwardEps);
            if (ttmPe == null && nonGaapTtmPe == null && quarterlyPe == null && forwardPe == null) {
                continue;
            }
            points.add(new PeHistoryPointResponse(price.getTradeDate(), ttmPe, nonGaapTtmPe, quarterlyPe, forwardPe,
                    peStatus(ttmPe, activeTtmEps), peStatus(nonGaapTtmPe, sumFullTtm(activeNonGaapQuarterlyEps)),
                    peStatus(quarterlyPe, activeQuarterlyEps), peStatus(forwardPe, activeForwardEps), activeEarningsPeriod));
        }

        return points;
    }

    private BigDecimal sumFullTtm(List<BigDecimal> quarterlyEps) {
        if (quarterlyEps.size() < 4) {
            return null;
        }
        return quarterlyEps.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String peStatus(BigDecimal pe, BigDecimal earnings) {
        if (pe != null) return "AVAILABLE";
        if (earnings != null && earnings.compareTo(BigDecimal.ZERO) <= 0) return "NOT_MEANINGFUL";
        return "UNAVAILABLE";
    }

    @Transactional(readOnly = true)
    public List<QuarterlyFundamentalPointResponse> getQuarterlyFundamentals(String symbol, LocalDate from, LocalDate to) {
        String normalized = normalizeSymbol(symbol);
        LocalDate toDate = to == null ? LocalDate.now(marketZone) : to;
        LocalDate fromDate = from == null ? toDate.minusYears(15) : from;
        List<EarningsHistory> rawRows = earningsHistoryRepository
                .findBySymbolAndAsOfDateBetweenOrderByAsOfDateAsc(normalized, fromDate, toDate);
        Map<String, Map<String, Object>> corrections = reviewedDataResolver.correctedValues(
                "fundamentals", rawRows.stream().map(EarningsHistory::getId).toList()
        );
        Set<String> rejected = reviewedDataResolver.rejectedRecordIds(
                "fundamentals", rawRows.stream().map(EarningsHistory::getId).toList()
        );
        List<QuarterlyFundamentalPointResponse> rows = new ArrayList<>(rawRows.stream()
                .filter(row -> !rejected.contains(String.valueOf(row.getId())))
                .map(row -> toQuarterlyFundamentalPointResponse(
                        row,
                        corrections.getOrDefault(String.valueOf(row.getId()), Map.of())
                ))
                .toList());

        LocalDate latestActualDate = rows.stream()
                .filter(row -> !row.forecast())
                .map(QuarterlyFundamentalPointResponse::asOfDate)
                .max(LocalDate::compareTo)
                .orElse(fromDate);
        earningsEstimateRepository.findBySymbolAndPeriodTypeOrderByPeriodEndDateAsc(normalized, "QUARTERLY").stream()
                .filter(estimate -> estimate.getPeriodEndDate().isAfter(latestActualDate))
                .map(this::toForwardEpsEstimateResponse)
                .forEach(rows::add);

        return rows.stream()
                .sorted(Comparator.comparing(QuarterlyFundamentalPointResponse::asOfDate))
                .toList();
    }

    @Transactional(readOnly = true)
    public CapitalAllocationHistoryResponse getCapitalAllocationHistory(String symbol, LocalDate from, LocalDate to) {
        String normalized = normalizeSymbol(symbol);
        LocalDate toDate = to == null ? LocalDate.now(marketZone) : to;
        LocalDate fromDate = from == null ? toDate.minusYears(15) : from;
        List<EarningsHistory> rawRows = earningsHistoryRepository
                .findBySymbolAndAsOfDateBetweenOrderByAsOfDateAsc(normalized, fromDate, toDate);
        Map<String, Map<String, Object>> corrections = reviewedDataResolver.correctedValues(
                "fundamentals", rawRows.stream().map(EarningsHistory::getId).toList()
        );
        Set<String> rejected = reviewedDataResolver.rejectedRecordIds(
                "fundamentals", rawRows.stream().map(EarningsHistory::getId).toList()
        );
        List<EarningsHistory> rows = rawRows.stream()
                .filter(row -> !rejected.contains(String.valueOf(row.getId())))
                .toList();

        List<BigDecimal> amounts = rows.stream()
                .map(row -> reviewedDataResolver.decimal(
                        corrections.getOrDefault(String.valueOf(row.getId()), Map.of()),
                        "shareRepurchases", row.getShareRepurchases()))
                .toList();
        List<CapitalAllocationHistoryResponse.ShareRepurchasePoint> repurchases = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            EarningsHistory row = rows.get(index);
            Map<String, Object> overrides = corrections.getOrDefault(String.valueOf(row.getId()), Map.of());
            BigDecimal amount = amounts.get(index);
            if (amount == null) continue;
            repurchases.add(new CapitalAllocationHistoryResponse.ShareRepurchasePoint(
                    row.getAsOfDate(), amount, trailingFourQuarterSum(amounts, index),
                    capitalAllocationSource(row, overrides, "shareRepurchases")
            ));
        }

        Map<LocalDate, FundamentalFactObservation> latestShareFacts = new LinkedHashMap<>();
        fundamentalFactObservationRepository
                .findBySymbolAndFieldNameAndPeriodEndBetweenOrderByPeriodEndAscSourceDateDesc(
                        normalized, "sharesOutstanding", fromDate, toDate)
                .forEach(observation -> latestShareFacts.putIfAbsent(observation.getPeriodEnd(), observation));
        // A historical point is displayed on today's split basis, so use every later split
        // already cached by the system rather than stopping at the selected history range.
        List<StockSplit> splits = stockSplitRepository.findBySymbolAndSplitDateBetweenOrderBySplitDateAsc(
                normalized, fromDate, LocalDate.now(marketZone));
        List<CapitalAllocationHistoryResponse.SharesOutstandingPoint> shares = latestShareFacts.values().stream()
                .map(observation -> toSharesOutstandingPoint(observation, splits))
                .toList();

        return new CapitalAllocationHistoryResponse(normalized, repurchases, shares);
    }

    private BigDecimal trailingFourQuarterSum(List<BigDecimal> values, int currentIndex) {
        if (currentIndex < 3) return null;
        BigDecimal total = BigDecimal.ZERO;
        for (int index = currentIndex - 3; index <= currentIndex; index++) {
            if (values.get(index) == null) return null;
            total = total.add(values.get(index));
        }
        return total;
    }

    private CapitalAllocationHistoryResponse.SharesOutstandingPoint toSharesOutstandingPoint(
            FundamentalFactObservation observation, List<StockSplit> splits
    ) {
        BigDecimal factor = calculateCurrentSplitAdjustmentFactor(observation.getPeriodEnd(), splits);
        BigDecimal adjusted = observation.getValue() == null ? null : observation.getValue().multiply(factor);
        return new CapitalAllocationHistoryResponse.SharesOutstandingPoint(
                observation.getPeriodEnd(), adjusted, observation.getValue(), factor,
                new FieldSourceResponse("SEC_COMPANY_FACTS", "SEC Company Facts", observation.getSourceDate(), null)
        );
    }

    static BigDecimal calculateCurrentSplitAdjustmentFactor(LocalDate observationDate, List<StockSplit> splits) {
        BigDecimal factor = BigDecimal.ONE;
        for (StockSplit split : splits == null ? List.<StockSplit>of() : splits) {
            if (split.getSplitDate() != null && split.getSplitDate().isAfter(observationDate)
                    && split.getNumerator() != null && split.getDenominator() != null
                    && split.getDenominator().signum() > 0) {
                factor = factor.multiply(split.getNumerator())
                        .divide(split.getDenominator(), 12, RoundingMode.HALF_UP);
            }
        }
        return factor;
    }

    private FieldSourceResponse capitalAllocationSource(EarningsHistory row, Map<String, Object> overrides, String field) {
        if (overrides.containsKey(field)) {
            return new FieldSourceResponse("REVIEWED", "Manual reviewed correction", row.getAsOfDate(), null);
        }
        if (row.getFilingDate() != null) {
            return new FieldSourceResponse("SEC_COMPANY_FACTS", "SEC Company Facts", row.getFilingDate(), null);
        }
        return new FieldSourceResponse("YAHOO_FALLBACK", "Yahoo Finance fallback", row.getAsOfDate(), null);
    }

    @Transactional(readOnly = true)
    public List<AssetCurvePointResponse> getAssetCurve() {
        List<Transaction> transactions = transactionRepository.findAllByOrderByExecutedAtAscIdAsc();
        List<Dividend> dividends = dividendRepository.findAllByOrderByPaidDateAscIdAsc();
        List<CashAdjustment> cashAdjustments = cashAdjustmentRepository.findAllByOrderByOccurredAtAscIdAsc();

        java.util.Set<String> symbols = transactions.stream()
                .map(Transaction::getSymbol)
                .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));

        if (transactions.isEmpty() && dividends.isEmpty() && cashAdjustments.isEmpty()) {
            return List.of();
        }

        java.util.Map<LocalDate, List<Transaction>> txByDate = new java.util.HashMap<>();
        java.util.Set<LocalDate> allDates = new java.util.TreeSet<>();
        for (Transaction transaction : transactions) {
            LocalDate date = transaction.getExecutedAt().atZoneSameInstant(marketZone).toLocalDate();
            txByDate.computeIfAbsent(date, ignored -> new ArrayList<>()).add(transaction);
            allDates.add(date);
        }

        java.util.Map<LocalDate, BigDecimal> dividendsByDate = new java.util.HashMap<>();
        for (Dividend dividend : dividends) {
            LocalDate date = dividend.getPaidDate();
            dividendsByDate.put(date, dividendsByDate.getOrDefault(date, BigDecimal.ZERO).add(dividend.getAmount()));
            allDates.add(date);
        }

        java.util.Map<LocalDate, BigDecimal> cashAdjustmentsByDate = new java.util.HashMap<>();
        for (CashAdjustment adjustment : cashAdjustments) {
            LocalDate date = adjustment.getOccurredAt().atZoneSameInstant(marketZone).toLocalDate();
            BigDecimal signed = signedCashAdjustment(adjustment);
            cashAdjustmentsByDate.put(date, cashAdjustmentsByDate.getOrDefault(date, BigDecimal.ZERO).add(signed));
            allDates.add(date);
        }

        if (allDates.isEmpty()) {
            return List.of();
        }
        LocalDate curveStartDate = allDates.stream().min(LocalDate::compareTo).orElseThrow();

        List<PriceHistory> historyRows = symbols.isEmpty()
                ? List.of()
                : priceHistoryRepository.findAllBySymbolInAndTradeDateGreaterThanEqualOrderByTradeDateAsc(
                        new ArrayList<>(symbols),
                        curveStartDate
                );
        Map<String, Map<String, Object>> priceCorrections = reviewedDataResolver.correctedValues(
                "market_data", historyRows.stream().map(PriceHistory::getId).toList()
        );
        Set<String> rejectedPrices = reviewedDataResolver.rejectedRecordIds(
                "market_data", historyRows.stream().map(PriceHistory::getId).toList()
        );

        java.util.Map<LocalDate, java.util.Map<String, BigDecimal>> closePriceByDateAndSymbol = new java.util.HashMap<>();
        for (PriceHistory row : historyRows) {
            if (rejectedPrices.contains(String.valueOf(row.getId()))) {
                continue;
            }
            closePriceByDateAndSymbol
                    .computeIfAbsent(row.getTradeDate(), ignored -> new java.util.HashMap<>())
                    .put(
                            row.getSymbol(),
                            reviewedDataResolver.decimal(
                                    priceCorrections.getOrDefault(String.valueOf(row.getId()), Map.of()),
                                    "closePrice",
                                    row.getClosePrice()
                            )
                    );
            allDates.add(row.getTradeDate());
        }

        java.util.Map<String, PositionSnapshot> snapshots = new java.util.HashMap<>();
        java.util.Map<String, BigDecimal> lastClosePriceBySymbol = new java.util.HashMap<>();
        java.util.List<AssetCurvePointResponse> points = new ArrayList<>();
        BigDecimal cashBalance = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);

        for (LocalDate date : allDates) {
            java.util.Map<String, BigDecimal> closesToday = closePriceByDateAndSymbol.getOrDefault(date, java.util.Collections.emptyMap());
            lastClosePriceBySymbol.putAll(closesToday);

            List<Transaction> txns = txByDate.getOrDefault(date, List.of());
            for (Transaction txn : txns) {
                PositionSnapshot snapshot = snapshots.computeIfAbsent(txn.getSymbol(), ignored -> new PositionSnapshot());
                applyTransactionToSnapshot(snapshot, txn);
            }

            cashBalance = cashBalance
                    .add(dividendsByDate.getOrDefault(date, BigDecimal.ZERO))
                    .add(cashAdjustmentsByDate.getOrDefault(date, BigDecimal.ZERO))
                    .setScale(4, RoundingMode.HALF_UP);

            BigDecimal totalCostBasis = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            BigDecimal totalMarketValue = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);

            for (java.util.Map.Entry<String, PositionSnapshot> entry : snapshots.entrySet()) {
                PositionSnapshot snapshot = entry.getValue();
                if (snapshot.quantity.compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }

                totalCostBasis = totalCostBasis.add(snapshot.costBasis()).setScale(4, RoundingMode.HALF_UP);

                BigDecimal close = lastClosePriceBySymbol.get(entry.getKey());
                BigDecimal markPrice = close == null ? snapshot.averageCost : close;
                totalMarketValue = totalMarketValue
                        .add(snapshot.quantity.multiply(markPrice))
                        .setScale(4, RoundingMode.HALF_UP);
            }

            BigDecimal totalAssets = totalMarketValue.add(cashBalance).setScale(4, RoundingMode.HALF_UP);
            OffsetDateTime timestamp = date.atStartOfDay(marketZone).toOffsetDateTime();
            points.add(new AssetCurvePointResponse(timestamp, totalAssets, totalCostBasis, totalMarketValue, cashBalance));
        }

        return points;
    }

    public void reconcileTransactionCashAdjustmentsOnStartup() {
        syncTransactionCashAdjustments();
    }

    private void syncTransactionCashAdjustments() {
        List<Transaction> transactions = transactionRepository.findAllByOrderByExecutedAtAscIdAsc();
        Set<Long> transactionIds = transactions.stream().map(Transaction::getId).collect(java.util.stream.Collectors.toSet());

        for (CashAdjustment adjustment : cashAdjustmentRepository.findAllByTransactionIdIsNotNull()) {
            Long txId = adjustment.getTransactionId();
            if (txId != null && !transactionIds.contains(txId)) {
                cashAdjustmentRepository.delete(adjustment);
            }
        }

        for (Transaction transaction : transactions) {
            upsertCashAdjustmentForTransaction(transaction);
        }
    }

    private void upsertCashAdjustmentForTransaction(Transaction transaction) {
        if (transaction.getId() == null) {
            return;
        }

        CashAdjustment adjustment = cashAdjustmentRepository.findByTransactionId(transaction.getId())
                .orElseGet(CashAdjustment::new);
        adjustment.setTransactionId(transaction.getId());
        adjustment.setType(transaction.getType() == TransactionType.BUY
                ? CashAdjustmentType.WITHDRAWAL
                : CashAdjustmentType.DEPOSIT);
        adjustment.setAmount(transaction.getQuantity()
                .multiply(transaction.getPrice())
                .setScale(4, RoundingMode.HALF_UP));
        adjustment.setOccurredAt(transaction.getExecutedAt());
        cashAdjustmentRepository.save(adjustment);
    }

    private Optional<YahooFinancePriceService.YahooMarketSnapshot> fetchSnapshotWithRetry(String symbol) {
        for (int attempt = 1; attempt <= retryMaxAttempts; attempt++) {
            Optional<YahooFinancePriceService.YahooMarketSnapshot> snapshotOpt = yahooFinancePriceService.fetchSnapshot(symbol);
            if (snapshotOpt.isPresent()) {
                return snapshotOpt;
            }

            if (attempt < retryMaxAttempts && retryBackoffMs > 0) {
                try {
                    Thread.sleep(retryBackoffMs * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return Optional.empty();
                }
            }
        }

        return Optional.empty();
    }

    private List<String> resolveBackfillSymbols(String symbolsCsv) {
        if (symbolsCsv == null || symbolsCsv.isBlank()) {
            java.util.Set<String> symbolsSet = new java.util.TreeSet<>();
            symbolsSet.addAll(activeSymbolsFromTransactions());
            positionRepository.findAll().stream()
                    .map(Position::getSymbol)
                    .filter(s -> s != null && !s.isBlank())
                    .map(this::normalizeSymbol)
                    .forEach(symbolsSet::add);
            List<String> symbols = symbolsSet.stream().toList();
            if (symbols.isEmpty()) {
                throw new ResponseStatusException(BAD_REQUEST,
                        "No symbols found. Provide ?symbols=AAPL,MSFT or create positions/transactions first");
            }
            return symbols;
        }

        List<String> symbols = java.util.Arrays.stream(symbolsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(this::normalizeSymbol)
                .distinct()
                .toList();

        if (symbols.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "No valid symbols provided");
        }
        return symbols;
    }

    private boolean applySnapshotToPosition(Position position,
                                            YahooFinancePriceService.YahooMarketSnapshot snapshot,
                                            OffsetDateTime now) {
        boolean changed = false;

        if (snapshot.regularMarketPrice() != null) {
            position.setLatestPrice(snapshot.regularMarketPrice().setScale(4, RoundingMode.HALF_UP));
            position.setPriceUpdatedAt(now);
            changed = true;
        }
        if (snapshot.currency() != null && !snapshot.currency().isBlank()
                && !snapshot.currency().equalsIgnoreCase(position.getQuoteCurrency())) {
            position.setQuoteCurrency(snapshot.currency().trim().toUpperCase());
            changed = true;
        }

        return changed;
    }

    private boolean refreshSharesOutstanding(Position position, OffsetDateTime now) {
        try {
            Optional<BigDecimal> sharesOpt = yahooFinancePriceService.fetchSharesOutstanding(position.getSymbol());
            if (sharesOpt.isEmpty()) {
                return false;
            }
            BigDecimal shares = sharesOpt.get().setScale(4, RoundingMode.HALF_UP);
            boolean changed = position.getSharesOutstanding() == null
                    || shares.compareTo(position.getSharesOutstanding()) != 0
                    || position.getSharesOutstandingSource() == null
                    || position.getSharesOutstandingUpdatedAt() == null;
            position.setSharesOutstanding(shares);
            position.setSharesOutstandingSource("YAHOO_DEFAULT_KEY_STATISTICS");
            position.setSharesOutstandingUpdatedAt(now);
            try {
                yahooFinancePriceService.fetchBeta(position.getSymbol()).ifPresent(beta -> {
                    position.setBeta(beta.setScale(6, RoundingMode.HALF_UP));
                    position.setBetaSource("YAHOO_QUOTE_SUMMARY");
                    position.setBetaUpdatedAt(now);
                });
            } catch (Exception ignored) { }
            return changed;
        } catch (Exception ignored) {
            return false;
        }
    }

    private void upsertPriceHistory(String symbol, LocalDate tradeDate, BigDecimal closePrice) {
        upsertPriceHistory(symbol, tradeDate, closePrice, null);
    }

    private void upsertPriceHistory(String symbol, LocalDate tradeDate, BigDecimal closePrice, BigDecimal adjustedClosePrice) {
        PriceHistory history = priceHistoryRepository.findBySymbolAndTradeDate(symbol, tradeDate)
                .orElseGet(PriceHistory::new);
        history.setSymbol(symbol);
        history.setTradeDate(tradeDate);
        history.setClosePrice(closePrice);
        if (adjustedClosePrice != null) history.setAdjustedClosePrice(adjustedClosePrice);
        try {
            priceHistoryRepository.save(history);
        } catch (DataIntegrityViolationException e) {
            PriceHistory existing = priceHistoryRepository.findBySymbolAndTradeDate(symbol, tradeDate)
                    .orElseThrow(() -> e);
            existing.setClosePrice(closePrice);
            if (adjustedClosePrice != null) existing.setAdjustedClosePrice(adjustedClosePrice);
            priceHistoryRepository.save(existing);
        }
    }

    private List<YahooFinancePriceService.QuarterlyFundamentalPoint> mergeQuarterlyFundamentals(
            List<YahooFinancePriceService.QuarterlyFundamentalPoint> secRows,
            List<YahooFinancePriceService.QuarterlyFundamentalPoint> yahooRows
    ) {
        Map<String, YahooFinancePriceService.QuarterlyFundamentalPoint> merged = new LinkedHashMap<>();
        for (YahooFinancePriceService.QuarterlyFundamentalPoint row : secRows) {
            merged.put(quarterKey(row.asOfDate()), row);
        }
        for (YahooFinancePriceService.QuarterlyFundamentalPoint row : yahooRows) {
            String key = quarterKey(row.asOfDate());
            YahooFinancePriceService.QuarterlyFundamentalPoint existing = merged.get(key);
            merged.put(key, existing == null
                    ? row
                    : mergePreferPrimary(existing, row));
        }
        return merged.values().stream()
                .sorted(Comparator.comparing(YahooFinancePriceService.QuarterlyFundamentalPoint::asOfDate))
                .toList();
    }

    private YahooFinancePriceService.QuarterlyFundamentalPoint mergePreferPrimary(
            YahooFinancePriceService.QuarterlyFundamentalPoint primary,
            YahooFinancePriceService.QuarterlyFundamentalPoint fallback
    ) {
        return new YahooFinancePriceService.QuarterlyFundamentalPoint(
                primary.asOfDate(),
                choose(primary.basicEps(), fallback.basicEps()),
                chooseText(primary.currencyCode(), fallback.currencyCode()),
                choose(primary.ttmEps(), fallback.ttmEps()),
                choose(primary.forwardEps(), fallback.forwardEps()),
                choose(primary.cashFlow(), fallback.cashFlow()),
                choose(primary.fcf(), fallback.fcf()),
                choose(primary.capex(), fallback.capex()),
                choose(primary.adjustedFcf(), fallback.adjustedFcf()),
                choose(primary.roe(), fallback.roe()),
                choose(primary.roic(), fallback.roic()),
                choose(primary.grossMargin(), fallback.grossMargin()),
                choose(primary.revenue(), fallback.revenue()),
                choose(primary.grossProfit(), fallback.grossProfit()),
                choose(primary.operatingIncome(), fallback.operatingIncome()),
                choose(primary.interestExpense(), fallback.interestExpense()),
                choose(primary.netIncome(), fallback.netIncome()),
                choose(primary.stockholdersEquity(), fallback.stockholdersEquity()),
                choose(primary.totalDebt(), fallback.totalDebt()),
                choose(primary.cashAndEquivalents(), fallback.cashAndEquivalents()),
                choose(primary.shortTermInvestments(), fallback.shortTermInvestments()),
                choose(primary.noncurrentMarketableSecurities(), fallback.noncurrentMarketableSecurities()),
                choose(primary.taxProvision(), fallback.taxProvision()),
                choose(primary.pretaxIncome(), fallback.pretaxIncome()),
                choose(primary.investedCapital(), fallback.investedCapital()),
                choose(primary.dilutedEps(), fallback.dilutedEps()),
                choosePositive(primary.dilutedWeightedAverageShares(), fallback.dilutedWeightedAverageShares()),
                choose(primary.depreciationAmortization(), fallback.depreciationAmortization()),
                choose(primary.changeInWorkingCapital(), fallback.changeInWorkingCapital()),
                choose(primary.netBorrowing(), fallback.netBorrowing()),
                choose(primary.shareRepurchases(), fallback.shareRepurchases()),
                choose(primary.totalAssets(), fallback.totalAssets()),
                primary.fiscalYear() == null ? fallback.fiscalYear() : primary.fiscalYear(),
                chooseText(primary.fiscalPeriod(), fallback.fiscalPeriod()),
                primary.filingDate() == null ? fallback.filingDate() : primary.filingDate()
        );
    }

    private List<YahooFinancePriceService.QuarterlyFundamentalPoint> calculateDerivedMetrics(
            List<YahooFinancePriceService.QuarterlyFundamentalPoint> rows
    ) {
        List<YahooFinancePriceService.QuarterlyFundamentalPoint> sorted = rows.stream()
                .sorted(Comparator.comparing(YahooFinancePriceService.QuarterlyFundamentalPoint::asOfDate))
                .toList();
        List<YahooFinancePriceService.QuarterlyFundamentalPoint> result = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            YahooFinancePriceService.QuarterlyFundamentalPoint row = sorted.get(i);
            BigDecimal ttmEps = sumLastFourNonNull(sorted, i, YahooFinancePriceService.QuarterlyFundamentalPoint::basicEps);
            BigDecimal ttmNetIncome = sumLastFourNonNull(sorted, i, YahooFinancePriceService.QuarterlyFundamentalPoint::netIncome);
            BigDecimal ttmOperatingIncome = sumLastFourNonNull(sorted, i, YahooFinancePriceService.QuarterlyFundamentalPoint::operatingIncome);
            BigDecimal ttmTaxProvision = sumLastFourNonNull(sorted, i, YahooFinancePriceService.QuarterlyFundamentalPoint::taxProvision);
            BigDecimal ttmPretaxIncome = sumLastFourNonNull(sorted, i, YahooFinancePriceService.QuarterlyFundamentalPoint::pretaxIncome);
            BigDecimal ttmGrossProfit = sumLastFourNonNull(sorted, i, YahooFinancePriceService.QuarterlyFundamentalPoint::grossProfit);
            BigDecimal ttmRevenue = sumLastFourNonNull(sorted, i, YahooFinancePriceService.QuarterlyFundamentalPoint::revenue);
            BigDecimal averageEquity = averageBalanceForTtm(sorted, i,
                    YahooFinancePriceService.QuarterlyFundamentalPoint::stockholdersEquity,
                    YahooFinancePriceService.QuarterlyFundamentalPoint::netIncome);
            BigDecimal averageInvestedCapital = averageBalanceForTtm(sorted, i,
                    YahooFinancePriceService.QuarterlyFundamentalPoint::investedCapital,
                    YahooFinancePriceService.QuarterlyFundamentalPoint::operatingIncome);
            BigDecimal roe = ratioPct(ttmNetIncome, averageEquity);
            BigDecimal grossMargin = ratioPct(ttmGrossProfit, ttmRevenue);
            BigDecimal roic = null;
            BigDecimal nopat = calculateNopat(ttmOperatingIncome, ttmTaxProvision, ttmPretaxIncome);
            if (nopat != null) {
                roic = ratioPct(nopat, averageInvestedCapital);
            }
            result.add(withDerived(
                    row,
                    ttmEps,
                    choose(roe, row.roe()),
                    choose(roic, row.roic()),
                    choose(grossMargin, row.grossMargin())
            ));
        }
        return result;
    }

    private YahooFinancePriceService.QuarterlyFundamentalPoint withDerived(
            YahooFinancePriceService.QuarterlyFundamentalPoint row,
            BigDecimal ttmEps,
            BigDecimal roe,
            BigDecimal roic,
            BigDecimal grossMargin
    ) {
        return new YahooFinancePriceService.QuarterlyFundamentalPoint(
                row.asOfDate(),
                row.basicEps(),
                row.currencyCode(),
                ttmEps,
                row.forwardEps(),
                row.cashFlow(),
                row.fcf(),
                row.capex(),
                row.adjustedFcf(),
                roe,
                roic,
                grossMargin,
                row.revenue(),
                row.grossProfit(),
                row.operatingIncome(),
                row.interestExpense(),
                row.netIncome(),
                row.stockholdersEquity(),
                row.totalDebt(),
                row.cashAndEquivalents(),
                row.shortTermInvestments(),
                row.noncurrentMarketableSecurities(),
                row.taxProvision(),
                row.pretaxIncome(),
                row.investedCapital(),
                row.dilutedEps(), row.dilutedWeightedAverageShares(), row.depreciationAmortization(),
                row.changeInWorkingCapital(), row.netBorrowing(), row.shareRepurchases(), row.totalAssets(), row.fiscalYear(),
                row.fiscalPeriod(), row.filingDate()
        );
    }

    private YahooFinancePriceService.QuarterlyFundamentalPoint withForwardEps(
            YahooFinancePriceService.QuarterlyFundamentalPoint row,
            BigDecimal forwardEps
    ) {
        return new YahooFinancePriceService.QuarterlyFundamentalPoint(
                row.asOfDate(),
                row.basicEps(),
                row.currencyCode(),
                row.ttmEps(),
                forwardEps == null ? row.forwardEps() : forwardEps,
                row.cashFlow(),
                row.fcf(),
                row.capex(),
                row.adjustedFcf(),
                row.roe(),
                row.roic(),
                row.grossMargin(),
                row.revenue(),
                row.grossProfit(),
                row.operatingIncome(),
                row.interestExpense(),
                row.netIncome(),
                row.stockholdersEquity(),
                row.totalDebt(),
                row.cashAndEquivalents(),
                row.shortTermInvestments(),
                row.noncurrentMarketableSecurities(),
                row.taxProvision(),
                row.pretaxIncome(),
                row.investedCapital(),
                row.dilutedEps(), row.dilutedWeightedAverageShares(), row.depreciationAmortization(),
                row.changeInWorkingCapital(), row.netBorrowing(), row.shareRepurchases(), row.totalAssets(), row.fiscalYear(),
                row.fiscalPeriod(), row.filingDate()
        );
    }

    static String quarterKey(LocalDate date) {
        long bestDistance = Long.MAX_VALUE;
        int bestYear = date.getYear();
        int bestQuarter = ((date.getMonthValue() - 1) / 3) + 1;
        for (int year = date.getYear() - 1; year <= date.getYear() + 1; year++) {
            for (int quarter = 1; quarter <= 4; quarter++) {
                LocalDate quarterEnd = YearMonth.of(year, quarter * 3).atEndOfMonth();
                long distance = Math.abs(ChronoUnit.DAYS.between(date, quarterEnd));
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestYear = year;
                    bestQuarter = quarter;
                }
            }
        }
        if (bestDistance <= QUARTER_END_TOLERANCE_DAYS) {
            return bestYear + "-Q" + bestQuarter;
        }
        int quarter = ((date.getMonthValue() - 1) / 3) + 1;
        return date.getYear() + "-Q" + quarter;
    }

    private BigDecimal sumLastFourNonNull(List<YahooFinancePriceService.QuarterlyFundamentalPoint> rows,
                                          int index,
                                          Function<YahooFinancePriceService.QuarterlyFundamentalPoint, BigDecimal> getter) {
        BigDecimal sum = BigDecimal.ZERO;
        int found = 0;
        for (int i = index; i >= 0 && found < 4; i--) {
            BigDecimal value = getter.apply(rows.get(i));
            if (value != null) {
                sum = sum.add(value);
                found++;
            }
        }
        return found == 4 ? sum : null;
    }

    private BigDecimal averageBalanceForTtm(List<YahooFinancePriceService.QuarterlyFundamentalPoint> rows,
                                            int index,
                                            Function<YahooFinancePriceService.QuarterlyFundamentalPoint, BigDecimal> balanceGetter,
                                            Function<YahooFinancePriceService.QuarterlyFundamentalPoint, BigDecimal> flowGetter) {
        List<Integer> flowIndexes = new ArrayList<>();
        for (int i = index; i >= 0 && flowIndexes.size() < 4; i--) {
            if (flowGetter.apply(rows.get(i)) != null) {
                flowIndexes.add(i);
            }
        }
        if (flowIndexes.size() < 4) {
            return null;
        }
        BigDecimal start = latestNonNullBalanceAtOrBefore(rows, flowIndexes.get(3), balanceGetter);
        BigDecimal end = latestNonNullBalanceAtOrBefore(rows, flowIndexes.get(0), balanceGetter);
        if (start == null || end == null) {
            return null;
        }
        return start.add(end).divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP);
    }

    private BigDecimal latestNonNullBalanceAtOrBefore(List<YahooFinancePriceService.QuarterlyFundamentalPoint> rows,
                                                       int index,
                                                       Function<YahooFinancePriceService.QuarterlyFundamentalPoint, BigDecimal> getter) {
        for (int i = index; i >= 0; i--) {
            BigDecimal value = getter.apply(rows.get(i));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private BigDecimal calculateNopat(BigDecimal operatingIncome, BigDecimal taxProvision, BigDecimal pretaxIncome) {
        if (operatingIncome == null || taxProvision == null || pretaxIncome == null || pretaxIncome.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        BigDecimal taxRate = taxProvision.divide(pretaxIncome, 8, RoundingMode.HALF_UP);
        if (taxRate.compareTo(BigDecimal.ONE) > 0) {
            return null;
        }
        if (taxRate.compareTo(BigDecimal.ZERO) < 0) {
            taxRate = BigDecimal.ZERO;
        }
        return operatingIncome.multiply(BigDecimal.ONE.subtract(taxRate));
    }

    private BigDecimal ratioPct(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return numerator.divide(denominator, 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    private BigDecimal dividePriceByEps(BigDecimal price, BigDecimal eps) {
        if (price == null || eps == null || eps.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return price.divide(eps, 4, RoundingMode.HALF_UP);
    }

    private BigDecimal annualizeEps(BigDecimal quarterlyEps) {
        return quarterlyEps == null ? null : quarterlyEps.multiply(BigDecimal.valueOf(4));
    }

    private BigDecimal resolveCurrentAnnualForwardEps(String symbol) {
        List<EarningsEstimate> annualEstimates = earningsEstimateRepository
                .findBySymbolAndPeriodTypeOrderByPeriodEndDateAsc(symbol, "ANNUAL");
        if (annualEstimates.isEmpty()) {
            return null;
        }
        LocalDate today = LocalDate.now(marketZone);
        return annualEstimates.stream()
                .filter(estimate -> estimate.getEpsAvg() != null)
                .filter(estimate -> !estimate.getPeriodEndDate().isBefore(today))
                .findFirst()
                .or(() -> annualEstimates.stream().filter(estimate -> estimate.getEpsAvg() != null).findFirst())
                .map(EarningsEstimate::getEpsAvg)
                .orElse(null);
    }

    private BigDecimal choose(BigDecimal primary, BigDecimal fallback) {
        return primary != null ? primary : fallback;
    }

    private BigDecimal choosePositive(BigDecimal primary, BigDecimal fallback) {
        if (primary != null && primary.signum() > 0) return primary;
        return fallback != null && fallback.signum() > 0 ? fallback : null;
    }

    private String chooseText(String primary, String fallback) {
        return primary == null || primary.isBlank() ? fallback : primary;
    }

    private BigDecimal scale(BigDecimal value) {
        return value == null ? null : value.setScale(4, RoundingMode.HALF_UP);
    }

    private QuarterlyFundamentalPointResponse toQuarterlyFundamentalPointResponse(EarningsHistory history) {
        return toQuarterlyFundamentalPointResponse(history, Map.of());
    }

    private QuarterlyFundamentalPointResponse toQuarterlyFundamentalPointResponse(
            EarningsHistory history,
            Map<String, Object> overrides
    ) {
        return new QuarterlyFundamentalPointResponse(
                history.getAsOfDate(),
                reviewedDataResolver.decimal(overrides, "basicEps", history.getBasicEps()),
                reviewedDataResolver.decimal(overrides, "ttmEps", history.getTtmEps()),
                reviewedDataResolver.decimal(overrides, "forwardEps", history.getForwardEps()),
                reviewedDataResolver.decimal(overrides, "cashFlow", history.getCashFlow()),
                reviewedDataResolver.decimal(overrides, "fcf", history.getFcf()),
                reviewedDataResolver.decimal(overrides, "capex", history.getCapex()),
                reviewedDataResolver.decimal(overrides, "adjustedFcf", history.getAdjustedFcf()),
                reviewedDataResolver.decimal(overrides, "roe", history.getRoe()),
                reviewedDataResolver.decimal(overrides, "roic", history.getRoic()),
                reviewedDataResolver.decimal(overrides, "grossMargin", history.getGrossMargin()),
                reviewedDataResolver.decimal(overrides, "revenue", history.getRevenue()),
                reviewedDataResolver.decimal(overrides, "grossProfit", history.getGrossProfit()),
                reviewedDataResolver.decimal(overrides, "operatingIncome", history.getOperatingIncome()),
                reviewedDataResolver.decimal(overrides, "interestExpense", history.getInterestExpense()),
                reviewedDataResolver.decimal(overrides, "netIncome", history.getNetIncome()),
                reviewedDataResolver.decimal(overrides, "stockholdersEquity", history.getStockholdersEquity()),
                reviewedDataResolver.decimal(overrides, "totalDebt", history.getTotalDebt()),
                reviewedDataResolver.decimal(overrides, "cashAndEquivalents", history.getCashAndEquivalents()),
                reviewedDataResolver.decimal(overrides, "shortTermInvestments", history.getShortTermInvestments()),
                reviewedDataResolver.decimal(overrides, "noncurrentMarketableSecurities", history.getNoncurrentMarketableSecurities()),
                reviewedDataResolver.decimal(overrides, "taxProvision", history.getTaxProvision()),
                reviewedDataResolver.decimal(overrides, "pretaxIncome", history.getPretaxIncome()),
                reviewedDataResolver.decimal(overrides, "investedCapital", history.getInvestedCapital()),
                false,
                reviewedDataResolver.decimal(overrides, "dilutedEps", history.getDilutedEps()),
                reviewedDataResolver.decimal(overrides, "dilutedWeightedAverageShares", history.getDilutedWeightedAverageShares()),
                reviewedDataResolver.decimal(overrides, "depreciationAmortization", history.getDepreciationAmortization()),
                reviewedDataResolver.decimal(overrides, "changeInWorkingCapital", history.getChangeInWorkingCapital()),
                reviewedDataResolver.decimal(overrides, "netBorrowing", history.getNetBorrowing()),
                reviewedDataResolver.decimal(overrides, "shareRepurchases", history.getShareRepurchases()),
                reviewedDataResolver.decimal(overrides, "totalAssets", history.getTotalAssets()),
                history.getFiscalYear(),
                history.getFiscalPeriod(),
                history.getFilingDate(),
                fundamentalFieldSources(history, overrides)
        );
    }

    private Map<String, FieldSourceResponse> fundamentalFieldSources(EarningsHistory row, Map<String, Object> overrides) {
        LinkedHashMap<String, FieldSourceResponse> result = new LinkedHashMap<>();
        addFundamentalSource(result, "basicEps", row.getBasicEps(), row, overrides, false);
        addFundamentalSource(result, "dilutedEps", row.getDilutedEps(), row, overrides, false);
        addFundamentalSource(result, "dilutedWeightedAverageShares", row.getDilutedWeightedAverageShares(), row, overrides, false);
        addFundamentalSource(result, "cashFlow", row.getCashFlow(), row, overrides, false);
        addFundamentalSource(result, "capex", row.getCapex(), row, overrides, false);
        addFundamentalSource(result, "interestExpense", row.getInterestExpense(), row, overrides, false);
        addFundamentalSource(result, "netBorrowing", row.getNetBorrowing(), row, overrides, false);
        addFundamentalSource(result, "shareRepurchases", row.getShareRepurchases(), row, overrides, false);
        addFundamentalSource(result, "depreciationAmortization", row.getDepreciationAmortization(), row, overrides, false);
        addFundamentalSource(result, "changeInWorkingCapital", row.getChangeInWorkingCapital(), row, overrides, false);
        addFundamentalSource(result, "totalAssets", row.getTotalAssets(), row, overrides, false);
        addFundamentalSource(result, "revenue", row.getRevenue(), row, overrides, false);
        addFundamentalSource(result, "grossProfit", row.getGrossProfit(), row, overrides, false);
        addFundamentalSource(result, "operatingIncome", row.getOperatingIncome(), row, overrides, false);
        addFundamentalSource(result, "netIncome", row.getNetIncome(), row, overrides, false);
        addFundamentalSource(result, "stockholdersEquity", row.getStockholdersEquity(), row, overrides, false);
        addFundamentalSource(result, "totalDebt", row.getTotalDebt(), row, overrides, false);
        addFundamentalSource(result, "cashAndEquivalents", row.getCashAndEquivalents(), row, overrides, false);
        addFundamentalSource(result, "shortTermInvestments", row.getShortTermInvestments(), row, overrides, false);
        addFundamentalSource(result, "noncurrentMarketableSecurities", row.getNoncurrentMarketableSecurities(), row, overrides, false);
        addFundamentalSource(result, "taxProvision", row.getTaxProvision(), row, overrides, false);
        addFundamentalSource(result, "pretaxIncome", row.getPretaxIncome(), row, overrides, false);
        addFundamentalSource(result, "investedCapital", row.getInvestedCapital(), row, overrides, false);
        addFundamentalSource(result, "ttmEps", row.getTtmEps(), row, overrides, true);
        addFundamentalSource(result, "roe", row.getRoe(), row, overrides, true);
        addFundamentalSource(result, "roic", row.getRoic(), row, overrides, true);
        addFundamentalSource(result, "grossMargin", row.getGrossMargin(), row, overrides, true);
        return Map.copyOf(result);
    }

    private void addFundamentalSource(Map<String, FieldSourceResponse> target, String field, Object value,
                                      EarningsHistory row, Map<String, Object> overrides, boolean derived) {
        if (value == null && !overrides.containsKey(field)) return;
        if (overrides.containsKey(field)) {
            target.put(field, new FieldSourceResponse("REVIEWED", "Manual reviewed correction", row.getAsOfDate(), null));
        } else if (derived) {
            target.put(field, new FieldSourceResponse("SYSTEM_DERIVED", "Java valuation engine", row.getAsOfDate(), null));
        } else if (row.getFilingDate() != null) {
            target.put(field, new FieldSourceResponse("SEC_COMPANY_FACTS", "SEC Company Facts", row.getFilingDate(), null));
        } else {
            target.put(field, new FieldSourceResponse("YAHOO_FALLBACK", "Yahoo Finance fallback", row.getAsOfDate(), null));
        }
    }

    private QuarterlyFundamentalPointResponse toForwardEpsEstimateResponse(EarningsEstimate estimate) {
        return new QuarterlyFundamentalPointResponse(
                estimate.getPeriodEndDate(),
                null,
                null,
                estimate.getEpsAvg(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                true
        );
    }

    private void replaceEarningsEstimates(String symbol, List<YahooFinancePriceService.ForwardEpsEstimatePoint> points) {
        if (points == null || points.isEmpty()) return;
        earningsEstimateRepository.deleteBySymbol(symbol);
        earningsEstimateRepository.flush();
        for (YahooFinancePriceService.ForwardEpsEstimatePoint point : points) {
            EarningsEstimate estimate = new EarningsEstimate();
            estimate.setSymbol(symbol);
            estimate.setPeriodType(point.periodType());
            estimate.setPeriodCode(point.periodCode());
            estimate.setPeriodEndDate(point.asOfDate());
            estimate.setEpsAvg(scale(point.eps()));
            estimate.setEpsLow(scale(point.epsLow()));
            estimate.setEpsHigh(scale(point.epsHigh()));
            estimate.setNumberOfAnalysts(point.numberOfAnalysts());
            estimate.setRevenueAvg(scale(point.revenue()));
            estimate.setRevenueLow(scale(point.revenueLow()));
            estimate.setRevenueHigh(scale(point.revenueHigh()));
            estimate.setRevenueAnalysts(point.revenueAnalysts());
            earningsEstimateRepository.save(estimate);
        }
    }

    private void refreshEarningsEstimatesIfAvailable(String symbol) {
        try {
            replaceEarningsEstimates(symbol, yahooFinancePriceService.fetchForwardEpsEstimates(symbol));
        } catch (Exception ignored) {
        }
    }

    private void refreshStockSplitsIfAvailable(String symbol, LocalDate from, LocalDate to) {
        try {
            for (YahooFinancePriceService.YahooStockSplitPoint point : yahooFinancePriceService.fetchStockSplits(symbol, from, to)) {
                StockSplit split = stockSplitRepository.findBySymbolAndSplitDate(symbol, point.splitDate()).orElseGet(StockSplit::new);
                split.setSymbol(symbol); split.setSplitDate(point.splitDate()); split.setNumerator(point.numerator());
                split.setDenominator(point.denominator()); split.setSourceCode("YAHOO"); split.setSourceDate(point.splitDate());
                stockSplitRepository.save(split);
            }
        } catch (Exception ignored) { }
    }

    private YahooFinancePriceService.QuarterlyFundamentalPoint toQuarterlyFundamentalPoint(EarningsHistory history) {
        return new YahooFinancePriceService.QuarterlyFundamentalPoint(
                history.getAsOfDate(),
                history.getBasicEps(),
                history.getCurrencyCode(),
                history.getTtmEps(),
                history.getForwardEps(),
                history.getCashFlow(),
                history.getFcf(),
                history.getCapex(),
                history.getAdjustedFcf(),
                history.getRoe(),
                history.getRoic(),
                history.getGrossMargin(),
                history.getRevenue(),
                history.getGrossProfit(),
                history.getOperatingIncome(),
                history.getInterestExpense(),
                history.getNetIncome(),
                history.getStockholdersEquity(),
                history.getTotalDebt(),
                history.getCashAndEquivalents(),
                history.getShortTermInvestments(),
                history.getNoncurrentMarketableSecurities(),
                history.getTaxProvision(),
                history.getPretaxIncome(),
                history.getInvestedCapital(),
                history.getDilutedEps(), history.getDilutedWeightedAverageShares(),
                history.getDepreciationAmortization(), history.getChangeInWorkingCapital(),
                history.getNetBorrowing(), history.getShareRepurchases(), history.getTotalAssets(), history.getFiscalYear(),
                history.getFiscalPeriod(), history.getFilingDate()
        );
    }

    private FundamentalFillStats fillMissingEarningsHistory(
            String symbol,
            EarningsHistory existing,
            YahooFinancePriceService.QuarterlyFundamentalPoint point,
            java.util.Map<String, Optional<BigDecimal>> fxCache
    ) {
        EarningsHistory history = existing == null ? new EarningsHistory() : existing;
        boolean inserted = existing == null;
        if (inserted) {
            history.setSymbol(symbol);
            history.setAsOfDate(point.asOfDate());
        }

        BigDecimal sourceEps = point.basicEps() == null ? null : point.basicEps().setScale(4, RoundingMode.HALF_UP);
        BigDecimal epsInQuote = convertEpsToQuoteCurrency(sourceEps, point.currencyCode(), "USD", fxCache);

        int filled = 0;
        filled += fillBigDecimal(history::getBasicEps, history::setBasicEps, sourceEps);
        filled += fillText(history::getCurrencyCode, history::setCurrencyCode,
                point.currencyCode() == null ? null : point.currencyCode().trim().toUpperCase());
        filled += fillBigDecimal(history::getSourceEps, history::setSourceEps, sourceEps);
        filled += fillBigDecimal(history::getEpsInQuote, history::setEpsInQuote, epsInQuote);
        filled += fillBigDecimal(history::getTtmEps, history::setTtmEps, point.ttmEps());
        filled += fillBigDecimal(history::getForwardEps, history::setForwardEps, point.forwardEps());
        filled += fillBigDecimal(history::getCashFlow, history::setCashFlow, point.cashFlow());
        filled += fillBigDecimal(history::getFcf, history::setFcf, point.fcf());
        filled += fillBigDecimal(history::getCapex, history::setCapex, point.capex());
        filled += fillBigDecimal(history::getAdjustedFcf, history::setAdjustedFcf, point.adjustedFcf());
        filled += fillBigDecimal(history::getRoe, history::setRoe, point.roe());
        filled += fillBigDecimal(history::getRoic, history::setRoic, point.roic());
        filled += fillBigDecimal(history::getGrossMargin, history::setGrossMargin, point.grossMargin());
        filled += fillBigDecimal(history::getRevenue, history::setRevenue, point.revenue());
        filled += fillBigDecimal(history::getGrossProfit, history::setGrossProfit, point.grossProfit());
        filled += fillBigDecimal(history::getOperatingIncome, history::setOperatingIncome, point.operatingIncome());
        filled += fillBigDecimal(history::getInterestExpense, history::setInterestExpense, point.interestExpense());
        filled += fillBigDecimal(history::getNetIncome, history::setNetIncome, point.netIncome());
        filled += fillBigDecimal(history::getStockholdersEquity, history::setStockholdersEquity, point.stockholdersEquity());
        filled += fillBigDecimal(history::getTotalDebt, history::setTotalDebt, point.totalDebt());
        filled += fillBigDecimal(history::getCashAndEquivalents, history::setCashAndEquivalents, point.cashAndEquivalents());
        filled += fillBigDecimal(history::getShortTermInvestments, history::setShortTermInvestments, point.shortTermInvestments());
        filled += fillBigDecimal(history::getNoncurrentMarketableSecurities, history::setNoncurrentMarketableSecurities, point.noncurrentMarketableSecurities());
        filled += fillBigDecimal(history::getTaxProvision, history::setTaxProvision, point.taxProvision());
        filled += fillBigDecimal(history::getPretaxIncome, history::setPretaxIncome, point.pretaxIncome());
        filled += fillBigDecimal(history::getInvestedCapital, history::setInvestedCapital, point.investedCapital());
        filled += fillBigDecimal(history::getDilutedEps, history::setDilutedEps, point.dilutedEps());
        if (history.getDilutedWeightedAverageShares() != null && history.getDilutedWeightedAverageShares().signum() <= 0) {
            history.setDilutedWeightedAverageShares(point.dilutedWeightedAverageShares() == null ? null : scale(point.dilutedWeightedAverageShares()));
            filled++;
        } else {
            filled += fillBigDecimal(history::getDilutedWeightedAverageShares, history::setDilutedWeightedAverageShares, point.dilutedWeightedAverageShares());
        }
        filled += fillBigDecimal(history::getDepreciationAmortization, history::setDepreciationAmortization, point.depreciationAmortization());
        filled += fillBigDecimal(history::getChangeInWorkingCapital, history::setChangeInWorkingCapital, point.changeInWorkingCapital());
        filled += fillBigDecimal(history::getNetBorrowing, history::setNetBorrowing, point.netBorrowing());
        filled += fillBigDecimal(history::getShareRepurchases, history::setShareRepurchases, point.shareRepurchases());
        filled += fillBigDecimal(history::getTotalAssets, history::setTotalAssets, point.totalAssets());
        if (point.fiscalYear() != null && !java.util.Objects.equals(history.getFiscalYear(), point.fiscalYear())) { history.setFiscalYear(point.fiscalYear()); filled++; }
        if (point.fiscalPeriod() != null && !point.fiscalPeriod().isBlank() && !point.fiscalPeriod().equals(history.getFiscalPeriod())) { history.setFiscalPeriod(point.fiscalPeriod()); filled++; }
        if (history.getFilingDate() == null && point.filingDate() != null) { history.setFilingDate(point.filingDate()); filled++; }

        if (!inserted && filled == 0) {
            return new FundamentalFillStats(0, 0, 0);
        }

        earningsHistoryRepository.save(history);
        return new FundamentalFillStats(inserted ? 1 : 0, inserted ? 0 : 1, filled);
    }

    private int fillBigDecimal(Supplier<BigDecimal> getter, Consumer<BigDecimal> setter, BigDecimal value) {
        if (getter.get() != null || value == null) {
            return 0;
        }
        setter.accept(scale(value));
        return 1;
    }

    private int fillText(Supplier<String> getter, Consumer<String> setter, String value) {
        if (getter.get() != null && !getter.get().isBlank()) {
            return 0;
        }
        if (value == null || value.isBlank()) {
            return 0;
        }
        setter.accept(value);
        return 1;
    }

    private void upsertEarningsHistory(String symbol,
                                       LocalDate asOfDate,
                                       BigDecimal sourceEps,
                                       String sourceCurrencyCode,
                                       BigDecimal epsInQuote,
                                       YahooFinancePriceService.QuarterlyFundamentalPoint point) {
        EarningsHistory history = earningsHistoryRepository.findBySymbolAndAsOfDate(symbol, asOfDate)
                .orElseGet(EarningsHistory::new);
        history.setSymbol(symbol);
        history.setAsOfDate(asOfDate);
        history.setBasicEps(sourceEps);
        history.setCurrencyCode(sourceCurrencyCode == null ? null : sourceCurrencyCode.trim().toUpperCase());
        history.setSourceEps(sourceEps);
        history.setEpsInQuote(epsInQuote == null ? null : epsInQuote.setScale(4, RoundingMode.HALF_UP));
        history.setTtmEps(scale(point.ttmEps()));
        history.setForwardEps(scale(point.forwardEps()));
        history.setCashFlow(scale(point.cashFlow()));
        history.setFcf(scale(point.fcf()));
        history.setCapex(scale(point.capex()));
        history.setAdjustedFcf(scale(point.adjustedFcf()));
        history.setRoe(scale(point.roe()));
        history.setRoic(scale(point.roic()));
        history.setGrossMargin(scale(point.grossMargin()));
        history.setRevenue(scale(point.revenue()));
        history.setGrossProfit(scale(point.grossProfit()));
        history.setOperatingIncome(scale(point.operatingIncome()));
        history.setInterestExpense(scale(point.interestExpense()));
        history.setNetIncome(scale(point.netIncome()));
        history.setStockholdersEquity(scale(point.stockholdersEquity()));
        history.setTotalDebt(scale(point.totalDebt()));
        history.setCashAndEquivalents(scale(point.cashAndEquivalents()));
        history.setShortTermInvestments(scale(point.shortTermInvestments()));
        history.setNoncurrentMarketableSecurities(scale(point.noncurrentMarketableSecurities()));
        history.setTaxProvision(scale(point.taxProvision()));
        history.setPretaxIncome(scale(point.pretaxIncome()));
        history.setInvestedCapital(scale(point.investedCapital()));
        history.setDilutedEps(point.dilutedEps() == null ? null : point.dilutedEps().setScale(6, RoundingMode.HALF_UP));
        history.setDilutedWeightedAverageShares(scale(point.dilutedWeightedAverageShares()));
        history.setDepreciationAmortization(scale(point.depreciationAmortization()));
        history.setChangeInWorkingCapital(scale(point.changeInWorkingCapital()));
        history.setNetBorrowing(scale(point.netBorrowing()));
        history.setShareRepurchases(scale(point.shareRepurchases()));
        history.setTotalAssets(scale(point.totalAssets()));
        history.setFiscalYear(point.fiscalYear());
        history.setFiscalPeriod(point.fiscalPeriod());
        history.setFilingDate(point.filingDate());
        earningsHistoryRepository.save(history);
    }

    private record FundamentalFillStats(int rowsInserted, int rowsUpdated, int fieldsFilled) {
    }

    private boolean applyDerivedPeFromLatestEarnings(Position position, LocalDate asOfDate) {
        if (position.getLatestPrice() == null) {
            position.setLatestPe(null);
            return false;
        }

        Optional<EarningsHistory> earningsOpt = earningsHistoryRepository
                .findTopBySymbolAndAsOfDateLessThanEqualOrderByAsOfDateDesc(position.getSymbol(), asOfDate);
        if (earningsOpt.isEmpty()) {
            position.setLatestPe(null);
            return false;
        }

        BigDecimal epsInQuote = earningsOpt.get().getTtmEps();
        if (epsInQuote == null) {
            epsInQuote = resolveEpsInQuote(earningsOpt.get(), "USD", new java.util.HashMap<>());
        }
        if (epsInQuote == null || epsInQuote.compareTo(BigDecimal.ZERO) <= 0) {
            position.setLatestPe(null);
            return false;
        }

        BigDecimal derivedPe = position.getLatestPrice().divide(epsInQuote, 4, RoundingMode.HALF_UP);
        boolean changed = !derivedPe.equals(position.getLatestPe());
        position.setLatestPe(derivedPe);
        return changed;
    }

    private BigDecimal convertEpsToQuoteCurrency(BigDecimal eps,
                                                 String epsCurrency,
                                                 String quoteCurrency,
                                                 java.util.Map<String, Optional<BigDecimal>> fxCache) {
        if (eps == null) {
            return null;
        }

        String from = (epsCurrency == null || epsCurrency.isBlank()) ? quoteCurrency : epsCurrency.trim().toUpperCase();
        String to = quoteCurrency == null ? "USD" : quoteCurrency.trim().toUpperCase();
        if (from.equals(to)) {
            return eps.setScale(4, RoundingMode.HALF_UP);
        }

        String key = from + "->" + to;
        Optional<BigDecimal> fxOpt = fxCache.computeIfAbsent(key, ignored -> {
            try {
                return yahooFinancePriceService.fetchFxRate(from, to);
            } catch (Exception e) {
                return Optional.empty();
            }
        });
        if (fxOpt.isEmpty()) {
            return null;
        }
        BigDecimal fx = fxOpt.get();
        if (fx.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return eps.multiply(fx).setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal resolveEpsInQuote(EarningsHistory earnings,
                                         String quoteCurrency,
                                         java.util.Map<String, Optional<BigDecimal>> fxCache) {
        return resolveEpsInQuote(earnings, Map.of(), quoteCurrency, fxCache);
    }

    private BigDecimal resolveEpsInQuote(EarningsHistory earnings,
                                         Map<String, Object> overrides,
                                         String quoteCurrency,
                                         java.util.Map<String, Optional<BigDecimal>> fxCache) {
        if (earnings == null) {
            return null;
        }
        BigDecimal epsInQuote = reviewedDataResolver.decimal(overrides, "epsInQuote", earnings.getEpsInQuote());
        if (epsInQuote != null) {
            return epsInQuote.setScale(4, RoundingMode.HALF_UP);
        }

        BigDecimal sourceEps = reviewedDataResolver.decimal(overrides, "sourceEps", earnings.getSourceEps());
        BigDecimal basicEps = reviewedDataResolver.decimal(overrides, "basicEps", earnings.getBasicEps());
        BigDecimal source = sourceEps != null ? sourceEps : basicEps;
        String currencyCode = reviewedDataResolver.text(overrides, "currencyCode", earnings.getCurrencyCode());
        return convertEpsToQuoteCurrency(source, currencyCode, quoteCurrency, fxCache);
    }

    private CsvImportAnalysis analyzeCsv(MultipartFile file, boolean dryRun) {
        int totalRows = 0;
        int importedRows = 0;
        int skippedRows = 0;
        int failedRows = 0;
        List<String> sampleErrors = new ArrayList<>();
        List<TransactionCsvFailedRow> failedRowsDetail = new ArrayList<>();
        List<CsvTransactionImportRow> parsedRows = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            if (line == null) {
                return new CsvImportAnalysis(totalRows, importedRows, skippedRows, failedRows, sampleErrors, failedRowsDetail);
            }

            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;

                if (line.isBlank()) {
                    skippedRows++;
                    continue;
                }

                totalRows++;
                List<String> cells = parseCsvLine(line);
                if (cells.size() < 5) {
                    failedRows++;
                    addFailedRow(sampleErrors, failedRowsDetail, lineNumber, line, "invalid column count");
                    continue;
                }

                try {
                    OffsetDateTime executedAt = parseCsvExecutedAt(cells.get(0).trim());
                    String symbol = extractSymbolFromRaw(cells.get(1).trim());
                    TransactionType type = parseSide(cells.get(2).trim());
                    BigDecimal quantity = new BigDecimal(cells.get(3).trim());
                    BigDecimal price = new BigDecimal(cells.get(4).trim());

                    String note = cells.size() >= 6 ? cells.get(5).trim() : null;
                    parsedRows.add(new CsvTransactionImportRow(lineNumber, line, symbol, type, quantity, price, note, executedAt));
                } catch (Exception e) {
                    failedRows++;
                    addFailedRow(sampleErrors, failedRowsDetail, lineNumber, line, e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new ResponseStatusException(BAD_REQUEST, "Unable to read CSV file: " + e.getMessage());
        }

        java.util.Map<String, BigDecimal> simulatedHoldings = buildSimulatedHoldings();
        List<CsvTransactionImportRow> rowsToProcess = parsedRows.stream()
                .sorted(Comparator
                        .comparing(CsvTransactionImportRow::executedAt)
                        .thenComparingInt(CsvTransactionImportRow::lineNumber))
                .toList();
        for (CsvTransactionImportRow row : rowsToProcess) {
            try {
                if (dryRun) {
                    validateAndApplyDryRunTransaction(simulatedHoldings, row.symbol(), row.type(), row.quantity());
                } else {
                    recordTransaction(new TransactionRequest(
                            row.symbol(),
                            row.type(),
                            row.quantity(),
                            row.price(),
                            row.note(),
                            row.executedAt()
                    ));
                }
                importedRows++;
            } catch (Exception e) {
                failedRows++;
                addFailedRow(sampleErrors, failedRowsDetail, row.lineNumber(), row.rawLine(), e.getMessage());
            }
        }
        return new CsvImportAnalysis(totalRows, importedRows, skippedRows, failedRows, sampleErrors, failedRowsDetail);
    }

    private java.util.Map<String, BigDecimal> buildSimulatedHoldings() {
        java.util.Map<String, BigDecimal> holdings = new java.util.HashMap<>();
        buildPositionSnapshots(transactionRepository.findAllByOrderByExecutedAtAscIdAsc()).forEach((symbol, snapshot) -> {
            if (snapshot.quantity.compareTo(BigDecimal.ZERO) > 0) {
                holdings.put(symbol, snapshot.quantity);
            }
        });
        return holdings;
    }

    private void validateAndApplyDryRunTransaction(java.util.Map<String, BigDecimal> simulatedHoldings,
                                                   String symbol,
                                                   TransactionType type,
                                                   BigDecimal quantity) {
        BigDecimal current = simulatedHoldings.getOrDefault(symbol, BigDecimal.ZERO);
        if (type == TransactionType.BUY) {
            simulatedHoldings.put(symbol, current.add(quantity));
            return;
        }

        if (current.compareTo(quantity) < 0) {
            throw new IllegalArgumentException("Sell quantity exceeds current/simulated holding for symbol: " + symbol);
        }
        simulatedHoldings.put(symbol, current.subtract(quantity));
    }

    private void addFailedRow(List<String> sampleErrors,
                              List<TransactionCsvFailedRow> failedRowsDetail,
                              int lineNumber,
                              String rawLine,
                              String reason) {
        if (sampleErrors.size() < 10) {
            sampleErrors.add("Row " + lineNumber + ": " + reason);
        }
        failedRowsDetail.add(new TransactionCsvFailedRow(lineNumber, rawLine, reason));
    }

    private Position ensurePositionCache(String symbol) {
        return positionRepository.findBySymbolIgnoreCase(symbol)
                .orElseGet(() -> {
                    Position p = new Position();
                    p.setSymbol(symbol);
                    return positionRepository.save(p);
                });
    }

    private List<Position> loadOrCreatePositionCaches(Set<String> symbols) {
        if (symbols.isEmpty()) {
            return List.of();
        }
        java.util.Map<String, Position> existing = positionRepository.findBySymbolIn(new ArrayList<>(symbols)).stream()
                .collect(java.util.stream.Collectors.toMap(
                        Position::getSymbol,
                        p -> p,
                        (left, right) -> left
                ));
        List<Position> rows = new ArrayList<>();
        for (String symbol : symbols) {
            Position position = existing.get(symbol);
            if (position == null) {
                position = new Position();
                position.setSymbol(symbol);
            }
            rows.add(position);
        }
        return rows;
    }

    private Set<String> activeSymbolsFromTransactions() {
        return buildPositionSnapshots(transactionRepository.findAllByOrderByExecutedAtAscIdAsc()).entrySet().stream()
                .filter(e -> e.getValue().quantity.compareTo(BigDecimal.ZERO) > 0)
                .map(java.util.Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet());
    }

    private java.util.Map<String, PositionSnapshot> buildPositionSnapshots(List<Transaction> transactions) {
        java.util.Map<String, PositionSnapshot> snapshots = new java.util.HashMap<>();
        for (Transaction transaction : transactions) {
            PositionSnapshot snapshot = snapshots.computeIfAbsent(transaction.getSymbol(), s -> new PositionSnapshot());
            applyTransactionToSnapshot(snapshot, transaction);
        }
        return snapshots;
    }

    private void validateSellQuantityOrThrow(String symbol, BigDecimal quantity, Long excludeTransactionId) {
        List<Transaction> transactions = transactionRepository.findAllByOrderByExecutedAtAscIdAsc();
        if (excludeTransactionId != null) {
            transactions = transactions.stream()
                    .filter(tx -> !excludeTransactionId.equals(tx.getId()))
                    .toList();
        }

        BigDecimal current = buildPositionSnapshots(transactions)
                .getOrDefault(symbol, new PositionSnapshot())
                .quantity;
        if (current.compareTo(quantity) < 0) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "Sell quantity exceeds current holding for symbol: " + symbol);
        }
    }

    private void applyTransactionToSnapshot(PositionSnapshot snapshot, Transaction transaction) {
        if (transaction.getType() == TransactionType.BUY) {
            BigDecimal newQuantity = snapshot.quantity.add(transaction.getQuantity());
            BigDecimal oldCostValue = snapshot.quantity.multiply(snapshot.averageCost);
            BigDecimal newCostValue = transaction.getQuantity().multiply(transaction.getPrice());
            BigDecimal weightedAverage = newQuantity.compareTo(BigDecimal.ZERO) == 0
                    ? BigDecimal.ZERO
                    : oldCostValue.add(newCostValue).divide(newQuantity, 4, RoundingMode.HALF_UP);

            snapshot.quantity = newQuantity.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
            snapshot.averageCost = weightedAverage.setScale(4, RoundingMode.HALF_UP);
            return;
        }

        BigDecimal remainingQuantity = snapshot.quantity.subtract(transaction.getQuantity());
        if (remainingQuantity.compareTo(BigDecimal.ZERO) <= 0) {
            snapshot.quantity = BigDecimal.ZERO.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
            snapshot.averageCost = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        } else {
            snapshot.quantity = remainingQuantity.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
        }
    }

    private String normalizeSymbol(String symbol) {
        return symbol.trim().toUpperCase();
    }

    private String extractSymbolFromRaw(String symbolRaw) {
        String normalizedRaw = symbolRaw.trim().toUpperCase();
        if (normalizedRaw.contains(":")) {
            return normalizedRaw.substring(normalizedRaw.indexOf(':') + 1);
        }
        return normalizedRaw;
    }

    private TransactionType parseSide(String side) {
        String normalized = side.trim().toUpperCase();
        return switch (normalized) {
            case "BUY" -> TransactionType.BUY;
            case "SELL" -> TransactionType.SELL;
            default -> throw new IllegalArgumentException("unsupported side: " + side);
        };
    }

    private OffsetDateTime parseCsvExecutedAt(String rawDateTime) {
        try {
            LocalDateTime localDateTime = LocalDateTime.parse(rawDateTime, CSV_DATE_TIME_FORMATTER);
            return localDateTime.atZone(marketZone).toOffsetDateTime();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("invalid datetime: " + rawDateTime);
        }
    }

    private OffsetDateTime parseCashOccurredAt(String rawDateTime) {
        try {
            return OffsetDateTime.parse(rawDateTime);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDate.parse(rawDateTime).atStartOfDay(marketZone).toOffsetDateTime();
            } catch (DateTimeParseException ignoredAgain) {
                return parseCsvExecutedAt(rawDateTime);
            }
        }
    }

    private CashAdjustment findManualCashAdjustment(Long adjustmentId) {
        CashAdjustment adjustment = cashAdjustmentRepository.findById(adjustmentId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Cash adjustment not found"));
        if (adjustment.getTransactionId() != null) {
            throw new ResponseStatusException(BAD_REQUEST, "Transaction-linked cash adjustments must be changed from the transaction record");
        }
        return adjustment;
    }

    private List<String> parseCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (ch == ',' && !inQuotes) {
                cells.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }

        cells.add(current.toString());
        return cells;
    }

    private String escapeCsv(String value) {
        String escaped = value.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }

    private PositionResponse toPositionResponse(Position position) {
        Map<String, Object> overrides = companyProfileOverrides(position);
        BigDecimal sharesOutstanding = reviewedDataResolver.decimal(overrides, "sharesOutstanding", position.getSharesOutstanding());
        BigDecimal sharesOutstandingOverride = reviewedDataResolver.decimal(
                overrides, "sharesOutstandingOverride", position.getSharesOutstandingOverride()
        );
        return new PositionResponse(
                position.getId(),
                position.getSymbol(),
                reviewedDataResolver.decimal(overrides, "latestPrice", position.getLatestPrice()),
                reviewedDataResolver.decimal(overrides, "latestPe", position.getLatestPe()),
                sharesOutstanding,
                sharesOutstandingOverride,
                sharesOutstandingOverride == null ? sharesOutstanding : sharesOutstandingOverride,
                reviewedDataResolver.text(overrides, "sharesOutstandingSource", position.getSharesOutstandingSource()),
                position.getSharesOutstandingUpdatedAt(),
                position.getPriceUpdatedAt(),
                reviewedDataResolver.text(overrides, "assetClass", position.getAssetClass()),
                reviewedDataResolver.text(overrides, "instrumentType", position.getInstrumentType()),
                reviewedDataResolver.text(overrides, "underlying", position.getUnderlying()),
                reviewedDataResolver.text(overrides, "sector", position.getSector()),
                reviewedDataResolver.text(overrides, "region", position.getRegion()),
                position.getMetadataUpdatedAt(),
                position.getUpdatedAt(),
                reviewedDataResolver.reviewStatus("company_profiles", position.getId()),
                position.getVersion(),
                position.getQuoteCurrency(),
                position.getBeta(),
                position.getBetaSource(),
                position.getBetaUpdatedAt()
        );
    }

    private BigDecimal effectiveLatestPrice(Position position) {
        return reviewedDataResolver.decimal(
                companyProfileOverrides(position),
                "latestPrice",
                position.getLatestPrice()
        );
    }

    private BigDecimal effectiveLatestPe(Position position) {
        return reviewedDataResolver.decimal(
                companyProfileOverrides(position),
                "latestPe",
                position.getLatestPe()
        );
    }

    private String effectiveAssetClass(Position position) {
        return reviewedDataResolver.text(
                companyProfileOverrides(position),
                "assetClass",
                position.getAssetClass()
        );
    }

    private String effectiveInstrumentType(Position position) {
        return reviewedDataResolver.text(
                companyProfileOverrides(position),
                "instrumentType",
                position.getInstrumentType()
        );
    }

    private String effectiveUnderlying(Position position) {
        return reviewedDataResolver.text(
                companyProfileOverrides(position),
                "underlying",
                position.getUnderlying()
        );
    }

    private String effectiveSector(Position position) {
        return reviewedDataResolver.text(
                companyProfileOverrides(position),
                "sector",
                position.getSector()
        );
    }

    private String effectiveRegion(Position position) {
        return reviewedDataResolver.text(
                companyProfileOverrides(position),
                "region",
                position.getRegion()
        );
    }

    private Map<String, Object> companyProfileOverrides(Position position) {
        if (position == null || position.getId() == null) {
            return Map.of();
        }
        return reviewedDataResolver.correctedValues("company_profiles", position.getId());
    }

    private TransactionResponse toTransactionResponse(Transaction transaction) {
        return new TransactionResponse(
                transaction.getId(),
                transaction.getSymbol(),
                transaction.getType(),
                transaction.getQuantity(),
                transaction.getPrice(),
                transaction.getNote(),
                transaction.getExecutedAt(),
                transaction.getVersion()
        );
    }

    private DividendResponse toDividendResponse(Dividend dividend) {
        return new DividendResponse(
                dividend.getId(),
                dividend.getSymbol(),
                dividend.getAmount(),
                dividend.getPaidDate(),
                dividend.getCreatedAt(),
                dividend.getVersion()
        );
    }

    private CashAdjustmentResponse toCashAdjustmentResponse(CashAdjustment adjustment) {
        return new CashAdjustmentResponse(
                adjustment.getId(),
                adjustment.getType(),
                adjustment.getAmount(),
                signedCashAdjustment(adjustment),
                adjustment.getOccurredAt(),
                adjustment.getCreatedAt(),
                adjustment.getTransactionId(),
                adjustment.getVersion()
        );
    }

    private StockNoteResponse toStockNoteResponse(com.stockportfolio.model.StockNote stockNote) {
        return new StockNoteResponse(
                stockNote.getSymbol(),
                stockNote.getNote(),
                stockNote.getUpdatedAt(),
                stockNote.getVersion()
        );
    }

    private FundamentalNoteResponse toFundamentalNoteResponse(FundamentalNote fundamentalNote) {
        return new FundamentalNoteResponse(
                fundamentalNote.getSymbol(),
                fundamentalNote.getNote(),
                fundamentalNote.getUpdatedAt(),
                fundamentalNote.getVersion()
        );
    }

    private OverviewNoteResponse toOverviewNoteResponse(OverviewNote overviewNote) {
        return new OverviewNoteResponse(
                overviewNote.getNoteType(),
                overviewNote.getNote(),
                overviewNote.getUpdatedAt(),
                overviewNote.getVersion()
        );
    }

    private BigDecimal signedCashAdjustment(CashAdjustment adjustment) {
        if (adjustment.getType() == CashAdjustmentType.WITHDRAWAL) {
            return adjustment.getAmount().negate().setScale(4, RoundingMode.HALF_UP);
        }
        return adjustment.getAmount().setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal percent(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        }
        return numerator
                .multiply(new BigDecimal("100"))
                .divide(denominator, 4, RoundingMode.HALF_UP);
    }

    private String normalizeNote(String note) {
        if (note == null) {
            return null;
        }
        String trimmed = note.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record CsvImportAnalysis(
            int totalRows,
            int importedRows,
            int skippedRows,
            int failedRows,
            List<String> sampleErrors,
            List<TransactionCsvFailedRow> failedRowsDetail
    ) {
    }

    private record CsvTransactionImportRow(
            int lineNumber,
            String rawLine,
            String symbol,
            TransactionType type,
            BigDecimal quantity,
            BigDecimal price,
            String note,
            OffsetDateTime executedAt
    ) {
    }

    private record PortfolioMetrics(
            BigDecimal totalCostBasis,
            BigDecimal totalUnits,
            BigDecimal totalMarketValue,
            BigDecimal totalUnrealizedPnl,
            BigDecimal totalRealizedGain
    ) {
    }

    private record FundamentalSnapshot(
            PortfolioExportV2Response.Fundamentals response,
            OffsetDateTime updatedAt
    ) {
    }

    private record OptionSymbol(
            String underlying,
            LocalDate expiration,
            String type,
            BigDecimal strike
    ) {
    }

    private static class PositionSnapshot {
        private BigDecimal quantity = BigDecimal.ZERO.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
        private BigDecimal averageCost = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);

        private BigDecimal costBasis() {
            return quantity.multiply(averageCost).setScale(4, RoundingMode.HALF_UP);
        }
    }
}
