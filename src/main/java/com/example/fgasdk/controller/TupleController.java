package com.example.fgasdk.controller;

import com.example.fgasdk.dto.TupleRequest;
import com.example.fgasdk.service.FgaTupleService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for managing relationship tuples.
 *
 * Tuples are the data plane of OpenFGA — they store the actual relationships.
 *
 * POST   /api/tuples        → write one or more tuples
 * DELETE /api/tuples        → delete one or more tuples
 *
 * ── RBAC example ────────────────────────────────────────────────────────────
 * POST /api/tuples
 * [
 *   { "user": "user:alice", "relation": "admin", "object": "organization:acme" }
 * ]
 *
 * ── ReBAC hierarchy example ──────────────────────────────────────────────────
 * POST /api/tuples
 * [
 *   { "user": "organization:acme",    "relation": "parent", "object": "department:engineering" },
 *   { "user": "department:engineering","relation": "parent", "object": "project:backend" },
 *   { "user": "project:backend",       "relation": "parent", "object": "document:spec" }
 * ]
 *
 * ── ABAC conditional example ─────────────────────────────────────────────────
 * POST /api/tuples
 * [
 *   {
 *     "user": "user:charlie",
 *     "relation": "viewer_on_weekdays",
 *     "object": "document:spec",
 *     "conditionName": "weekday_only"
 *   }
 * ]
 * Then check with context: { "now": "2026-03-19T10:00:00Z" }
 */
@RestController
@RequestMapping("/api/tuples")
public class TupleController {

    private final FgaTupleService tupleService;

    public TupleController(FgaTupleService tupleService) {
        this.tupleService = tupleService;
    }

    /**
     * Writes one or more relationship tuples.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> writeTuples(
            @RequestBody List<TupleRequest> tuples) throws Exception {
        tupleService.writeTuples(tuples);
        return ResponseEntity.ok(Map.of(
                "written", tuples.size(),
                "message", "Tuples written successfully"));
    }

    /**
     * Deletes one or more relationship tuples.
     */
    @DeleteMapping
    public ResponseEntity<Map<String, Object>> deleteTuples(
            @RequestBody List<TupleRequest> tuples) throws Exception {
        tupleService.deleteTuples(tuples);
        return ResponseEntity.ok(Map.of(
                "deleted", tuples.size(),
                "message", "Tuples deleted successfully"));
    }
}
