package com.stockportfolio.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "positions", uniqueConstraints = @UniqueConstraint(columnNames = "symbol"))
public class Position {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(name = "latest_price", precision = 19, scale = 4)
    private BigDecimal latestPrice;

    @Column(name = "latest_pe", precision = 19, scale = 4)
    private BigDecimal latestPe;

    @Column(name = "shares_outstanding", precision = 24, scale = 4)
    private BigDecimal sharesOutstanding;

    @Column(name = "shares_outstanding_override", precision = 24, scale = 4)
    private BigDecimal sharesOutstandingOverride;

    @Column(name = "shares_outstanding_source", length = 40)
    private String sharesOutstandingSource;

    @Column(name = "shares_outstanding_updated_at")
    private OffsetDateTime sharesOutstandingUpdatedAt;

    @Column(name = "quote_currency", length = 10)
    private String quoteCurrency;

    @Column(precision = 19, scale = 6)
    private BigDecimal beta;

    @Column(name = "beta_source", length = 80)
    private String betaSource;

    @Column(name = "beta_updated_at")
    private OffsetDateTime betaUpdatedAt;

    @Column(name = "price_updated_at")
    private OffsetDateTime priceUpdatedAt;

    @Column(name = "asset_class", length = 40)
    private String assetClass;

    @Column(name = "instrument_type", length = 60)
    private String instrumentType;

    @Column(length = 20)
    private String underlying;

    @Column(length = 80)
    private String sector;

    @Column(length = 80)
    private String region;

    @Column(name = "metadata_updated_at")
    private OffsetDateTime metadataUpdatedAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touchTimestamp() {
        updatedAt = OffsetDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public BigDecimal getLatestPrice() {
        return latestPrice;
    }

    public void setLatestPrice(BigDecimal latestPrice) {
        this.latestPrice = latestPrice;
    }

    public OffsetDateTime getPriceUpdatedAt() {
        return priceUpdatedAt;
    }

    public void setPriceUpdatedAt(OffsetDateTime priceUpdatedAt) {
        this.priceUpdatedAt = priceUpdatedAt;
    }

    public String getAssetClass() {
        return assetClass;
    }

    public void setAssetClass(String assetClass) {
        this.assetClass = assetClass;
    }

    public String getInstrumentType() {
        return instrumentType;
    }

    public void setInstrumentType(String instrumentType) {
        this.instrumentType = instrumentType;
    }

    public String getUnderlying() {
        return underlying;
    }

    public void setUnderlying(String underlying) {
        this.underlying = underlying;
    }

    public String getSector() {
        return sector;
    }

    public void setSector(String sector) {
        this.sector = sector;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public OffsetDateTime getMetadataUpdatedAt() {
        return metadataUpdatedAt;
    }

    public void setMetadataUpdatedAt(OffsetDateTime metadataUpdatedAt) {
        this.metadataUpdatedAt = metadataUpdatedAt;
    }

    public BigDecimal getLatestPe() {
        return latestPe;
    }

    public void setLatestPe(BigDecimal latestPe) {
        this.latestPe = latestPe;
    }

    public BigDecimal getSharesOutstanding() {
        return sharesOutstanding;
    }

    public void setSharesOutstanding(BigDecimal sharesOutstanding) {
        this.sharesOutstanding = sharesOutstanding;
    }

    public BigDecimal getSharesOutstandingOverride() {
        return sharesOutstandingOverride;
    }

    public void setSharesOutstandingOverride(BigDecimal sharesOutstandingOverride) {
        this.sharesOutstandingOverride = sharesOutstandingOverride;
    }

    public String getSharesOutstandingSource() {
        return sharesOutstandingSource;
    }

    public void setSharesOutstandingSource(String sharesOutstandingSource) {
        this.sharesOutstandingSource = sharesOutstandingSource;
    }

    public OffsetDateTime getSharesOutstandingUpdatedAt() {
        return sharesOutstandingUpdatedAt;
    }

    public void setSharesOutstandingUpdatedAt(OffsetDateTime sharesOutstandingUpdatedAt) {
        this.sharesOutstandingUpdatedAt = sharesOutstandingUpdatedAt;
    }

    public String getQuoteCurrency() { return quoteCurrency; }
    public void setQuoteCurrency(String quoteCurrency) { this.quoteCurrency = quoteCurrency; }
    public BigDecimal getBeta() { return beta; }
    public void setBeta(BigDecimal beta) { this.beta = beta; }
    public String getBetaSource() { return betaSource; }
    public void setBetaSource(String betaSource) { this.betaSource = betaSource; }
    public OffsetDateTime getBetaUpdatedAt() { return betaUpdatedAt; }
    public void setBetaUpdatedAt(OffsetDateTime betaUpdatedAt) { this.betaUpdatedAt = betaUpdatedAt; }

    public BigDecimal getEffectiveSharesOutstanding() {
        return sharesOutstandingOverride == null ? sharesOutstanding : sharesOutstandingOverride;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
