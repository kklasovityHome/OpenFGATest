package com.example.fgasdk.dto;

public class CheckResponse {
    private boolean allowed;
    private String explanation;

    public CheckResponse() {}

    public CheckResponse(boolean allowed, String explanation) {
        this.allowed = allowed;
        this.explanation = explanation;
    }

    public boolean isAllowed() { return allowed; }
    public void setAllowed(boolean allowed) { this.allowed = allowed; }

    public String getExplanation() { return explanation; }
    public void setExplanation(String explanation) { this.explanation = explanation; }
}
