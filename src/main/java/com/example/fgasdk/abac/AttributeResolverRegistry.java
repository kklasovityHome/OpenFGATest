package com.example.fgasdk.abac;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Registry of ABAC attribute resolvers, keyed by parameter name.
 *
 * When OpenFGA returns HTTP 400 "missing context parameters '[x, y]'", the
 * service extracts the missing names and calls this registry directly -- no
 * need to look up which condition the parameter belongs to.
 *
 * Adding a new resolvable parameter: just register a supplier here.
 *   register("country_code", () -> geoIpService.resolve(...));
 *
 * Built-in:
 *   "current_hour" -- UTC hour from server clock (within_time_window condition).
 *   "client_ip"    -- NOT registered; must come from the caller.
 */
@Component
public class AttributeResolverRegistry {

    private static final Logger log = LoggerFactory.getLogger(AttributeResolverRegistry.class);

    private final Map<String, Supplier<Object>> resolvers = new ConcurrentHashMap<>();

    public AttributeResolverRegistry() {
        register("current_hour", () -> ZonedDateTime.now(ZoneOffset.UTC).getHour());
        // "client_ip" intentionally not registered -- must come from the caller
    }

    /** Registers (or replaces) a resolver for a parameter name. Thread-safe. */
    public void register(String parameterName, Supplier<Object> resolver) {
        resolvers.put(parameterName, resolver);
        log.debug("Registered ABAC resolver for parameter '{}'", parameterName);
    }

    /**
     * Resolves all missing parameters that have a registered resolver.
     * Skips any already present in existingContext (caller values always win).
     * Parameters with no resolver are silently skipped.
     *
     * @param missingParams   parameter names OpenFGA reported as missing
     * @param existingContext attributes already present in the check request
     * @return newly resolved parameter-name to value (never null, may be empty)
     */
    public Map<String, Object> resolve(Set<String> missingParams, Map<String, Object> existingContext) {
        Map<String, Object> resolved = new HashMap<>();
        for (String param : missingParams) {
            if (existingContext.containsKey(param)) continue;
            Supplier<Object> resolver = resolvers.get(param);
            if (resolver == null) {
                log.debug("No resolver for missing parameter '{}' -- skipping", param);
                continue;
            }
            Object value = resolver.get();
            resolved.put(param, value);
            log.debug("Lazily resolved '{}' = {}", param, value);
        }
        return resolved;
    }
}
