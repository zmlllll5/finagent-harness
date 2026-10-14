package com.persephone.finagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** EDGAR access settings: identification, rate limiting, caching. */
@Component
@ConfigurationProperties(prefix = "finagent.edgar")
public class EdgarProperties {

    /**
     * SEC requires a descriptive User-Agent including contact info and will
     * block anonymous automated traffic. This is a real operational constraint,
     * not a nicety — see https://www.sec.gov/os/accessing-edgar-data
     */
    private String userAgent = "Persephone Research Agent research@example.com";

    /** SEC fair-access ceiling is 10 req/s; default to half that for headroom. */
    private int requestsPerSecond = 5;
    private int timeoutMs = 20000;
    private int retryAttempts = 3;
    private String cacheDir;
    private int cacheTtlMinutes = 720;

    /** When true, serve from bundled sample payloads and never touch the network. */
    private boolean useMock = false;

    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    public int getRequestsPerSecond() { return requestsPerSecond; }
    public void setRequestsPerSecond(int requestsPerSecond) { this.requestsPerSecond = requestsPerSecond; }
    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
    public int getRetryAttempts() { return retryAttempts; }
    public void setRetryAttempts(int retryAttempts) { this.retryAttempts = retryAttempts; }
    public String getCacheDir() { return cacheDir; }
    public void setCacheDir(String cacheDir) { this.cacheDir = cacheDir; }
    public int getCacheTtlMinutes() { return cacheTtlMinutes; }
    public void setCacheTtlMinutes(int cacheTtlMinutes) { this.cacheTtlMinutes = cacheTtlMinutes; }
    public boolean isUseMock() { return useMock; }
    public void setUseMock(boolean useMock) { this.useMock = useMock; }
}
