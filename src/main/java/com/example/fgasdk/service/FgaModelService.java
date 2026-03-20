package com.example.fgasdk.service;

import com.example.fgasdk.config.FgaProperties;
import dev.openfga.sdk.api.client.ApiClient;
import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.model.WriteAuthorizationModelRequest;
import dev.openfga.sdk.errors.FgaInvalidParameterException;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutionException;

@Service
public class FgaModelService {

    private final OpenFgaClient fgaClient;
    private final FgaProperties props;
    private final ApiClient apiClient;

    public FgaModelService(OpenFgaClient fgaClient, FgaProperties props) {
        this.fgaClient = fgaClient;
        this.props = props;
        this.apiClient = new ApiClient();
    }

    public String writeModel(String modelJson) throws Exception {
        WriteAuthorizationModelRequest req =
                apiClient.getObjectMapper().readValue(modelJson, WriteAuthorizationModelRequest.class);
        var response = fgaClient.writeAuthorizationModel(req).get();
        String modelId = response.getAuthorizationModelId();
        fgaClient.setAuthorizationModelId(modelId);
        props.setModelId(modelId);

        return modelId;
    }

    public String currentModelId() {
        return props.getModelId();
    }

    public Object readCurrentModel()
            throws ExecutionException, InterruptedException, FgaInvalidParameterException {
        return fgaClient.readLatestAuthorizationModel().get().getAuthorizationModel();
    }
}
