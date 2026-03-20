package com.example.fgasdk.dto;

/**
 * Request DTO for the /api/list-users endpoint.
 *
 * Example JSON:
 * {
 *   "objectType": "document",
 *   "objectId":   "spec",
 *   "relation":   "can_view",
 *   "userType":   "user"
 * }
 *
 * Returns all users of the given type that have the given relation on the object.
 */
public class ListUsersRequest {
    private String objectType;
    private String objectId;
    private String relation;
    /** The user type to filter by, e.g. "user" */
    private String userType = "user";

    public String getObjectType() { return objectType; }
    public void setObjectType(String objectType) { this.objectType = objectType; }

    public String getObjectId() { return objectId; }
    public void setObjectId(String objectId) { this.objectId = objectId; }

    public String getRelation() { return relation; }
    public void setRelation(String relation) { this.relation = relation; }

    public String getUserType() { return userType; }
    public void setUserType(String userType) { this.userType = userType; }
}
