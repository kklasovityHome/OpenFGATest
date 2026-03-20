package com.example.fgasdk.controller;

import com.example.fgasdk.dto.BootstrapResponse;
import com.example.fgasdk.model.AuthorizationModels;
import com.example.fgasdk.service.FgaModelService;
import com.example.fgasdk.service.FgaStoreService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller for bootstrapping the OpenFGA store and authorization model.
 *
 * Typical first-time setup flow:
 *   POST /api/bootstrap           → creates store "iam-demo" + writes the model
 *   POST /api/bootstrap/model     → (re)writes the model to the existing store
 *   GET  /api/bootstrap/status    → shows current storeId + modelId
 *   GET  /api/bootstrap/model     → shows the current authorization model
 */
@RestController
@RequestMapping("/api/bootstrap")
public class BootstrapController {

    private final FgaStoreService storeService;
    private final FgaModelService modelService;

    public BootstrapController(FgaStoreService storeService, FgaModelService modelService) {
        this.storeService = storeService;
        this.modelService = modelService;
    }

    /**
     * Full bootstrap: creates a new store named "iam-demo" and writes the built-in
     * authorization model. Returns { storeId, modelId }.
     *
     * ⚠ This creates a NEW store every time it is called. To avoid duplicates,
     *    check /api/bootstrap/status first, or set openfga.store-id in application.properties.
     */
    @PostMapping
    public ResponseEntity<BootstrapResponse> bootstrap() throws Exception {
        String storeId = storeService.createStore("iam-demo");
        String modelId = modelService.writeModel(AuthorizationModels.IAM_MODEL);
        return ResponseEntity.ok(new BootstrapResponse(storeId, modelId,
                "Store created and model written successfully"));
    }

    /**
     * (Re)writes the authorization model to the currently configured store.
     * Use this to update the model without recreating the store.
     * You can also POST a custom model JSON body; if the body is empty/null
     * the built-in model is used.
     */
    @PostMapping("/model")
    public ResponseEntity<Map<String, String>> writeModel(
            @RequestBody(required = false) String customModelJson) throws Exception {

        String json = (customModelJson != null && !customModelJson.isBlank())
                ? customModelJson
                : AuthorizationModels.IAM_MODEL;

        String modelId = modelService.writeModel(json);
        return ResponseEntity.ok(Map.of(
                "modelId", modelId,
                "message", "Authorization model written successfully"));
    }

    /**
     * Shows the currently configured storeId and modelId.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, String>> status() {
        return ResponseEntity.ok(Map.of(
                "storeId", storeService.currentStoreId() != null ? storeService.currentStoreId() : "(not set)",
                "modelId", modelService.currentModelId() != null ? modelService.currentModelId() : "(not set)"
        ));
    }

    /**
     * Reads and returns the currently active authorization model from OpenFGA.
     */
    @GetMapping("/model")
    public ResponseEntity<Object> getModel() throws Exception {
        return ResponseEntity.ok(modelService.readCurrentModel());
    }

    /**
     * Lists all stores in the OpenFGA instance.
     */
    @GetMapping("/stores")
    public ResponseEntity<Object> listStores() throws Exception {
        return ResponseEntity.ok(storeService.listStores());
    }
}
