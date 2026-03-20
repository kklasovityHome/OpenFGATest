package com.example.fgasdk.model;

/**
 * Minimal dynamic RBAC + ABAC authorization model for OpenFGA.
 *
 * ── DESIGN GOALS ─────────────────────────────────────────────────────────────
 *
 *  • The schema is as small as possible — only the traversal SHAPE is defined here.
 *  • Everything else (role names, resource paths, hierarchy, permissions,
 *    condition parameter values) is TUPLES.
 *  • No model change ever needed for new roles, resources, hierarchies, or
 *    condition rules — all expressed by writing tuples at runtime.
 *
 * ── THREE TYPES ONLY ─────────────────────────────────────────────────────────
 *
 *   user       — a human or service identity (e.g. user:alice)
 *   role       — a named permission group (e.g. role:admin, role:editor, role:viewer)
 *   resource   — ANY addressable thing: an HTTP endpoint, a page, a file, etc.
 *
 * ── DYNAMIC ABAC — HOW IT WORKS ──────────────────────────────────────────────
 *
 *  OpenFGA conditions have TWO parts:
 *    1. The CEL expression — defined ONCE in the model (generic comparator).
 *    2. The parameter values — stored in the TUPLE at write time (fully dynamic).
 *
 *  This means:
 *    • Adding a new IP-restricted role → write a tuple with condition context
 *      { "allowed_cidr": "10.0.0.0/8" }. Zero model changes.
 *    • Adding a new time-restricted role → write a tuple with condition context
 *      { "start_hour": 9, "end_hour": 17 }. Zero model changes.
 *    • Changing an existing rule → delete old tuple, write new tuple with new
 *      context values. Zero model changes.
 *
 *  The model defines two generic conditions:
 *
 *    within_ip_range(client_ip: string, allowed_cidr: string)
 *      → CEL: client_ip.matches(allowed_cidr)
 *        Used for IP allowlist / geo-fence policies.
 *        • allowed_cidr stored in the tuple (e.g. "192\\.168\\..*")
 *        • client_ip passed in the check request context at runtime
 *
 *    within_time_window(current_hour: int, start_hour: int, end_hour: int)
 *      → CEL: current_hour >= start_hour && current_hour < end_hour
 *        Used for business-hours / time-of-day policies.
 *        • start_hour + end_hour stored in the tuple (e.g. 9, 17)
 *        • current_hour passed in the check request context at runtime
 *
 * ── EXAMPLE: IP-RESTRICTED ROLE GRANT ────────────────────────────────────────
 *
 *  Grant role:corp-editor edit access on /home, but only from the corp network:
 *
 *    POST /api/tuples
 *    [{
 *      "user":      "role:corp-editor",
 *      "relation":  "edit_access",
 *      "object":    "resource:/home",
 *      "condition": {
 *        "name":    "within_ip_range",
 *        "context": { "allowed_cidr": "10\\..*" }
 *      }
 *    }]
 *
 *  Check (pass the client IP at request time):
 *    POST /api/authz/check
 *    {
 *      "userId":         "user:alice",
 *      "relation":       "can_edit",
 *      "objectType":     "resource",
 *      "objectId":       "/home",
 *      "contextualRoles": ["role:corp-editor"],
 *      "context":        { "client_ip": "10.0.1.55" }
 *    }
 *    → true  (IP matches the CIDR stored in the tuple)
 *
 *  Same check from an external IP:
 *    "context": { "client_ip": "8.8.8.8" }
 *    → false (IP does not match)
 *
 * ── EXAMPLE: TIME-RESTRICTED ROLE GRANT ──────────────────────────────────────
 *
 *  Grant role:business-hours-editor edit access on /home, Mon-Fri 9-17 only:
 *
 *    POST /api/tuples
 *    [{
 *      "user":      "role:business-hours-editor",
 *      "relation":  "edit_access",
 *      "object":    "resource:/home",
 *      "condition": {
 *        "name":    "within_time_window",
 *        "context": { "start_hour": 9, "end_hour": 17 }
 *      }
 *    }]
 *
 *  Check (pass the current hour at request time, extracted from server clock or JWT):
 *    {
 *      "contextualRoles": ["role:business-hours-editor"],
 *      "context":         { "current_hour": 14 }
 *    }
 *    → true  (14 >= 9 && 14 < 17)
 *
 *    { "context": { "current_hour": 20 } }
 *    → false
 *
 * ── COMBINING RBAC + ABAC ─────────────────────────────────────────────────────
 *
 *  A role can have BOTH unconditional AND conditional grants simultaneously.
 *  OpenFGA evaluates them as a union — access is granted if ANY path resolves to true.
 *
 *  Example: role:editor has unconditional edit on /home/users,
 *           AND conditional (corp IP only) edit on /home/config:
 *    (role:editor, edit_access, resource:/home/users)                       ← no condition
 *    (role:editor, edit_access, resource:/home/config) + within_ip_range    ← conditional
 *
 * ── ROLE INHERITANCE ─────────────────────────────────────────────────────────
 *
 *  Roles can inherit from other roles via "granted_by":
 *    (role:editor, granted_by, role:viewer)
 *    → everyone with role:editor also has all permissions of role:viewer
 *    NOT the reverse — role:viewer does NOT inherit role:editor's permissions
 *
 * ── RUNTIME WORKFLOW ─────────────────────────────────────────────────────────
 *
 *  1. Bootstrap (once):   POST /api/bootstrap
 *  2. Build resource tree: POST /api/tuples [ parent links ]
 *  3. Grant roles:         POST /api/tuples [ role → resource, optionally with condition ]
 *  4. Assign users:        POST /api/tuples [ user → role ]  OR use contextualRoles
 *  5. Check:               POST /api/authz/check { ..., context: { "client_ip": "..." } }
 */
public final class AuthorizationModels {

    private AuthorizationModels() {}

    /**
     * Minimal dynamic RBAC + ABAC model — 3 types, 2 generic conditions.
     * Schema never needs to change; all policy values live in tuples.
     */
    public static final String IAM_MODEL = """
            {
              "schema_version": "1.1",
              "type_definitions": [

                {
                  "type": "user"
                },

                {
                  "type": "group",
                  "relations": {
                    "member": { "this": {} }
                  },
                  "metadata": {
                    "relations": {
                      "member": { "directly_related_user_types": [{ "type": "user" }] }
                    }
                  }
                },

                {
                  "type": "role",
                  "relations": {
                    "assignee": { "this": {} },
                    "granted_by": { "this": {} },
                    "effective_assignee": {
                      "union": {
                        "child": [
                          { "computedUserset": { "relation": "assignee" } },
                          { "tupleToUserset": {
                              "tupleset": { "relation": "granted_by" },
                              "computedUserset": { "relation": "effective_assignee" }
                          }}
                        ]
                      }
                    }
                  },
                  "metadata": {
                    "relations": {
                      "assignee": {
                        "directly_related_user_types": [
                          { "type": "user" },
                          { "type": "group", "relation": "member" }
                        ]
                      },
                      "granted_by":         { "directly_related_user_types": [{ "type": "role" }] },
                      "effective_assignee": { "directly_related_user_types": [] }
                    }
                  }
                },

                {
                  "type": "resource",
                  "relations": {
                    "parent":        { "this": {} },
                    "view_access":   { "this": {} },
                    "edit_access":   { "this": {} },
                    "delete_access": { "this": {} },
                    "manage_access": { "this": {} },

                    "can_view": {
                      "union": {
                        "child": [
                          { "tupleToUserset": { "tupleset": { "relation": "view_access" },   "computedUserset": { "relation": "effective_assignee" } } },
                          { "tupleToUserset": { "tupleset": { "relation": "edit_access" },   "computedUserset": { "relation": "effective_assignee" } } },
                          { "tupleToUserset": { "tupleset": { "relation": "delete_access" }, "computedUserset": { "relation": "effective_assignee" } } },
                          { "tupleToUserset": { "tupleset": { "relation": "manage_access" }, "computedUserset": { "relation": "effective_assignee" } } },
                          { "tupleToUserset": { "tupleset": { "relation": "parent" },        "computedUserset": { "relation": "can_view" } } }
                        ]
                      }
                    },
                    "can_edit": {
                      "union": {
                        "child": [
                          { "tupleToUserset": { "tupleset": { "relation": "edit_access" },   "computedUserset": { "relation": "effective_assignee" } } },
                          { "tupleToUserset": { "tupleset": { "relation": "manage_access" }, "computedUserset": { "relation": "effective_assignee" } } },
                          { "tupleToUserset": { "tupleset": { "relation": "parent" },        "computedUserset": { "relation": "can_edit" } } }
                        ]
                      }
                    },
                    "can_delete": {
                      "union": {
                        "child": [
                          { "tupleToUserset": { "tupleset": { "relation": "delete_access" }, "computedUserset": { "relation": "effective_assignee" } } },
                          { "tupleToUserset": { "tupleset": { "relation": "manage_access" }, "computedUserset": { "relation": "effective_assignee" } } },
                          { "tupleToUserset": { "tupleset": { "relation": "parent" },        "computedUserset": { "relation": "can_delete" } } }
                        ]
                      }
                    },
                    "can_manage": {
                      "union": {
                        "child": [
                          { "tupleToUserset": { "tupleset": { "relation": "manage_access" }, "computedUserset": { "relation": "effective_assignee" } } },
                          { "tupleToUserset": { "tupleset": { "relation": "parent" },        "computedUserset": { "relation": "can_manage" } } }
                        ]
                      }
                    }
                  },
                  "metadata": {
                    "relations": {
                      "parent":        { "directly_related_user_types": [{ "type": "resource" }] },
                      "view_access":   { "directly_related_user_types": [{ "type": "role" }, { "type": "role", "condition": "within_ip_range" }, { "type": "role", "condition": "within_time_window" }] },
                      "edit_access":   { "directly_related_user_types": [{ "type": "role" }, { "type": "role", "condition": "within_ip_range" }, { "type": "role", "condition": "within_time_window" }] },
                      "delete_access": { "directly_related_user_types": [{ "type": "role" }, { "type": "role", "condition": "within_ip_range" }, { "type": "role", "condition": "within_time_window" }] },
                      "manage_access": { "directly_related_user_types": [{ "type": "role" }, { "type": "role", "condition": "within_ip_range" }, { "type": "role", "condition": "within_time_window" }] },
                      "can_view":      { "directly_related_user_types": [] },
                      "can_edit":      { "directly_related_user_types": [] },
                      "can_delete":    { "directly_related_user_types": [] },
                      "can_manage":    { "directly_related_user_types": [] }
                    }
                  }
                }

              ],

              "conditions": {

                "within_ip_range": {
                  "name": "within_ip_range",
                  "expression": "client_ip.matches(allowed_cidr)",
                  "parameters": {
                    "client_ip":    { "type_name": "TYPE_NAME_STRING" },
                    "allowed_cidr": { "type_name": "TYPE_NAME_STRING" }
                  }
                },

                "within_time_window": {
                  "name": "within_time_window",
                  "expression": "current_hour >= start_hour && current_hour < end_hour",
                  "parameters": {
                    "current_hour": { "type_name": "TYPE_NAME_INT" },
                    "start_hour":   { "type_name": "TYPE_NAME_INT" },
                    "end_hour":     { "type_name": "TYPE_NAME_INT" }
                  }
                }

              }
            }
            """;
}
