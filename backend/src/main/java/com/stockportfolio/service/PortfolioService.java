package com.stockportfolio.service;

import com.stockportfolio.dto.*;
import com.stockportfolio.model.Position;
import com.stockportfolio.model.Transaction;
import com.stockportfolio.model.TransactionType;
import com.stockportfolio.repository.PositionRepository;
import com.stockportfolio.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

@Service
@Transactional
public class PortfolioService {

    private final PositionRepository positionRepository;
    private final TransactionRepository transactionRepository;

    public PortfolioService(PositionRepository positionRepository, TransactionRepository transactionRepository) {
        this.positionRepository = positionRepository;
        this.transactionRepository = transactionRepository;
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

    @Transactional(readOnly = true)
    public List<HoldingResponse> getHoldings() {
        return positionRepository.findAll().stream()
                .map(position -> {
                    BigDecimal costBasis = position.getQuantity().multiply(position.getAverageCost())
                            .setScale(4, RoundingMode.HALF_UP);
                    return new HoldingResponse(
                            position.getSymbol(),
                            position.getQuantity(),
                            position.getAverageCost(),
                            costBasis
                    );
                })
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

        return new PortfolioSummaryResponse(positions.size(), totalCostBasis, totalUnits);
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

    private String normalizeSymbol(String symbol) {
        return symbol.trim().toUpperCase();
    }

    private PositionResponse toPositionResponse(Position position) {
        return new PositionResponse(
                position.getId(),
                position.getSymbol(),
                position.getQuantity(),
                position.getAverageCost(),
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
}
