package com.example.fgasdk.dto;

import java.util.List;
import java.util.Map;

/**
 * Request DTO for the /api/authz/check endpoint.
 *
 * Option A - Stored assignments:
 *   userId + relation + objectType + objectId only.
 *   Role/group assignments must exist as stored tuples.
 *
 * Option B - contextualRoles (IdP JWT):
 *   Ephemeral (userId, assignee, roleId) tuples for this check only.
 *   e.g. ["role:admin", "role:editor"]
 *
 * Option C - contextualGroups (IdP JWT / session):
 *   Ephemeral (userId, member, groupId) tuples for this check only.
 *   e.g. ["group:auditors", "group:finance"]
 *
 *   WHY groups cannot be lazily resolved (unlike ABAC params):
 *   A missing CEL parameter causes OpenFGA to throw HTTP 400 with
 *   "missing context parameters '[x]'" -- catchable and resolvable.
 *   A missing group membership tuple causes OpenFGA to return
 *   allowed:false -- identical to a legitimate policy deny.
 *   There is no signal to distinguish the two, so group memberships
 *   MUST be supplied upfront by the caller.
 *
 * Option D - ABAC context:
 *   context: {"current_hour": 21} overrides the auto-injected server clock.
 *   context: {"client_ip": "10.0.1.55"} must be supplied explicitly.
 */
public class CheckRequest {
    private String userId;
    private String relation;
    private String objectType;
    private String objectId;
    private List<String> contextualRoles;
    private List<String> contextualGroups;
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

    public List<String> getContextualGroups() { return contextualGroups; }
    public void setContextualGroups(List<String> contextualGroups) { this.contextualGroups = contextualGroups; }

    public Map<String, Object> getContext() { return context; }
    public void setContext(Map<String, Object> context) { this.context = context; }
}
