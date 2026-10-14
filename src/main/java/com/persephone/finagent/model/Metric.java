package com.persephone.finagent.model;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Canonical metric taxonomy.
 *
 * <p>The hard part of cross-company financial comparison is that companies tag
 * the same economic quantity with different XBRL concepts. One filer reports
 * {@code us-gaap:Revenues}, another {@code us-gaap:RevenueFromContractWithCustomerExcludingAssessedTax},
 * a third a custom extension concept. Comparing raw tags across peers yields
 * garbage.
 *
 * <p>So every fact is normalised to a {@link Metric} on ingest. Peer tables are
 * then built on canonical keys, and — importantly — the <i>original</i> concept
 * is preserved on the fact ({@code sourceConcept}) and surfaced in the provenance,
 * so an analyst can always see which tag actually produced a number.
 */
public enum Metric {

    REVENUE("Revenue", "us-gaap:Revenues",
            "us-gaap:RevenueFromContractWithCustomerExcludingAssessedTax",
            "us-gaap:SalesRevenueNet",
            "us-gaap:RevenueFromContractWithCustomerIncludingAssessedTax"),
    COST_OF_REVENUE("Cost of Revenue", "us-gaap:CostOfRevenue",
            "us-gaap:CostOfGoodsAndServicesSold",
            "us-gaap:CostOfGoodsSold"),
    GROSS_PROFIT("Gross Profit", "us-gaap:GrossProfit"),
    RND_EXPENSE("R&D Expense", "us-gaap:ResearchAndDevelopmentExpense"),
    SGNA_EXPENSE("SG&A Expense", "us-gaap:SellingGeneralAndAdministrativeExpense"),
    OPERATING_INCOME("Operating Income", "us-gaap:OperatingIncomeLoss"),
    NET_INCOME("Net Income", "us-gaap:NetIncomeLoss",
            "us-gaap:ProfitLoss",
            "us-gaap:NetIncomeLossAvailableToCommonStockholdersBasic"),
    EPS_DILUTED("EPS (Diluted)", "us-gaap:EarningsPerShareDiluted"),
    EPS_BASIC("EPS (Basic)", "us-gaap:EarningsPerShareBasic"),

    ASSETS("Total Assets", "us-gaap:Assets"),
    LIABILITIES("Total Liabilities", "us-gaap:Liabilities"),
    STOCKHOLDERS_EQUITY("Stockholders' Equity",
            "us-gaap:StockholdersEquity",
            "us-gaap:StockholdersEquityIncludingPortionAttributableToNoncontrollingInterest"),
    CASH_AND_EQUIVALENTS("Cash & Equivalents",
            "us-gaap:CashAndCashEquivalentsAtCarryingValue",
            "us-gaap:CashCashEquivalentsRestrictedCashAndRestrictedCashEquivalents"),
    LONG_TERM_DEBT("Long-Term Debt",
            "us-gaap:LongTermDebtNoncurrent",
            "us-gaap:LongTermDebt"),
    INVENTORY("Inventory", "us-gaap:InventoryNet",
            "us-gaap:InventoryFinishedGoodsNetOfReserves"),
    ACCOUNTS_RECEIVABLE("Accounts Receivable",
            "us-gaap:AccountsReceivableNetCurrent",
            "us-gaap:ReceivablesNetCurrent"),

    OPERATING_CASH_FLOW("Operating Cash Flow",
            "us-gaap:NetCashProvidedByUsedInOperatingActivities"),
    CAPEX("Capital Expenditures",
            "us-gaap:PaymentsToAcquirePropertyPlantAndEquipment",
            "us-gaap:PaymentsToAcquireProductiveAssets"),
    FREE_CASH_FLOW("Free Cash Flow", "__derived__");

    /** Human-readable label used in tables and reports. */
    private final String displayName;
    /** XBRL concepts that map to this metric, in priority order. */
    private final List<String> sourceConcepts;

    Metric(String displayName, String... sourceConcepts) {
        this.displayName = displayName;
        this.sourceConcepts = Collections.unmodifiableList(Arrays.asList(sourceConcepts));
    }

    public String displayName() { return displayName; }

    public List<String> sourceConcepts() { return sourceConcepts; }

    /** True when this metric is computed rather than read from a single tag. */
    public boolean isDerived() {
        return sourceConcepts.size() == 1 && "__derived__".equals(sourceConcepts.get(0));
    }

    // --- Reverse index: normalised concept name -> Metric ---------------------

    private static final Map<String, Metric> CONCEPT_INDEX;

    static {
        Map<String, Metric> idx = new LinkedHashMap<>();
        for (Metric m : values()) {
            if (m.isDerived()) continue;
            for (String c : m.sourceConcepts) {
                // First writer wins: preserves the priority order declared above,
                // so ambiguous concepts (e.g. ProfitLoss) resolve deterministically.
                idx.putIfAbsent(normalise(c), m);
            }
        }
        CONCEPT_INDEX = Collections.unmodifiableMap(idx);
    }

    /**
     * Resolve an XBRL concept to a canonical metric.
     *
     * <p>Handles the two forms EDGAR emits: {@code us-gaap:Revenues} and
     * {@code us-gaap/Revenues}. Case and separator differences are normalised so
     * a small taxonomy drift does not silently drop a fact.
     */
    public static Optional<Metric> fromConcept(String concept) {
        if (concept == null || concept.isEmpty()) return Optional.empty();
        return Optional.ofNullable(CONCEPT_INDEX.get(normalise(concept)));
    }

    private static String normalise(String concept) {
        return concept.trim()
                .replace('/', ':')
                .replace("_", "")
                .toLowerCase(Locale.ROOT);
    }

    public static Optional<Metric> fromName(String name) {
        if (name == null) return Optional.empty();
        String n = name.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace("-", "_");
        for (Metric m : values()) {
            if (m.name().equals(n)) return Optional.of(m);
        }
        // Tolerate analyst phrasing: "revenue", "net income", "free cash flow".
        for (Metric m : values()) {
            if (m.displayName.equalsIgnoreCase(name.trim())) return Optional.of(m);
        }
        return Optional.empty();
    }
}
