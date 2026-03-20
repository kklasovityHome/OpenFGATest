package com.example.fgasdk.dto;

import java.util.List;
import java.util.Map;

/**
 * Request DTO for the /api/authz/check endpoint.
 *
 * Supports three ways of expressing "what roles does this user have":
 *
 * ── Option A: Roles stored in OpenFGA tuples (default, persistent) ──────────
 *   Just send userId + relation + objectType + objectId.
 *   The role assignment must exist as a stored tuple, e.g.:
 *     (user:alice, assignee, role:org-admin)
 *
 * ── Option B: Roles injected at request time from an Identity Provider ───────
 *   Use the "contextualRoles" field. The app extracts roles from the IdP JWT
 *   and passes them here. OpenFGA evaluates them as if they were stored tuples
 *   but does NOT persist them. Nothing is written to the store.
 *
 *   Example — JWT contains roles ["org-admin", "proj-editor"]:
 *   {
 *     "userId":        "user:alice",
 *     "relation":      "can_edit",
 *     "objectType":    "document",
 *     "objectId":      "spec",
 *     "contextualRoles": ["role:org-admin", "role:proj-editor"]
 *   }
 *   This is equivalent to having stored tuples:
 *     (user:alice, assignee, role:org-admin)
 *     (user:alice, assignee, role:proj-editor)
 *   ...but only for this single check call.
 *
 * ── Option C: ABAC condition context ─────────────────────────────────────────
 *   Pass CEL parameter values via "context":
 *   {
 *     "userId": "user:charlie",
 *     "relation": "can_view",
 *     "objectType": "document",
 *     "objectId": "spec",
 *     "context": { "now": "2026-03-19T10:00:00Z" }
 *   }
 *
 * ── Option D: Both at once ────────────────────────────────────────────────────
 *   {
 *     "userId":        "user:alice",
 *     "relation":      "can_edit",
 *     "objectType":    "document",
 *     "objectId":      "spec",
 *     "contextualRoles": ["role:org-admin"],
 *     "context":       { "now": "2026-03-19T10:00:00Z" }
 *   }
 */
public class CheckRequest {
    private String userId;
    private String relation;
    private String objectType;
    private String objectId;

    /**
     * Roles injected from the Identity Provider at request time.
     * These are passed as contextual tuples — evaluated by OpenFGA for this
     * check only, never persisted in the store.
     *
     * Each entry should be a fully-qualified role ID, e.g. "role:org-admin".
     * The service will synthesize tuples: (userId, assignee, roleId) for each.
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

    public String getObjectId() { return objectId; }
    public void setObjectId(String objectId) { this.objectId = objectId; }

    public List<String> getContextualRoles() { return contextualRoles; }
    public void setContextualRoles(List<String> contextualRoles) { this.contextualRoles = contextualRoles; }

    public Map<String, Object> getContext() { return context; }
    public void setContext(Map<String, Object> context) { this.context = context; }
}
