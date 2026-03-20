package com.example.fgasdk.service;

import com.example.fgasdk.abac.AttributeResolverRegistry;
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
 * Lazy ABAC attribute resolution:
 *
 *  Phase 1 -- Optimistic check with caller context only.
 *    ALLOW  -> return true.
 *    DENY   -> return false (policy decision).
 *    HTTP 400 "missing context parameters [x]" -> Phase 2.
 *
 *  Phase 2 -- OpenFGA told us exactly which parameters are missing.
 *    Look each one up in AttributeResolverRegistry by parameter name.
 *    If resolved -> retry with enriched context.
 *    If nothing resolved -> rethrow (safe: no silent denies for unresolvable params).
 */
@Service
public class FgaCheckService {

    private static final Logger log = LoggerFactory.getLogger(FgaCheckService.class);
    private static final Pattern MISSING_PARAMS_PATTERN =
            Pattern.compile("missing context parameters '\\[([^\\]]+)\\]'");

    private final OpenFgaClient fgaClient;
    private final AttributeResolverRegistry resolverRegistry;

    public FgaCheckService(OpenFgaClient fgaClient, AttributeResolverRegistry resolverRegistry) {
        this.fgaClient = fgaClient;
        this.resolverRegistry = resolverRegistry;
    }

    public boolean check(CheckRequest request)
            throws ExecutionException, InterruptedException, FgaInvalidParameterException {
        List<ClientTupleKey> contextualTuples = buildRoleTuples(request.getUserId(), request.getContextualRoles());
        Map<String, Object> context = callerContext(request.getContext());
        try {
            return runCheck(request, context, contextualTuples);
        } catch (ExecutionException e) {
            Map<String, Object> enriched = tryEnrich(e, context);
            if (enriched == null) throw e;
            log.debug("check: retrying with resolved params {}", enriched.keySet());
            return runCheck(request, enriched, contextualTuples);
        }
    }

    public List<String> listObjects(ListObjectsRequest request)
            throws ExecutionException, InterruptedException, FgaInvalidParameterException {
        List<ClientTupleKey> contextualTuples = buildRoleTuples(request.getUserId(), request.getContextualRoles());
        Map<String, Object> context = callerContext(request.getContext());
        try {
            return runListObjects(request, context, contextualTuples);
        } catch (ExecutionException e) {
            Map<String, Object> enriched = tryEnrich(e, context);
            if (enriched == null) throw e;
            log.debug("listObjects: retrying with resolved params {}", enriched.keySet());
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
                .map(this::userToString)
                .toList();
    }

    // ── private ───────────────────────────────────────────────────────────────

    /**
     * If the exception is a "missing context parameters" validation error,
     * resolves the missing params via the registry and returns the enriched context.
     * Returns null if the exception is unrelated or nothing could be resolved
     * (caller should rethrow in that case).
     */
    private Map<String, Object> tryEnrich(ExecutionException e, Map<String, Object> currentContext) {
        if (!(e.getCause() instanceof FgaApiValidationError v)) return null;
        if (v.getApiErrorMessage() == null) return null;

        Set<String> missing = parseMissingParams(v.getApiErrorMessage());
        if (missing.isEmpty()) return null;

        log.info("OpenFGA missing params: {} -- attempting lazy resolution", missing);

        Map<String, Object> resolved = resolverRegistry.resolve(missing, currentContext);
        if (resolved.isEmpty()) {
            log.warn("No resolvers for params {} -- rethrowing", missing);
            return null;
        }

        Map<String, Object> enriched = new HashMap<>(currentContext);
        enriched.putAll(resolved); // currentContext values already take precedence (they're the base)
        return enriched;
    }

    private boolean runCheck(CheckRequest request, Map<String, Object> context,
                             List<ClientTupleKey> contextualTuples)
            throws ExecutionException, InterruptedException, FgaInvalidParameterException {
        ClientCheckRequest req = new ClientCheckRequest()
                .user(request.getUserId())
                .relation(request.getRelation())
                ._object(request.getObjectType() + ":" + request.getObjectId())
                .context(context);
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
                .context(context);
        if (!contextualTuples.isEmpty()) req.contextualTupleKeys(contextualTuples);
        List<String> objects = fgaClient.listObjects(req).get().getObjects();
        return objects != null ? objects : List.of();
    }

    private Set<String> parseMissingParams(String message) {
        if (message == null) return Set.of();
        Matcher m = MISSING_PARAMS_PATTERN.matcher(message);
        if (!m.find()) return Set.of();
        return Arrays.stream(m.group(1).split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    private Map<String, Object> callerContext(Map<String, Object> ctx) {
        return ctx != null ? new HashMap<>(ctx) : new HashMap<>();
    }

    private List<ClientTupleKey> buildRoleTuples(String userId, List<String> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) return List.of();
        return roleIds.stream()
                .map(roleId -> new ClientTupleKey()
                        .user(userId)
                        .relation("assignee")
                        ._object(roleId))
                .toList();
    }

    private String userToString(User u) {
        if (u.getObject() != null) return u.getObject().getType() + ":" + u.getObject().getId();
        if (u.getUserset() != null) return u.getUserset().getType() + ":" + u.getUserset().getId()
                + "#" + u.getUserset().getRelation();
        if (u.getWildcard() != null) return u.getWildcard().getType() + ":*";
        return u.toString();
    }
}
