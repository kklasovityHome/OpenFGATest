package com.example.fgasdk.dto;

public class BootstrapResponse {
    private String storeId;
    private String modelId;
    private String message;

    public BootstrapResponse() {}

    public BootstrapResponse(String storeId, String modelId, String message) {
        this.storeId = storeId;
        this.modelId = modelId;
        this.message = message;
    }

    public String getStoreId() { return storeId; }
    public void setStoreId(String storeId) { this.storeId = storeId; }

    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
