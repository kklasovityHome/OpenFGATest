package com.example.fgasdk.controller;

import com.example.fgasdk.dto.*;
import com.example.fgasdk.service.FgaCheckService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller for all authorization query operations.
 *
 * ── Check ────────────────────────────────────────────────────────────────────
 * POST /api/authz/check
 * "Can user:alice view document:spec?"
 * Body: { "userId":"user:alice", "relation":"can_view", "objectType":"document", "objectId":"spec" }
 * Response: { "allowed": true, "explanation": "..." }
 *
 * With ABAC context (weekday check):
 * Body: { "userId":"user:charlie", "relation":"can_view", "objectType":"document",
 *         "objectId":"spec", "context":{"now":"2026-03-19T10:00:00Z"} }
 *
 * ── ListObjects ──────────────────────────────────────────────────────────────
 * POST /api/authz/list-objects
 * "Which documents can user:alice view?"
 * Body: { "userId":"user:alice", "relation":"can_view", "objectType":"document" }
 * Response: ["document:spec", "document:readme"]
 *
 * ── ListUsers ────────────────────────────────────────────────────────────────
 * POST /api/authz/list-users
 * "Who can view document:spec?"
 * Body: { "objectType":"document", "objectId":"spec", "relation":"can_view", "userType":"user" }
 * Response: ["user:alice", "user:bob"]
 */
@RestController
@RequestMapping("/api/authz")
public class AuthzController {

    private final FgaCheckService checkService;

    public AuthzController(FgaCheckService checkService) {
        this.checkService = checkService;
    }

    /**
     * Single authorization check.
     * Returns { allowed: true/false, explanation: "..." }
     */
    @PostMapping("/check")
    public ResponseEntity<CheckResponse> check(@RequestBody CheckRequest request) throws Exception {
        boolean allowed = checkService.check(request);
        String explanation = allowed
                ? request.getUserId() + " has '" + request.getRelation()
                  + "' on " + request.getObjectType() + ":" + request.getObjectId()
                : request.getUserId() + " does NOT have '" + request.getRelation()
                  + "' on " + request.getObjectType() + ":" + request.getObjectId();
        return ResponseEntity.ok(new CheckResponse(allowed, explanation));
    }

    /**
     * Lists all objects of a given type that the user can access.
     * Useful for building filtered resource lists in UIs.
     */
    @PostMapping("/list-objects")
    public ResponseEntity<List<String>> listObjects(
            @RequestBody ListObjectsRequest request) throws Exception {
        return ResponseEntity.ok(checkService.listObjects(request));
    }

    /**
     * Lists all users that have a specific relation on a given object.
     * Useful for auditing access and building permission management UIs.
     */
    @PostMapping("/list-users")
    public ResponseEntity<List<String>> listUsers(
            @RequestBody ListUsersRequest request) throws Exception {
        return ResponseEntity.ok(checkService.listUsers(request));
    }
}
