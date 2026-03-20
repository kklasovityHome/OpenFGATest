package com.example.fgasdk.dto;

import java.util.Map;

/**
 * Represents a single relationship tuple to write or delete.
 *
 * Examples:
 *
 * Simple RBAC assignment:
 *   { "user": "user:alice", "relation": "admin", "object": "organization:acme" }
 *
 * ReBAC parent link:
 *   { "user": "department:engineering", "relation": "parent", "object": "project:backend" }
 *
 * ABAC conditional assignment (weekday-only viewer):
 *   {
 *     "user": "user:charlie",
 *     "relation": "viewer_on_weekdays",
 *     "object": "document:spec",
 *     "conditionName": "weekday_only",
 *     "conditionContext": { "now": "2026-03-19T10:00:00Z" }
 *   }
 * Note: conditionContext in a tuple write is for metadata; the actual runtime
 * CEL evaluation happens at check time via the "context" map in CheckRequest.
 */
public class TupleRequest {
    /** The user/subject side, e.g. "user:alice" or "department:engineering" */
    private String user;
    /** The relation name, e.g. "admin", "viewer", "parent" */
    private String relation;
    /** The object side, formatted as "type:id", e.g. "document:spec" */
    private String object;
    /** Optional: condition name for ABAC tuples, e.g. "weekday_only" */
    private String conditionName;
    /** Optional: context values to store with the condition */
    private Map<String, Object> conditionContext;

    public String getUser() { return user; }
    public void setUser(String user) { this.user = user; }

    public String getRelation() { return relation; }
    public void setRelation(String relation) { this.relation = relation; }

    public String getObject() { return object; }
    public void setObject(String object) { this.object = object; }

    public String getConditionName() { return conditionName; }
    public void setConditionName(String conditionName) { this.conditionName = conditionName; }

    public Map<String, Object> getConditionContext() { return conditionContext; }
    public void setConditionContext(Map<String, Object> conditionContext) { this.conditionContext = conditionContext; }
}
