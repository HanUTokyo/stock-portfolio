package com.stockportfolio.service;

import com.stockportfolio.dto.*;
import com.stockportfolio.model.Dividend;
import com.stockportfolio.model.PeHistory;
import com.stockportfolio.model.Position;
import com.stockportfolio.model.PriceHistory;
import com.stockportfolio.model.Transaction;
import com.stockportfolio.model.TransactionType;
import com.stockportfolio.repository.DividendRepository;
import com.stockportfolio.repository.PeHistoryRepository;
import com.stockportfolio.repository.PositionRepository;
import com.stockportfolio.repository.PriceHistoryRepository;
import com.stockportfolio.repository.TransactionRepository;
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
import java.util.List;
import java.util.Optional;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
@Transactional
public class PortfolioService {
    private static final DateTimeFormatter CSV_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd H:mm:ss");

    private final PositionRepository positionRepository;
    private final TransactionRepository transactionRepository;
    private final DividendRepository dividendRepository;
    private final PriceHistoryRepository priceHistoryRepository;
    private final PeHistoryRepository peHistoryRepository;
    private final YahooFinancePriceService yahooFinancePriceService;
    private final int retryMaxAttempts;
    private final long retryBackoffMs;
    private final ZoneId marketZone;

    public PortfolioService(
            PositionRepository positionRepository,
            TransactionRepository transactionRepository,
            DividendRepository dividendRepository,
            PriceHistoryRepository priceHistoryRepository,
            PeHistoryRepository peHistoryRepository,
            YahooFinancePriceService yahooFinancePriceService,
            @Value("${app.pricing.retry.max-attempts:3}") int retryMaxAttempts,
            @Value("${app.pricing.retry.backoff-ms:1000}") long retryBackoffMs,
            @Value("${app.pricing.timezone:America/New_York}") String marketTimezone
    ) {
        this.positionRepository = positionRepository;
        this.transactionRepository = transactionRepository;
        this.dividendRepository = dividendRepository;
        this.priceHistoryRepository = priceHistoryRepository;
        this.peHistoryRepository = peHistoryRepository;
        this.yahooFinancePriceService = yahooFinancePriceService;
        this.retryMaxAttempts = Math.max(1, retryMaxAttempts);
        this.retryBackoffMs = Math.max(0, retryBackoffMs);
        this.marketZone = ZoneId.of(marketTimezone);
    }

    public PositionResponse addOrUpdatePosition(PositionRequest request) {
        String symbol = normalizeSymbol(request.symbol());

        Position position = positionRepository.findBySymbolIgnoreCase(symbol)
                .orElseGet(Position::new);

        position.setSymbol(symbol);
        position.setQuantity(request.quantity().setScale(4, RoundingMode.HALF_UP));
        position.setAverageCost(request.averageCost().setScale(4, RoundingMode.HALF_UP));

        Position saved = positionRepository.save(position);
        return toPositionResponse(saved);
    }

    public List<PositionResponse> listPositions() {
        return positionRepository.findAll().stream().map(this::toPositionResponse).toList();
    }

    public TransactionResponse recordTransaction(TransactionRequest request) {
        String symbol = normalizeSymbol(request.symbol());

        Transaction transaction = new Transaction();
        transaction.setSymbol(symbol);
        transaction.setType(request.type());
        transaction.setQuantity(request.quantity().setScale(4, RoundingMode.HALF_UP));
        transaction.setPrice(request.price().setScale(4, RoundingMode.HALF_UP));
        transaction.setExecutedAt(request.executedAt() == null ? OffsetDateTime.now() : request.executedAt());

        applyTransactionToPosition(transaction);

        Transaction saved = transactionRepository.save(transaction);
        return toTransactionResponse(saved);
    }

    public List<TransactionResponse> listTransactions() {
        return transactionRepository.findAll().stream().map(this::toTransactionResponse).toList();
    }

    public void deleteTransaction(Long transactionId) {
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Transaction not found: " + transactionId));

        String deletedSymbol = transaction.getSymbol();
        transactionRepository.delete(transaction);
        rebuildPositionFromTransactions(deletedSymbol);
    }

    public TransactionResponse updateTransaction(Long transactionId, TransactionRequest request) {
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Transaction not found: " + transactionId));

        String oldSymbol = transaction.getSymbol();

        transaction.setSymbol(normalizeSymbol(request.symbol()));
        transaction.setType(request.type());
        transaction.setQuantity(request.quantity().setScale(4, RoundingMode.HALF_UP));
        transaction.setPrice(request.price().setScale(4, RoundingMode.HALF_UP));
        transaction.setExecutedAt(request.executedAt() == null ? OffsetDateTime.now() : request.executedAt());

        Transaction saved = transactionRepository.save(transaction);
        rebuildPositionFromTransactions(oldSymbol);
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
        return positionRepository.findAll().stream()
                .map(position -> {
                    BigDecimal costBasis = position.getQuantity().multiply(position.getAverageCost())
                            .setScale(4, RoundingMode.HALF_UP);
                    BigDecimal latestPrice = position.getLatestPrice();
                    BigDecimal latestPe = position.getLatestPe();
                    BigDecimal marketValue = latestPrice == null
                            ? null
                            : position.getQuantity().multiply(latestPrice).setScale(4, RoundingMode.HALF_UP);
                    BigDecimal unrealizedPnl = marketValue == null
                            ? null
                            : marketValue.subtract(costBasis).setScale(4, RoundingMode.HALF_UP);
                    return new HoldingResponse(
                            position.getSymbol(),
                            position.getQuantity(),
                            position.getAverageCost(),
                            costBasis,
                            latestPrice,
                            latestPe,
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
        List<Position> positions = positionRepository.findAll();

        BigDecimal totalCostBasis = positions.stream()
                .map(p -> p.getQuantity().multiply(p.getAverageCost()))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(4, RoundingMode.HALF_UP);

        BigDecimal totalUnits = positions.stream()
                .map(Position::getQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(4, RoundingMode.HALF_UP);

        BigDecimal totalMarketValue = positions.stream()
                .map(p -> {
                    BigDecimal price = p.getLatestPrice() == null ? p.getAverageCost() : p.getLatestPrice();
                    return p.getQuantity().multiply(price);
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(4, RoundingMode.HALF_UP);

        BigDecimal totalUnrealizedPnl = totalMarketValue.subtract(totalCostBasis).setScale(4, RoundingMode.HALF_UP);
        BigDecimal totalRealizedGain = calculateTotalRealizedGain();

        return new PortfolioSummaryResponse(
                positions.size(),
                totalCostBasis,
                totalUnits,
                totalMarketValue,
                totalUnrealizedPnl,
                totalRealizedGain
        );
    }

    @Transactional(readOnly = true)
    private BigDecimal calculateTotalRealizedGain() {
        List<Transaction> transactions = transactionRepository.findAllByOrderByExecutedAtAscIdAsc();
        java.util.Map<String, PositionSnapshot> snapshots = new java.util.HashMap<>();
        BigDecimal totalRealizedGain = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);

        for (Transaction transaction : transactions) {
            PositionSnapshot snapshot = snapshots.computeIfAbsent(transaction.getSymbol(), s -> new PositionSnapshot());

            if (transaction.getType() == TransactionType.BUY) {
                BigDecimal newQuantity = snapshot.quantity.add(transaction.getQuantity());
                BigDecimal oldCostValue = snapshot.quantity.multiply(snapshot.averageCost);
                BigDecimal buyCostValue = transaction.getQuantity().multiply(transaction.getPrice());
                BigDecimal weightedAverage = newQuantity.compareTo(BigDecimal.ZERO) == 0
                        ? BigDecimal.ZERO
                        : oldCostValue.add(buyCostValue).divide(newQuantity, 4, RoundingMode.HALF_UP);

                snapshot.quantity = newQuantity.setScale(4, RoundingMode.HALF_UP);
                snapshot.averageCost = weightedAverage.setScale(4, RoundingMode.HALF_UP);
                continue;
            }

            BigDecimal sellQuantity = transaction.getQuantity();
            BigDecimal realizedGain = sellQuantity
                    .multiply(transaction.getPrice().subtract(snapshot.averageCost))
                    .setScale(4, RoundingMode.HALF_UP);
            totalRealizedGain = totalRealizedGain.add(realizedGain).setScale(4, RoundingMode.HALF_UP);

            BigDecimal remainingQuantity = snapshot.quantity.subtract(sellQuantity);
            if (remainingQuantity.compareTo(BigDecimal.ZERO) <= 0) {
                snapshot.quantity = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
                snapshot.averageCost = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
            } else {
                snapshot.quantity = remainingQuantity.setScale(4, RoundingMode.HALF_UP);
            }
        }

        return totalRealizedGain;
    }

    public PriceRefreshResponse refreshPrices(String trigger) {
        List<Position> positions = positionRepository.findAllByQuantityGreaterThan(BigDecimal.ZERO);
        int updated = 0;

        for (Position position : positions) {
            Optional<YahooFinancePriceService.YahooMarketSnapshot> snapshotOpt = fetchSnapshotWithRetry(position.getSymbol());
            if (snapshotOpt.isEmpty()) {
                continue;
            }

            YahooFinancePriceService.YahooMarketSnapshot snapshot = snapshotOpt.get();
            boolean changed = applySnapshotToPosition(position, snapshot, OffsetDateTime.now());
            if (changed) {
                updated++;
            }
        }

        positionRepository.saveAll(positions);
        return new PriceRefreshResponse(positions.size(), updated, trigger);
    }

    public MarketCloseSyncResponse syncMarketClose(String trigger) {
        List<Position> positions = positionRepository.findAllByQuantityGreaterThan(BigDecimal.ZERO);
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

            if (snapshot.trailingPe() != null) {
                upsertPeHistory(position.getSymbol(), tradeDate, snapshot.trailingPe().setScale(4, RoundingMode.HALF_UP));
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
        LocalDate toDate = to == null ? LocalDate.now(marketZone) : to;
        LocalDate fromDate = from == null ? toDate.minusMonths(12) : from;

        return peHistoryRepository
                .findBySymbolAndTradeDateBetweenOrderByTradeDateAsc(normalizeSymbol(symbol), fromDate, toDate)
                .stream()
                .map(h -> new PeHistoryPointResponse(h.getTradeDate(), h.getTrailingPe()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AssetCurvePointResponse> getAssetCurve() {
        List<Transaction> transactions = transactionRepository.findAllByOrderByExecutedAtAscIdAsc();
        java.util.Map<String, PositionSnapshot> snapshots = new java.util.HashMap<>();
        java.util.List<AssetCurvePointResponse> points = new java.util.ArrayList<>();

        BigDecimal totalAssets = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);

        for (Transaction transaction : transactions) {
            PositionSnapshot snapshot = snapshots.computeIfAbsent(transaction.getSymbol(), s -> new PositionSnapshot());
            BigDecimal oldCostBasis = snapshot.costBasis();

            if (transaction.getType() == TransactionType.BUY) {
                BigDecimal newQuantity = snapshot.quantity.add(transaction.getQuantity());
                BigDecimal weightedAverage = newQuantity.compareTo(BigDecimal.ZERO) == 0
                        ? BigDecimal.ZERO
                        : oldCostBasis.add(transaction.getQuantity().multiply(transaction.getPrice()))
                        .divide(newQuantity, 4, RoundingMode.HALF_UP);

                snapshot.quantity = newQuantity.setScale(4, RoundingMode.HALF_UP);
                snapshot.averageCost = weightedAverage.setScale(4, RoundingMode.HALF_UP);
            } else {
                BigDecimal remainingQuantity = snapshot.quantity.subtract(transaction.getQuantity());
                if (remainingQuantity.compareTo(BigDecimal.ZERO) < 0) {
                    remainingQuantity = BigDecimal.ZERO;
                }

                snapshot.quantity = remainingQuantity.setScale(4, RoundingMode.HALF_UP);
                if (remainingQuantity.compareTo(BigDecimal.ZERO) == 0) {
                    snapshot.averageCost = BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP);
                }
            }

            BigDecimal newCostBasis = snapshot.costBasis();
            totalAssets = totalAssets.subtract(oldCostBasis).add(newCostBasis).setScale(4, RoundingMode.HALF_UP);
            points.add(new AssetCurvePointResponse(transaction.getExecutedAt(), totalAssets));
        }

        return points;
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
            List<String> symbols = positionRepository.findAllByQuantityGreaterThan(BigDecimal.ZERO).stream()
                    .map(Position::getSymbol)
                    .distinct()
                    .toList();
            if (symbols.isEmpty()) {
                throw new ResponseStatusException(BAD_REQUEST,
                        "No symbols found. Provide ?symbols=AAPL,MSFT or add positions with quantity > 0");
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

        if (snapshot.trailingPe() != null) {
            position.setLatestPe(snapshot.trailingPe().setScale(4, RoundingMode.HALF_UP));
            position.setPeUpdatedAt(now);
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
        priceHistoryRepository.save(history);
    }

    private void upsertPeHistory(String symbol, LocalDate tradeDate, BigDecimal trailingPe) {
        PeHistory history = peHistoryRepository.findBySymbolAndTradeDate(symbol, tradeDate)
                .orElseGet(PeHistory::new);
        history.setSymbol(symbol);
        history.setTradeDate(tradeDate);
        history.setTrailingPe(trailingPe);
        peHistoryRepository.save(history);
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

                    if (dryRun) {
                        validateAndApplyDryRunTransaction(simulatedHoldings, symbol, type, quantity);
                    } else {
                        recordTransaction(new TransactionRequest(symbol, type, quantity, price, executedAt));
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
        positionRepository.findAllByQuantityGreaterThan(BigDecimal.ZERO)
                .forEach(position -> holdings.put(position.getSymbol(), position.getQuantity()));
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

    private void applyTransactionToPosition(Transaction transaction) {
        Position position = positionRepository.findBySymbolIgnoreCase(transaction.getSymbol())
                .orElseGet(() -> {
                    Position newPosition = new Position();
                    newPosition.setSymbol(transaction.getSymbol());
                    newPosition.setQuantity(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP));
                    newPosition.setAverageCost(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP));
                    return newPosition;
                });

        BigDecimal currentQty = position.getQuantity();
        BigDecimal currentAvg = position.getAverageCost();

        if (transaction.getType() == TransactionType.BUY) {
            BigDecimal newQty = currentQty.add(transaction.getQuantity());
            BigDecimal oldCostValue = currentQty.multiply(currentAvg);
            BigDecimal newCostValue = transaction.getQuantity().multiply(transaction.getPrice());
            BigDecimal weightedAverage = newQty.compareTo(BigDecimal.ZERO) == 0
                    ? BigDecimal.ZERO
                    : oldCostValue.add(newCostValue)
                    .divide(newQty, 4, RoundingMode.HALF_UP);

            position.setQuantity(newQty.setScale(4, RoundingMode.HALF_UP));
            position.setAverageCost(weightedAverage.setScale(4, RoundingMode.HALF_UP));
        } else {
            if (currentQty.compareTo(transaction.getQuantity()) < 0) {
                throw new ResponseStatusException(BAD_REQUEST,
                        "Sell quantity exceeds current holding for symbol: " + transaction.getSymbol());
            }

            BigDecimal newQty = currentQty.subtract(transaction.getQuantity()).setScale(4, RoundingMode.HALF_UP);
            position.setQuantity(newQty);

            if (newQty.compareTo(BigDecimal.ZERO) == 0) {
                position.setAverageCost(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP));
            }
        }

        positionRepository.save(position);
    }

    private void rebuildPositionFromTransactions(String forceIncludeSymbol) {
        List<Transaction> transactions = transactionRepository.findAllByOrderByExecutedAtAscIdAsc();
        java.util.Map<String, PositionSnapshot> snapshots = new java.util.HashMap<>();

        for (Transaction transaction : transactions) {
            PositionSnapshot snapshot = snapshots.computeIfAbsent(transaction.getSymbol(), s -> new PositionSnapshot());
            applyTransactionToSnapshot(snapshot, transaction);
        }

        java.util.Set<String> symbolsToUpdate = new java.util.HashSet<>(snapshots.keySet());
        if (forceIncludeSymbol != null && !forceIncludeSymbol.isBlank()) {
            symbolsToUpdate.add(forceIncludeSymbol);
        }

        for (String symbol : symbolsToUpdate) {
            PositionSnapshot snapshot = snapshots.getOrDefault(symbol, new PositionSnapshot());
            Position position = positionRepository.findBySymbolIgnoreCase(symbol)
                    .orElseGet(() -> {
                        Position p = new Position();
                        p.setSymbol(symbol);
                        p.setQuantity(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP));
                        p.setAverageCost(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP));
                        return p;
                    });
            position.setQuantity(snapshot.quantity.setScale(4, RoundingMode.HALF_UP));
            position.setAverageCost(snapshot.averageCost.setScale(4, RoundingMode.HALF_UP));
            positionRepository.save(position);
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
                position.getQuantity(),
                position.getAverageCost(),
                position.getLatestPrice(),
                position.getLatestPe(),
                position.getPriceUpdatedAt(),
                position.getPeUpdatedAt(),
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

    private record CsvImportAnalysis(
            int totalRows,
            int importedRows,
            int skippedRows,
            int failedRows,
            List<String> sampleErrors,
            List<TransactionCsvFailedRow> failedRowsDetail
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
