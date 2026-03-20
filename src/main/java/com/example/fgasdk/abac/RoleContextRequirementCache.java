package com.example.fgasdk.abac;

import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.client.model.ClientReadRequest;
import dev.openfga.sdk.api.model.AuthorizationModel;
import dev.openfga.sdk.api.model.Condition;
import dev.openfga.sdk.api.model.RelationshipCondition;
import dev.openfga.sdk.api.model.Tuple;
import dev.openfga.sdk.api.model.TupleKey;
import dev.openfga.sdk.errors.FgaInvalidParameterException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

/**
 * Caches the RUNTIME-required context parameter names for each role.
 *
 * "Runtime-required" means: parameters declared by a condition on a role's
 * stored tuples that are NOT already satisfied by the tuple's own condition
 * context (i.e. not start_hour/end_hour which are baked into the tuple,
 * but yes current_hour and client_ip which must come from the check request).
 *
 * How it works:
 *   1. On first check involving role:X, read all tuples where user=role:X.
 *   2. For each conditional tuple, get the full parameter list from the model's
 *      condition definition, subtract what's already stored in the tuple's own
 *      condition context — the remainder is what must come from the caller.
 *   3. Cache roleId -> Set<runtimeParamName> until invalidated.
 *
 * Example — role:tester, condition within_time_window:
 *   Model declares:        {current_hour, start_hour, end_hour}
 *   Tuple stores:          {start_hour: 6, end_hour: 20}
 *   Runtime-required:      {current_hour}   ← only this needs resolving
 *
 * Example — role:corp-editor, condition within_ip_range:
 *   Model declares:        {client_ip, allowed_cidr}
 *   Tuple stores:          {allowed_cidr: "10\\..*"}
 *   Runtime-required:      {client_ip}      ← caller must supply this
 *
 * Invalidated on every tuple write/delete.
 */
@Component
public class RoleContextRequirementCache {

    private static final Logger log = LoggerFactory.getLogger(RoleContextRequirementCache.class);

    private final OpenFgaClient fgaClient;

    // roleId → set of runtime-required parameter names (empty = no conditions)
    private final Map<String, Set<String>> roleCache = new ConcurrentHashMap<>();
    // conditionName → full set of declared parameter names (from model)
    private final Map<String, Set<String>> conditionParamCache = new ConcurrentHashMap<>();
    private volatile boolean conditionParamsLoaded = false;

    public RoleContextRequirementCache(OpenFgaClient fgaClient) {
        this.fgaClient = fgaClient;
    }

    /**
     * Returns the runtime context params required for this role.
     * Loads and caches on first call. Returns empty set if no conditional tuples.
     */
    public Set<String> getRequiredParams(String roleId)
            throws ExecutionException, InterruptedException, FgaInvalidParameterException {
        Set<String> cached = roleCache.get(roleId);
        if (cached != null) return cached;

        ensureConditionParamsLoaded();
        Set<String> required = loadRuntimeParamsForRole(roleId);
        roleCache.put(roleId, required);
        return required;
    }

    /** Full cache invalidation — call after any tuple write or delete. */
    public void invalidate() {
        roleCache.clear();
        log.debug("RoleContextRequirementCache invalidated");
    }

    /** Targeted invalidation for a single role — call when you know which role changed. */
    public void invalidate(String roleId) {
        roleCache.remove(roleId);
        log.debug("RoleContextRequirementCache invalidated for '{}'", roleId);
    }

    // ── private ──────────────────────────────────────────────────────────────

    private Set<String> loadRuntimeParamsForRole(String roleId)
            throws ExecutionException, InterruptedException, FgaInvalidParameterException {
        var response = fgaClient.read(
                new ClientReadRequest().user(roleId)._object("resource:")
        ).get();

        if (response.getTuples() == null || response.getTuples().isEmpty()) {
            return Collections.emptySet();
        }

        Set<String> runtimeRequired = new HashSet<>();

        for (Tuple tuple : response.getTuples()) {
            TupleKey key = tuple.getKey();
            if (key.getCondition() == null) continue;

            RelationshipCondition cond = key.getCondition();
            String condName = cond.getName();
            if (condName == null) continue;

            // All parameters this condition declares (from model)
            Set<String> allDeclared = conditionParamCache.getOrDefault(condName, Collections.emptySet());

            // Parameters already baked into this specific tuple (static policy values)
            @SuppressWarnings("unchecked")
            Set<String> storedInTuple = cond.getContext() != null
                    ? ((Map<String, Object>) cond.getContext()).keySet()
                    : Collections.emptySet();

            // Runtime-required = declared by condition - already stored in tuple
            Set<String> runtime = new HashSet<>(allDeclared);
            runtime.removeAll(storedInTuple);
            runtimeRequired.addAll(runtime);
        }

        Set<String> result = Collections.unmodifiableSet(runtimeRequired);
        if (!result.isEmpty()) {
            log.info("Role '{}' requires runtime context params: {}", roleId, result);
        }
        return result;
    }

    private void ensureConditionParamsLoaded()
            throws ExecutionException, InterruptedException, FgaInvalidParameterException {
        if (conditionParamsLoaded) return;
        synchronized (this) {
            if (conditionParamsLoaded) return;
            AuthorizationModel model = fgaClient
                    .readLatestAuthorizationModel().get()
                    .getAuthorizationModel();
            if (model != null && model.getConditions() != null) {
                for (Map.Entry<String, Condition> e : model.getConditions().entrySet()) {
                    Set<String> params = e.getValue().getParameters() != null
                            ? Collections.unmodifiableSet(e.getValue().getParameters().keySet())
                            : Collections.emptySet();
                    conditionParamCache.put(e.getKey(), params);
                    log.debug("Condition '{}' declares params: {}", e.getKey(), params);
                }
            }
            conditionParamsLoaded = true;
        }
    }
}
