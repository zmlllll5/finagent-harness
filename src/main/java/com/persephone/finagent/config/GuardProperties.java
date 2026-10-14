package com.persephone.finagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Tuning for the anti-hallucination guard layer. */
@Component
@ConfigurationProperties(prefix = "finagent.guard")
public class GuardProperties {

    /** Relative tolerance when binding a proposed number to a filing fact. */
    private double numericTolerance = 0.0001;

    /**
     * Verify Assets == Liabilities + Equity. This catches a class of error the
     * LLM cannot make but a bad extraction pipeline can: mismatched period
     * alignment across the three balance-sheet lines.
     */
    private boolean enableBalanceSheetIdentityCheck = true;

    /**
     * Remove unverifiable numbers from output rather than merely warning.
     * Default is true: a warning that scrolls past is not a control.
     */
    private boolean stripUnverifiedNumbers = true;

    public double getNumericTolerance() { return numericTolerance; }
    public void setNumericTolerance(double numericTolerance) { this.numericTolerance = numericTolerance; }
    public boolean isEnableBalanceSheetIdentityCheck() { return enableBalanceSheetIdentityCheck; }
    public void setEnableBalanceSheetIdentityCheck(boolean v) { this.enableBalanceSheetIdentityCheck = v; }
    public boolean isStripUnverifiedNumbers() { return stripUnverifiedNumbers; }
    public void setStripUnverifiedNumbers(boolean v) { this.stripUnverifiedNumbers = v; }
}
