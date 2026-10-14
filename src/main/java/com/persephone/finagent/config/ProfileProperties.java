package com.persephone.finagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Settings for the user-habit adaptation layer. */
@Component
@ConfigurationProperties(prefix = "finagent.profile")
public class ProfileProperties {

    private String storeDir;
    /** Observations before a metric is treated as an established habit. */
    private int metricAffinityThreshold = 3;

    public String getStoreDir() { return storeDir; }
    public void setStoreDir(String storeDir) { this.storeDir = storeDir; }
    public int getMetricAffinityThreshold() { return metricAffinityThreshold; }
    public void setMetricAffinityThreshold(int metricAffinityThreshold) { this.metricAffinityThreshold = metricAffinityThreshold; }
}
