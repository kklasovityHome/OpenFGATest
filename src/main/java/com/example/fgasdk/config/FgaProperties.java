package com.example.fgasdk.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the OpenFGA client.
 * Values are read from application.properties under the "openfga" prefix.
 * Registered via @EnableConfigurationProperties in FgaClientConfig.
 */
@ConfigurationProperties(prefix = "openfga")
public class FgaProperties {

    /** Base URL of the OpenFGA server, e.g. http://localhost:8082 */
    private String apiUrl = "http://localhost:8082";

    /** Store ID – populated after bootstrap or set manually */
    private String storeId = "";

    /** Authorization model ID – populated after bootstrap or set manually */
    private String modelId = "";

    /** Optional pre-shared-key token for OpenFGA auth */
    private String apiToken = "";

    /** Whether to seed demo tuples on startup */
    private boolean seedData = true;

    public String getApiUrl() { return apiUrl; }
    public void setApiUrl(String apiUrl) { this.apiUrl = apiUrl; }

    public String getStoreId() { return storeId; }
    public void setStoreId(String storeId) { this.storeId = storeId; }

    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }

    public String getApiToken() { return apiToken; }
    public void setApiToken(String apiToken) { this.apiToken = apiToken; }

    public boolean isSeedData() { return seedData; }
    public void setSeedData(boolean seedData) { this.seedData = seedData; }
}
