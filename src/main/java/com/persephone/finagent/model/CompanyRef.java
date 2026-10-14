package com.persephone.finagent.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Identity of a company under coverage. Keyed on CIK rather than ticker, because
 * tickers get reused and renamed while the CIK is the stable SEC identifier.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class CompanyRef {

    private final String cik;
    private final String ticker;
    private final String name;

    public CompanyRef(String cik, String ticker, String name) {
        this.cik = cik;
        this.ticker = ticker;
        this.name = name;
    }

    /**
     * CIK as EDGAR expects it in URLs: zero-padded to 10 digits.
     */
    public String paddedCik() {
        if (cik == null) return null;
        String digits = cik.replaceAll("[^0-9]", "");
        StringBuilder sb = new StringBuilder(digits);
        while (sb.length() < 10) sb.insert(0, '0');
        return sb.toString();
    }

    public String getCik() { return cik; }
    public String getTicker() { return ticker; }
    public String getName() { return name; }

    @Override
    public String toString() {
        return ticker + " (" + name + ", CIK " + paddedCik() + ")";
    }
}
