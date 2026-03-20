package com.example.fgasdk.service;

import com.example.fgasdk.abac.AttributeResolverRegistry;
import com.example.fgasdk.abac.RoleContextRequirementCache;
import com.example.fgasdk.dto.CheckRequest;
import com.example.fgasdk.dto.ListObjectsRequest;
import com.example.fgasdk.dto.ListUsersRequest;
import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.client.model.*;
import dev.openfga.sdk.api.model.FgaObject;
import dev.openfga.sdk.api.model.User;
import dev.openfga.sdk.api.model.UserTypeFilter;
import dev.openfga.sdk.errors.FgaApiValidationError;
import dev.openfga.sdk.errors.FgaInvalidParameterException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Handles all authorization query operations against OpenFGA.
 *
 * ABAC context resolution — two layers, no model changes needed:
 *
 * Layer 1 — Pre-validated enrichment (per role, cached):
 *   Before the first OpenFGA call, consult RoleContextRequirementCache to find
 *   which runtime parameters each contextual role actually needs. The cache is
 *   built by reading the role's stored tuples once, then subtracting the params
 *   already baked into the tuple (start_hour, end_hour, allowed_cidr) from the
 *   condition's full declaration — leaving only what must come from the caller
 *   or be resolved at runtime (current_hour, client_ip, etc.).
 *   Only the required missing params are resolved — nothing else is fetched.
 *   Cache is invalidated per-role on tuple write/delete.
 *
 * Layer 2 — Lazy retry (fallback):
 *   If OpenFGA still throws HTTP 400 "missing context parameters [x]" despite
 *   layer 1 (e.g. a role whose cache entry was just invalidated, or a brand-new
 *   uncached role), parse the missing param names from the error and resolve them.
 *   Rethrow if nothing can be resolved.
 *
 * Result: roles with no conditions pay zero overhead. Roles with conditions pay
 * one cache lookup (in-memory after first load) and resolve only what's needed.
 */
@Service
public class FgaCheckService {

    private static final Logger log = LoggerFactory.getLogger(FgaCheckService.class);
    private static final Pattern MISSING_PARAMS_PATTERN =
            Pattern.compile("missing context parameters '\\[([^\\]]+)\\]'");

    private final OpenFgaClient fgaClient;
    private final AttributeResolverRegistry resolverRegistry;
    private final RoleContextRequirementCache requirementCache;

    public FgaCheckService(OpenFgaClient fgaClient,
                           AttributeResolverRegistry resolverRegistry,
                           RoleContextRequirementCache requirementCache) {
        this.fgaClient = fgaClient;
        this.resolverRegistry = resolverRegistry;
        this.requirementCache = requirementCache;
    }

    public boolean check(CheckRequest request)
            throws ExecutionException, InterruptedException, FgaInvalidParameterException {
        List<ClientTupleKey> contextualTuples = merge(
                buildRoleTuples(request.getUserId(), request.getContextualRoles()),
                buildGroupTuples(request.getUserId(), request.getContextualGroups()));
        Map<String, Object> context = preEnrich(request.getContext(), request.getContextualRoles());
        try {
            return runCheck(request, context, contextualTuples);
        } catch (ExecutionException e) {
            Map<String, Object> enriched = tryEnrich(e, context);
            if (enriched == null) throw e;
            log.debug("check: fallback retry with params {}", enriched.keySet());
            return runCheck(request, enriched, contextualTuples);
        }
    }

    public List<String> listObjects(ListObjectsRequest request)
            throws ExecutionException, InterruptedException, FgaInvalidParameterException {
        List<ClientTupleKey> contextualTuples = merge(
                buildRoleTuples(request.getUserId(), request.getContextualRoles()),
                buildGroupTuples(request.getUserId(), request.getContextualGroups()));
        Map<String, Object> context = preEnrich(request.getContext(), request.getContextualRoles());
        try {
            return runListObjects(request, context, contextualTuples);
        } catch (ExecutionException e) {
            Map<String, Object> enriched = tryEnrich(e, context);
            if (enriched == null) throw e;
            log.debug("listObjects: fallback retry with params {}", enriched.keySet());
            return runListObjects(request, enriched, contextualTuples);
        }
    }

    public List<String> listUsers(ListUsersRequest request)
            throws ExecutionException, InterruptedException, FgaInvalidParameterException {
        ClientListUsersRequest req = new ClientListUsersRequest()
                ._object(new FgaObject().type(request.getObjectType()).id(request.getObjectId()))
                .relation(request.getRelation())
                .userFilters(List.of(new UserTypeFilter().type(request.getUserType())));
        return fgaClient.listUsers(req).get().getUsers().stream()
                .map(this::userToString).toList();
    }

    // ── private ───────────────────────────────────────────────────────────────

    /**
     * Layer 1: consult RoleContextRequirementCache for each contextual role,
     * collect all runtime-required params, resolve only the missing ones.
     * Caller-supplied values always win (putIfAbsent semantics).
     */
    private Map<String, Object> preEnrich(Map<String, Object> callerContext, List<String> roles)
            throws ExecutionException, InterruptedException, FgaInvalidParameterException {
        Map<String, Object> ctx = callerContext != null ? new HashMap<>(callerContext) : new HashMap<>();
        if (roles == null || roles.isEmpty()) return ctx;

        Set<String> needed = new HashSet<>();
        for (String role : roles) {
            needed.addAll(requirementCache.getRequiredParams(role));
        }

        if (needed.isEmpty()) return ctx;

        // Only resolve what is both needed AND missing from caller context
        needed.removeAll(ctx.keySet());
        if (needed.isEmpty()) return ctx;

        Map<String, Object> resolved = resolverRegistry.resolve(needed, ctx);
        if (!resolved.isEmpty()) {
            log.debug("preEnrich: resolved {} for roles {}", resolved.keySet(), roles);
            ctx.putAll(resolved);
        }
        return ctx;
    }

    /**
     * Layer 2: if OpenFGA throws HTTP 400 "missing context parameters [x]",
     * parse the names and try to resolve them. Returns null if unresolvable.
     */
    private Map<String, Object> tryEnrich(ExecutionException e, Map<String, Object> currentContext) {
        if (!(e.getCause() instanceof FgaApiValidationError v)) return null;
        if (v.getApiErrorMessage() == null) return null;

        Set<String> missing = parseMissingParams(v.getApiErrorMessage());
        if (missing.isEmpty()) return null;

        log.info("Fallback: OpenFGA still missing params {} after pre-enrichment", missing);
        Map<String, Object> resolved = resolverRegistry.resolve(missing, currentContext);
        if (resolved.isEmpty()) {
            log.warn("No resolvers for {} — rethrowing", missing);
            return null;
        }
        Map<String, Object> enriched = new HashMap<>(currentContext);
        enriched.putAll(resolved);
        return enriched;
    }

    private boolean runCheck(CheckRequest request, Map<String, Object> context,
                             List<ClientTupleKey> contextualTuples)
            throws ExecutionException, InterruptedException, FgaInvalidParameterException {
        ClientCheckRequest req = new ClientCheckRequest()
                .user(request.getUserId())
                .relation(request.getRelation())
                ._object(request.getObjectType() + ":" + request.getObjectId())
                .context(context.isEmpty() ? null : context);
        if (!contextualTuples.isEmpty()) req.contextualTuples(contextualTuples);
        return Boolean.TRUE.equals(fgaClient.check(req).get().getAllowed());
    }

    private List<String> runListObjects(ListObjectsRequest request, Map<String, Object> context,
                                        List<ClientTupleKey> contextualTuples)
            throws ExecutionException, InterruptedException, FgaInvalidParameterException {
        ClientListObjectsRequest req = new ClientListObjectsRequest()
                .user(request.getUserId())
                .relation(request.getRelation())
                .type(request.getObjectType())
                .context(context.isEmpty() ? null : context);
        if (!contextualTuples.isEmpty()) req.contextualTupleKeys(contextualTuples);
        List<String> objects = fgaClient.listObjects(req).get().getObjects();
        return objects != null ? objects : List.of();
    }

    private Set<String> parseMissingParams(String message) {
        if (message == null) return Set.of();
        Matcher m = MISSING_PARAMS_PATTERN.matcher(message);
        if (!m.find()) return Set.of();
        return Arrays.stream(m.group(1).split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    private List<ClientTupleKey> buildRoleTuples(String userId, List<String> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) return List.of();
        return roleIds.stream()
                .map(r -> new ClientTupleKey().user(userId).relation("assignee")._object(r))
                .toList();
    }

    /** Builds ephemeral (userId, member, groupId) tuples from the caller-supplied group list. */
    private List<ClientTupleKey> buildGroupTuples(String userId, List<String> groupIds) {
        if (groupIds == null || groupIds.isEmpty()) return List.of();
        return groupIds.stream()
                .map(g -> new ClientTupleKey().user(userId).relation("member")._object(g))
                .toList();
    }

    private List<ClientTupleKey> merge(List<ClientTupleKey> a, List<ClientTupleKey> b) {
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        List<ClientTupleKey> merged = new ArrayList<>(a);
        merged.addAll(b);
        return merged;
    }

    private String userToString(User u) {
        if (u.getObject() != null) return u.getObject().getType() + ":" + u.getObject().getId();
        if (u.getUserset() != null) return u.getUserset().getType() + ":" + u.getUserset().getId()
                + "#" + u.getUserset().getRelation();
        if (u.getWildcard() != null) return u.getWildcard().getType() + ":*";
        return u.toString();
    }
}
