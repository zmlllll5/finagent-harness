package com.persephone.finagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Financial research agent harness.
 *
 * <p>Run with {@code mvn spring-boot:run}. The service starts with no external
 * configuration and no API key: the fact store begins empty and filings are
 * pulled from SEC EDGAR on demand.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class FinAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(FinAgentApplication.class, args);
    }
}
