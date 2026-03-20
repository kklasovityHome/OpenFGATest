package com.example.fgasdk.service;

import com.example.fgasdk.config.FgaProperties;
import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.client.model.ClientCreateStoreResponse;
import dev.openfga.sdk.api.model.CreateStoreRequest;
import dev.openfga.sdk.api.model.Store;
import dev.openfga.sdk.errors.FgaInvalidParameterException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Manages the OpenFGA Store lifecycle.
 *
 * A "store" is the top-level namespace in OpenFGA — all tuples and models
 * belong to a store. In production you typically have one store per application
 * (or one per environment). The storeId must be set on the OpenFgaClient before
 * any tuple/model operations.
 */
@Service
public class FgaStoreService {

    private final OpenFgaClient fgaClient;
    private final FgaProperties props;

    public FgaStoreService(OpenFgaClient fgaClient, FgaProperties props) {
        this.fgaClient = fgaClient;
        this.props = props;
    }

    /**
     * Creates a new store with the given name and updates the client to use it.
     * @param storeName a human-readable store name, e.g. "iam-demo"
     * @return the new store ID
     */
    public String createStore(String storeName)
            throws ExecutionException, InterruptedException, FgaInvalidParameterException {
        CreateStoreRequest req = new CreateStoreRequest().name(storeName);
        ClientCreateStoreResponse response = fgaClient.createStore(req).get();
        String storeId = response.getId();

        // Update the running client and props so subsequent calls use this store
        fgaClient.setStoreId(storeId);
        props.setStoreId(storeId);

        return storeId;
    }

    /**
     * Lists all stores. Useful to check whether a store already exists before creating one.
     */
    public List<Store> listStores()
            throws ExecutionException, InterruptedException, FgaInvalidParameterException {
        var response = fgaClient.listStores().get();
        List<Store> stores = response.getStores();
        return stores != null ? stores : List.of();
    }

    /**
     * Returns the storeId currently configured on the client, or null if not set.
     */
    public String currentStoreId() {
        return props.getStoreId();
    }
}
