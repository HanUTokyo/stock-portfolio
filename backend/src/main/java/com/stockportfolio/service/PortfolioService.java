package com.stockportfolio.service;

import com.stockportfolio.dto.*;
import com.stockportfolio.model.CashAdjustment;
import com.stockportfolio.model.CashAdjustmentType;
import com.stockportfolio.model.Dividend;
import com.stockportfolio.model.EarningsHistory;
import com.stockportfolio.model.OverviewNote;
import com.stockportfolio.model.OverviewNoteType;
import com.stockportfolio.model.Position;
import com.stockportfolio.model.PriceHistory;
import com.stockportfolio.model.Transaction;
import com.stockportfolio.model.TransactionType;
import com.stockportfolio.repository.CashAdjustmentRepository;
import com.stockportfolio.repository.DividendRepository;
import com.stockportfolio.repository.EarningsHistoryRepository;
import com.stockportfolio.repository.OverviewNoteRepository;
import com.stockportfolio.repository.PositionRepository;
import com.stockportfolio.repository.PriceHistoryRepository;
import com.stockportfolio.repository.StockNoteRepository;
import com.stockportfolio.repository.TransactionRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
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
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
@Transactional
public class PortfolioService {
    private static final DateTimeFormatter CSV_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd H:mm:ss");

    private final PositionRepository positionRepository;
    private final TransactionRepository transactionRepository;
    private final DividendRepository dividendRepository;
    private final CashAdjustmentRepository cashAdjustmentRepository;
    private final PriceHistoryRepository priceHistoryRepository;
    private final EarningsHistoryRepository earningsHistoryRepository;
    private final StockNoteRepository stockNoteRepository;
    private final OverviewNoteRepository overviewNoteRepository;
    private final YahooFinancePriceService yahooFinancePriceService;
    private final int retryMaxAttempts;
    private final long retryBackoffMs;
    private final ZoneId marketZone;

    public PortfolioService(
            PositionRepository positionRepository,
            TransactionRepository transactionRepository,
            DividendRepository dividendRepository,
            CashAdjustmentRepository cashAdjustmentRepository,
            PriceHistoryRepository priceHistoryRepository,
            EarningsHistoryRepository earningsHistoryRepository,
            StockNoteRepository stockNoteRepository,
            OverviewNoteRepository overviewNoteRepository,
            YahooFinancePriceService yahooFinancePriceService,
            @Value("${app.pricing.retry.max-attempts:3}") int retryMaxAttempts,
            @Value("${app.pricing.retry.backoff-ms:1000}") long retryBackoffMs,
            @Value("${app.pricing.timezone:America/New_York}") String marketTimezone
    ) {
        this.positionRepository = positionRepository;
        this.transactionRepository = transactionRepository;
        this.dividendRepository = dividendRepository;
        this.cashAdjustmentRepository = cashAdjustmentRepository;
        this.priceHistoryRepository = priceHistoryRepository;
        this.earningsHistoryRepository = earningsHistoryRepository;
        this.stockNoteRepository = stockNoteRepository;
        this.overviewNoteRepository = overviewNoteRepository;
        this.yahooFinancePriceService = yahooFinancePriceService;
        this.retryMaxAttempts = Math.max(1, retryMaxAttempts);
        this.retryBackoffMs = Math.max(0, retryBackoffMs);
        this.marketZone = ZoneId.of(marketTimezone);
    }

    public PositionResponse addOrUpdatePosition(PositionRequest request) {
        String symbol = normalizeSymbol(request.symbol());
        Position saved = ensurePositionCache(symbol);
        return toPositionResponse(saved);
    }

    public List<PositionResponse> listPositions() {
        return positionRepository.findAll().stream().map(this::toPositionResponse).toList();
    }

    public TransactionResponse recordTransaction(TransactionRequest request) {
        String symbol = normalizeSymbol(request.symbol());
        if (request.type() == TransactionType.SELL) {
            validateSellQuantityOrThrow(symbol, request.quantity().setScale(4, RoundingMode.HALF_UP), null);
        }

        Transaction transaction = new Transaction();
        transaction.setSymbol(symbol);
        transaction.setType(request.type());
        transaction.setQuantity(request.quantity().setScale(4, RoundingMode.HALF_UP));
        transaction.setPrice(request.price().setScale(4, RoundingMode.HALF_UP));
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
            validateSellQuantityOrThrow(newSymbol, request.quantity().setScale(4, RoundingMode.HALF_UP), transactionId);
        }

        transaction.setSymbol(newSymbol);
        transaction.setType(request.type());
        transaction.setQuantity(request.quantity().setScale(4, RoundingMode.HALF_UP));
        transaction.setPrice(request.price().setScale(4, RoundingMode.HALF_UP));
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
                    BigDecimal quantity = snapshot.quantity.setScale(4, RoundingMode.HALF_UP);
                    BigDecimal averageCost = snapshot.averageCost.setScale(4, RoundingMode.HALF_UP);
                    BigDecimal costBasis = quantity.multiply(averageCost).setScale(4, RoundingMode.HALF_UP);

                    Position cache = cacheBySymbol.get(symbol);
                    BigDecimal latestPrice = cache == null ? null : cache.getLatestPrice();
                    BigDecimal latestPe = cache == null ? null : cache.getLatestPe();
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

    @Transactional(readOnly = true)
    public List<CashAdjustmentResponse> listCashAdjustments() {
        return cashAdjustmentRepository.findAllByOrderByOccurredAtAscIdAsc()
                .stream()
                .map(this::toCashAdjustmentResponse)
                .toList();
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
                String[] cells = line.split(",", -1);
                if (cells.length < 6) {
                    failedRows++;
                    if (sampleErrors.size() < 10) {
                        sampleErrors.add("Row " + lineNumber + ": invalid column count");
                    }
                    continue;
                }

                try {
                    String symbol = extractSymbolFromRaw(cells[0].trim());
                    String side = cells[1].trim();
                    if (!"DIVIDEND".equalsIgnoreCase(side)) {
                        skippedRows++;
                        continue;
                    }

                    BigDecimal amount = new BigDecimal(cells[2].trim());
                    LocalDate paidDate = LocalDate.parse(cells[5].trim());
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
                    BigDecimal latestPrice = position == null ? null : position.getLatestPrice();
                    BigDecimal latestPe = position == null ? null : position.getLatestPe();
                    BigDecimal marketPrice = latestPrice == null ? snapshot.averageCost : latestPrice;
                    BigDecimal costBasis = snapshot.costBasis();
                    BigDecimal marketValue = snapshot.quantity.multiply(marketPrice).setScale(4, RoundingMode.HALF_UP);
                    BigDecimal unrealizedPnl = marketValue.subtract(costBasis).setScale(4, RoundingMode.HALF_UP);
                    BigDecimal dividendIncome = dividendBySymbol
                            .getOrDefault(symbol, BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP))
                            .setScale(4, RoundingMode.HALF_UP);

                    return new PortfolioExportHoldingResponse(
                            symbol,
                            snapshot.quantity.setScale(4, RoundingMode.HALF_UP),
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
            BigDecimal latestPrice = position == null ? null : position.getLatestPrice();
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
                    upsertPriceHistory(symbol, point.tradeDate(), point.closePrice().setScale(4, RoundingMode.HALF_UP));
                    historyPointsWritten++;
                }
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
                LocalDate earningsFrom = fromDate.minusYears(2);
                List<YahooFinancePriceService.QuarterlyEpsPoint> epsRows =
                        yahooFinancePriceService.fetchTrailingBasicEpsHistory(symbol, earningsFrom, toDate);
                List<YahooFinancePriceService.QuarterlyEpsPoint> sortedEps = epsRows.stream()
                        .sorted(Comparator.comparing(YahooFinancePriceService.QuarterlyEpsPoint::asOfDate))
                        .toList();

                if (sortedEps.isEmpty()) {
                    skipped++;
                    continue;
                }

                List<EarningsHistory> existingRows = earningsHistoryRepository
                        .findBySymbolAndAsOfDateBetweenOrderByAsOfDateAsc(symbol, earningsFrom, toDate);
                if (!existingRows.isEmpty()) {
                    earningsHistoryRepository.deleteAll(existingRows);
                }

                int symbolWrites = 0;

                for (YahooFinancePriceService.QuarterlyEpsPoint point : sortedEps) {
                    if (point.eps() == null) {
                        continue;
                    }
                    BigDecimal sourceEps = point.eps().setScale(4, RoundingMode.HALF_UP);
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
                            epsInQuote
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

    @Transactional(readOnly = true)
    public List<PriceHistoryPointResponse> getPriceHistory(String symbol, LocalDate from, LocalDate to) {
        LocalDate toDate = to == null ? LocalDate.now(marketZone) : to;
        LocalDate fromDate = from == null ? toDate.minusMonths(12) : from;

        return priceHistoryRepository
                .findBySymbolAndTradeDateBetweenOrderByTradeDateAsc(normalizeSymbol(symbol), fromDate, toDate)
                .stream()
                .map(h -> new PriceHistoryPointResponse(h.getTradeDate(), h.getClosePrice()))
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

        List<EarningsHistory> earnings = earningsHistoryRepository
                .findBySymbolAndAsOfDateLessThanEqualOrderByAsOfDateAsc(normalized, toDate);
        if (earnings.isEmpty()) {
            return List.of();
        }

        List<PeHistoryPointResponse> points = new ArrayList<>();
        java.util.Map<String, Optional<BigDecimal>> fxCache = new java.util.HashMap<>();
        int epsIndex = 0;
        BigDecimal activeEpsInQuote = null;
        EarningsHistory activeRow = null;
        for (PriceHistory price : prices) {
            while (epsIndex < earnings.size() && !earnings.get(epsIndex).getAsOfDate().isAfter(price.getTradeDate())) {
                activeRow = earnings.get(epsIndex);
                activeEpsInQuote = resolveEpsInQuote(activeRow, "USD", fxCache);
                epsIndex++;
            }

            if (activeEpsInQuote == null || activeEpsInQuote.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            BigDecimal pe = price.getClosePrice().divide(activeEpsInQuote, 4, RoundingMode.HALF_UP);
            points.add(new PeHistoryPointResponse(price.getTradeDate(), pe));
        }

        return points;
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

        java.util.List<PriceHistory> historyRows = symbols.isEmpty()
                ? List.of()
                : priceHistoryRepository.findAllBySymbolInOrderByTradeDateAsc(new ArrayList<>(symbols));

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

        java.util.Map<LocalDate, java.util.Map<String, BigDecimal>> closePriceByDateAndSymbol = new java.util.HashMap<>();
        for (PriceHistory row : historyRows) {
            if (row.getTradeDate().isBefore(curveStartDate)) {
                continue;
            }
            closePriceByDateAndSymbol
                    .computeIfAbsent(row.getTradeDate(), ignored -> new java.util.HashMap<>())
                    .put(row.getSymbol(), row.getClosePrice());
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

    @EventListener(ApplicationReadyEvent.class)
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

        return changed;
    }

    private void upsertPriceHistory(String symbol, LocalDate tradeDate, BigDecimal closePrice) {
        PriceHistory history = priceHistoryRepository.findBySymbolAndTradeDate(symbol, tradeDate)
                .orElseGet(PriceHistory::new);
        history.setSymbol(symbol);
        history.setTradeDate(tradeDate);
        history.setClosePrice(closePrice);
        try {
            priceHistoryRepository.save(history);
        } catch (DataIntegrityViolationException e) {
            PriceHistory existing = priceHistoryRepository.findBySymbolAndTradeDate(symbol, tradeDate)
                    .orElseThrow(() -> e);
            existing.setClosePrice(closePrice);
            priceHistoryRepository.save(existing);
        }
    }

    private void upsertEarningsHistory(String symbol,
                                       LocalDate asOfDate,
                                       BigDecimal sourceEps,
                                       String sourceCurrencyCode,
                                       BigDecimal epsInQuote) {
        EarningsHistory history = earningsHistoryRepository.findBySymbolAndAsOfDate(symbol, asOfDate)
                .orElseGet(EarningsHistory::new);
        history.setSymbol(symbol);
        history.setAsOfDate(asOfDate);
        history.setBasicEps(sourceEps);
        history.setCurrencyCode(sourceCurrencyCode == null ? null : sourceCurrencyCode.trim().toUpperCase());
        history.setSourceEps(sourceEps);
        history.setEpsInQuote(epsInQuote == null ? null : epsInQuote.setScale(4, RoundingMode.HALF_UP));
        earningsHistoryRepository.save(history);
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

        BigDecimal epsInQuote = resolveEpsInQuote(earningsOpt.get(), "USD", new java.util.HashMap<>());
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
        if (earnings == null) {
            return null;
        }
        if (earnings.getEpsInQuote() != null) {
            return earnings.getEpsInQuote().setScale(4, RoundingMode.HALF_UP);
        }

        BigDecimal source = earnings.getSourceEps() != null ? earnings.getSourceEps() : earnings.getBasicEps();
        return convertEpsToQuoteCurrency(source, earnings.getCurrencyCode(), quoteCurrency, fxCache);
    }

    private CsvImportAnalysis analyzeCsv(MultipartFile file, boolean dryRun) {
        int totalRows = 0;
        int importedRows = 0;
        int skippedRows = 0;
        int failedRows = 0;
        List<String> sampleErrors = new ArrayList<>();
        List<TransactionCsvFailedRow> failedRowsDetail = new ArrayList<>();
        java.util.Map<String, BigDecimal> simulatedHoldings = buildSimulatedHoldings();

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
                String[] cells = line.split(",", -1);
                if (cells.length < 5) {
                    failedRows++;
                    addFailedRow(sampleErrors, failedRowsDetail, lineNumber, line, "invalid column count");
                    continue;
                }

                try {
                    OffsetDateTime executedAt = parseCsvExecutedAt(cells[0].trim());
                    String symbol = extractSymbolFromRaw(cells[1].trim());
                    TransactionType type = parseSide(cells[2].trim());
                    BigDecimal quantity = new BigDecimal(cells[3].trim());
                    BigDecimal price = new BigDecimal(cells[4].trim());

                    String note = cells.length >= 6 ? cells[5].trim() : null;

                    if (dryRun) {
                        validateAndApplyDryRunTransaction(simulatedHoldings, symbol, type, quantity);
                    } else {
                        recordTransaction(new TransactionRequest(symbol, type, quantity, price, note, executedAt));
                    }
                    importedRows++;
                } catch (Exception e) {
                    failedRows++;
                    addFailedRow(sampleErrors, failedRowsDetail, lineNumber, line, e.getMessage());
                }
            }
        } catch (IOException e) {
            throw new ResponseStatusException(BAD_REQUEST, "Unable to read CSV file: " + e.getMessage());
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

            snapshot.quantity = newQuantity.setScale(4, RoundingMode.HALF_UP);
            snapshot.averageCost = weightedAverage.setScale(4, RoundingMode.HALF_UP);
            return;
        }

        BigDecimal remainingQuantity = snapshot.quantity.subtract(transaction.getQuantity());
        if (remainingQuantity.compareTo(BigDecimal.ZERO) <= 0) {
            snapshot.quantity = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            snapshot.averageCost = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        } else {
            snapshot.quantity = remainingQuantity.setScale(4, RoundingMode.HALF_UP);
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

    private String escapeCsv(String value) {
        String escaped = value.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }

    private PositionResponse toPositionResponse(Position position) {
        return new PositionResponse(
                position.getId(),
                position.getSymbol(),
                position.getLatestPrice(),
                position.getLatestPe(),
                position.getPriceUpdatedAt(),
                position.getUpdatedAt()
        );
    }

    private TransactionResponse toTransactionResponse(Transaction transaction) {
        return new TransactionResponse(
                transaction.getId(),
                transaction.getSymbol(),
                transaction.getType(),
                transaction.getQuantity(),
                transaction.getPrice(),
                transaction.getNote(),
                transaction.getExecutedAt()
        );
    }

    private DividendResponse toDividendResponse(Dividend dividend) {
        return new DividendResponse(
                dividend.getId(),
                dividend.getSymbol(),
                dividend.getAmount(),
                dividend.getPaidDate(),
                dividend.getCreatedAt()
        );
    }

    private CashAdjustmentResponse toCashAdjustmentResponse(CashAdjustment adjustment) {
        return new CashAdjustmentResponse(
                adjustment.getId(),
                adjustment.getType(),
                adjustment.getAmount(),
                signedCashAdjustment(adjustment),
                adjustment.getOccurredAt(),
                adjustment.getCreatedAt()
        );
    }

    private StockNoteResponse toStockNoteResponse(com.stockportfolio.model.StockNote stockNote) {
        return new StockNoteResponse(
                stockNote.getSymbol(),
                stockNote.getNote(),
                stockNote.getUpdatedAt()
        );
    }

    private OverviewNoteResponse toOverviewNoteResponse(OverviewNote overviewNote) {
        return new OverviewNoteResponse(
                overviewNote.getNoteType(),
                overviewNote.getNote(),
                overviewNote.getUpdatedAt()
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

    private record PortfolioMetrics(
            BigDecimal totalCostBasis,
            BigDecimal totalUnits,
            BigDecimal totalMarketValue,
            BigDecimal totalUnrealizedPnl,
            BigDecimal totalRealizedGain
    ) {
    }

    private static class PositionSnapshot {
        private BigDecimal quantity = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
        private BigDecimal averageCost = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);

        private BigDecimal costBasis() {
            return quantity.multiply(averageCost).setScale(4, RoundingMode.HALF_UP);
        }
    }
}
