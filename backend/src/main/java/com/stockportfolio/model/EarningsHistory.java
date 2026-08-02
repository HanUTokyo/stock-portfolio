package com.stockportfolio.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(
        name = "earnings_history",
        uniqueConstraints = @UniqueConstraint(columnNames = {"symbol", "as_of_date"})
)
public class EarningsHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String symbol;

    @Column(name = "as_of_date", nullable = false)
    private LocalDate asOfDate;

    @Column(name = "basic_eps", precision = 19, scale = 4)
    private BigDecimal basicEps;

    @Column(name = "diluted_eps", precision = 19, scale = 6)
    private BigDecimal dilutedEps;

    @Column(name = "diluted_weighted_average_shares", precision = 24, scale = 4)
    private BigDecimal dilutedWeightedAverageShares;

    @Column(name = "currency_code", length = 10)
    private String currencyCode;

    @Column(name = "source_eps", precision = 19, scale = 4)
    private BigDecimal sourceEps;

    @Column(name = "eps_in_quote", precision = 19, scale = 4)
    private BigDecimal epsInQuote;

    @Column(name = "ttm_eps", precision = 19, scale = 4)
    private BigDecimal ttmEps;

    @Column(name = "forward_eps", precision = 19, scale = 4)
    private BigDecimal forwardEps;

    @Column(name = "cash_flow", precision = 19, scale = 4)
    private BigDecimal cashFlow;

    @Column(name = "fcf", precision = 19, scale = 4)
    private BigDecimal fcf;

    @Column(name = "capex", precision = 19, scale = 4)
    private BigDecimal capex;

    @Column(name = "adjusted_fcf", precision = 19, scale = 4)
    private BigDecimal adjustedFcf;

    @Column(name = "roe", precision = 19, scale = 4)
    private BigDecimal roe;

    @Column(name = "roic", precision = 19, scale = 4)
    private BigDecimal roic;

    @Column(name = "gross_margin", precision = 19, scale = 4)
    private BigDecimal grossMargin;

    @Column(name = "revenue", precision = 19, scale = 4)
    private BigDecimal revenue;

    @Column(name = "gross_profit", precision = 19, scale = 4)
    private BigDecimal grossProfit;

    @Column(name = "operating_income", precision = 19, scale = 4)
    private BigDecimal operatingIncome;

    @Column(name = "interest_expense", precision = 19, scale = 4)
    private BigDecimal interestExpense;

    @Column(name = "net_income", precision = 19, scale = 4)
    private BigDecimal netIncome;

    @Column(name = "stockholders_equity", precision = 19, scale = 4)
    private BigDecimal stockholdersEquity;

    @Column(name = "total_debt", precision = 19, scale = 4)
    private BigDecimal totalDebt;

    @Column(name = "cash_and_equivalents", precision = 19, scale = 4)
    private BigDecimal cashAndEquivalents;

    @Column(name = "short_term_investments", precision = 24, scale = 4)
    private BigDecimal shortTermInvestments;

    @Column(name = "noncurrent_marketable_securities", precision = 24, scale = 4)
    private BigDecimal noncurrentMarketableSecurities;

    @Column(name = "tax_provision", precision = 19, scale = 4)
    private BigDecimal taxProvision;

    @Column(name = "pretax_income", precision = 19, scale = 4)
    private BigDecimal pretaxIncome;

    @Column(name = "invested_capital", precision = 19, scale = 4)
    private BigDecimal investedCapital;

    @Column(name = "depreciation_amortization", precision = 19, scale = 4)
    private BigDecimal depreciationAmortization;

    @Column(name = "change_in_working_capital", precision = 19, scale = 4)
    private BigDecimal changeInWorkingCapital;

    @Column(name = "net_borrowing", precision = 19, scale = 4)
    private BigDecimal netBorrowing;

    @Column(name = "share_repurchases", precision = 19, scale = 4)
    private BigDecimal shareRepurchases;

    @Column(name = "total_assets", precision = 19, scale = 4)
    private BigDecimal totalAssets;

    @Column(name = "fiscal_year")
    private Integer fiscalYear;

    @Column(name = "fiscal_period", length = 8)
    private String fiscalPeriod;

    @Column(name = "filing_date")
    private LocalDate filingDate;

    @Lob
    @Column(name = "field_metadata", columnDefinition = "text")
    private String fieldMetadata;

    @Column(name = "captured_at", nullable = false)
    private OffsetDateTime capturedAt;

    @PrePersist
    @PreUpdate
    void touchCapturedAt() {
        capturedAt = OffsetDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSymbol() {
        return symbol;
    }

    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }

    public LocalDate getAsOfDate() {
        return asOfDate;
    }

    public void setAsOfDate(LocalDate asOfDate) {
        this.asOfDate = asOfDate;
    }

    public BigDecimal getBasicEps() {
        return basicEps;
    }

    public void setBasicEps(BigDecimal basicEps) {
        this.basicEps = basicEps;
    }

    public BigDecimal getDilutedEps() { return dilutedEps; }
    public void setDilutedEps(BigDecimal dilutedEps) { this.dilutedEps = dilutedEps; }
    public BigDecimal getDilutedWeightedAverageShares() { return dilutedWeightedAverageShares; }
    public void setDilutedWeightedAverageShares(BigDecimal value) { this.dilutedWeightedAverageShares = value; }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public void setCurrencyCode(String currencyCode) {
        this.currencyCode = currencyCode;
    }

    public OffsetDateTime getCapturedAt() {
        return capturedAt;
    }

    public void setCapturedAt(OffsetDateTime capturedAt) {
        this.capturedAt = capturedAt;
    }

    public BigDecimal getSourceEps() {
        return sourceEps;
    }

    public void setSourceEps(BigDecimal sourceEps) {
        this.sourceEps = sourceEps;
    }

    public BigDecimal getEpsInQuote() {
        return epsInQuote;
    }

    public void setEpsInQuote(BigDecimal epsInQuote) {
        this.epsInQuote = epsInQuote;
    }

    public BigDecimal getTtmEps() {
        return ttmEps;
    }

    public void setTtmEps(BigDecimal ttmEps) {
        this.ttmEps = ttmEps;
    }

    public BigDecimal getForwardEps() {
        return forwardEps;
    }

    public void setForwardEps(BigDecimal forwardEps) {
        this.forwardEps = forwardEps;
    }

    public BigDecimal getCashFlow() {
        return cashFlow;
    }

    public void setCashFlow(BigDecimal cashFlow) {
        this.cashFlow = cashFlow;
    }

    public BigDecimal getFcf() {
        return fcf;
    }

    public void setFcf(BigDecimal fcf) {
        this.fcf = fcf;
    }

    public BigDecimal getCapex() {
        return capex;
    }

    public void setCapex(BigDecimal capex) {
        this.capex = capex;
    }

    public BigDecimal getAdjustedFcf() {
        return adjustedFcf;
    }

    public void setAdjustedFcf(BigDecimal adjustedFcf) {
        this.adjustedFcf = adjustedFcf;
    }

    public BigDecimal getRoe() {
        return roe;
    }

    public void setRoe(BigDecimal roe) {
        this.roe = roe;
    }

    public BigDecimal getRoic() {
        return roic;
    }

    public void setRoic(BigDecimal roic) {
        this.roic = roic;
    }

    public BigDecimal getGrossMargin() {
        return grossMargin;
    }

    public void setGrossMargin(BigDecimal grossMargin) {
        this.grossMargin = grossMargin;
    }

    public BigDecimal getRevenue() {
        return revenue;
    }

    public void setRevenue(BigDecimal revenue) {
        this.revenue = revenue;
    }

    public BigDecimal getGrossProfit() {
        return grossProfit;
    }

    public void setGrossProfit(BigDecimal grossProfit) {
        this.grossProfit = grossProfit;
    }

    public BigDecimal getOperatingIncome() {
        return operatingIncome;
    }

    public void setOperatingIncome(BigDecimal operatingIncome) {
        this.operatingIncome = operatingIncome;
    }

    public BigDecimal getInterestExpense() {
        return interestExpense;
    }

    public void setInterestExpense(BigDecimal interestExpense) {
        this.interestExpense = interestExpense;
    }

    public BigDecimal getNetIncome() {
        return netIncome;
    }

    public void setNetIncome(BigDecimal netIncome) {
        this.netIncome = netIncome;
    }

    public BigDecimal getStockholdersEquity() {
        return stockholdersEquity;
    }

    public void setStockholdersEquity(BigDecimal stockholdersEquity) {
        this.stockholdersEquity = stockholdersEquity;
    }

    public BigDecimal getTotalDebt() {
        return totalDebt;
    }

    public void setTotalDebt(BigDecimal totalDebt) {
        this.totalDebt = totalDebt;
    }

    public BigDecimal getCashAndEquivalents() {
        return cashAndEquivalents;
    }

    public void setCashAndEquivalents(BigDecimal cashAndEquivalents) {
        this.cashAndEquivalents = cashAndEquivalents;
    }

    public BigDecimal getShortTermInvestments() { return shortTermInvestments; }
    public void setShortTermInvestments(BigDecimal value) { this.shortTermInvestments = value; }
    public BigDecimal getNoncurrentMarketableSecurities() { return noncurrentMarketableSecurities; }
    public void setNoncurrentMarketableSecurities(BigDecimal value) { this.noncurrentMarketableSecurities = value; }

    public BigDecimal getTaxProvision() {
        return taxProvision;
    }

    public void setTaxProvision(BigDecimal taxProvision) {
        this.taxProvision = taxProvision;
    }

    public BigDecimal getPretaxIncome() {
        return pretaxIncome;
    }

    public void setPretaxIncome(BigDecimal pretaxIncome) {
        this.pretaxIncome = pretaxIncome;
    }

    public BigDecimal getInvestedCapital() {
        return investedCapital;
    }

    public void setInvestedCapital(BigDecimal investedCapital) {
        this.investedCapital = investedCapital;
    }

    public BigDecimal getDepreciationAmortization() { return depreciationAmortization; }
    public void setDepreciationAmortization(BigDecimal value) { this.depreciationAmortization = value; }
    public BigDecimal getChangeInWorkingCapital() { return changeInWorkingCapital; }
    public void setChangeInWorkingCapital(BigDecimal value) { this.changeInWorkingCapital = value; }
    public BigDecimal getNetBorrowing() { return netBorrowing; }
    public void setNetBorrowing(BigDecimal value) { this.netBorrowing = value; }
    public BigDecimal getShareRepurchases() { return shareRepurchases; }
    public void setShareRepurchases(BigDecimal value) { this.shareRepurchases = value; }
    public BigDecimal getTotalAssets() { return totalAssets; }
    public void setTotalAssets(BigDecimal value) { this.totalAssets = value; }
    public Integer getFiscalYear() { return fiscalYear; }
    public void setFiscalYear(Integer fiscalYear) { this.fiscalYear = fiscalYear; }
    public String getFiscalPeriod() { return fiscalPeriod; }
    public void setFiscalPeriod(String fiscalPeriod) { this.fiscalPeriod = fiscalPeriod; }
    public LocalDate getFilingDate() { return filingDate; }
    public void setFilingDate(LocalDate filingDate) { this.filingDate = filingDate; }
    public String getFieldMetadata() { return fieldMetadata; }
    public void setFieldMetadata(String fieldMetadata) { this.fieldMetadata = fieldMetadata; }
}
