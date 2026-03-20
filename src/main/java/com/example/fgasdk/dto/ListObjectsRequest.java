package com.example.fgasdk.dto;

import java.util.List;
import java.util.Map;

/**
 * Request DTO for the /api/authz/list-objects endpoint.
 *
 * Supports the same IdP role injection as CheckRequest via "contextualRoles".
 * Roles listed here are passed as contextual tuples for this query only —
 * they are never persisted in the store.
 *
 * Example:
 * {
 *   "userId":          "user:alice",
 *   "relation":        "can_view",
 *   "objectType":      "document",
 *   "contextualRoles": ["role:proj-editor"],
 *   "context":         { "now": "2026-03-19T10:00:00Z" }
 * }
 */
public class ListObjectsRequest {
    private String userId;
    private String relation;
    private String objectType;

    /**
     * Roles from the IdP JWT, injected at request time as contextual tuples.
     * Each entry: "role:some-role-name"
     */
    private List<String> contextualRoles;

    /** Optional CEL context values for ABAC condition evaluation. */
    private Map<String, Object> context;

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getRelation() { return relation; }
    public void setRelation(String relation) { this.relation = relation; }

    public String getObjectType() { return objectType; }
    public void setObjectType(String objectType) { this.objectType = objectType; }

    public List<String> getContextualRoles() { return contextualRoles; }
    public void setContextualRoles(List<String> contextualRoles) { this.contextualRoles = contextualRoles; }

    public Map<String, Object> getContext() { return context; }
    public void setContext(Map<String, Object> context) { this.context = context; }
}
