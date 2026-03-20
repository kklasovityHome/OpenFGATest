package com.example.fgasdk.service;

import com.example.fgasdk.dto.TupleRequest;
import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.client.model.ClientRelationshipCondition;
import dev.openfga.sdk.api.client.model.ClientTupleKey;
import dev.openfga.sdk.api.client.model.ClientTupleKeyWithoutCondition;
import dev.openfga.sdk.api.client.model.ClientWriteRequest;
import dev.openfga.sdk.errors.FgaInvalidParameterException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Handles writing and deleting relationship tuples in OpenFGA.
 *
 * A "tuple" is the fundamental unit of data: (user, relation, object).
 * Examples:
 *   ("user:alice", "admin",  "organization:acme")           ← RBAC role assignment
 *   ("department:engineering", "parent", "project:backend") ← ReBAC hierarchy link
 *   ("user:charlie", "viewer_on_weekdays", "document:spec") ← ABAC conditional
 */
@Service
public class FgaTupleService {

    private final OpenFgaClient fgaClient;

    public FgaTupleService(OpenFgaClient fgaClient) {
        this.fgaClient = fgaClient;
    }

    /**
     * Writes one or more relationship tuples.
     * Optionally attaches a condition for ABAC tuples.
     */
    public void writeTuples(List<TupleRequest> tuples)
            throws ExecutionException, InterruptedException, FgaInvalidParameterException {
        List<ClientTupleKey> keys = tuples.stream()
                .map(this::toClientTupleKey)
                .toList();
        ClientWriteRequest req = new ClientWriteRequest().writes(keys);
        fgaClient.write(req).get();
    }

    /**
     * Deletes one or more relationship tuples.
     */
    public void deleteTuples(List<TupleRequest> tuples)
            throws ExecutionException, InterruptedException, FgaInvalidParameterException {
        List<ClientTupleKeyWithoutCondition> keys = tuples.stream()
                .map(t -> new ClientTupleKeyWithoutCondition()
                        .user(t.getUser())
                        .relation(t.getRelation())
                        ._object(t.getObject()))
                .toList();
        ClientWriteRequest req = new ClientWriteRequest().deletes(keys);
        fgaClient.write(req).get();
    }

    private ClientTupleKey toClientTupleKey(TupleRequest t) {
        ClientTupleKey key = new ClientTupleKey()
                .user(t.getUser())
                .relation(t.getRelation())
                ._object(t.getObject());

        if (t.getConditionName() != null && !t.getConditionName().isBlank()) {
            ClientRelationshipCondition condition = new ClientRelationshipCondition()
                    .name(t.getConditionName());
            if (t.getConditionContext() != null) {
                condition.context(t.getConditionContext());
            }
            key.condition(condition);
        }
        return key;
    }
}
