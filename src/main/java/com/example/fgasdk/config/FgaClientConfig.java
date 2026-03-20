package com.example.fgasdk.config;

import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.configuration.ApiToken;
import dev.openfga.sdk.api.configuration.ClientConfiguration;
import dev.openfga.sdk.api.configuration.Credentials;
import dev.openfga.sdk.api.configuration.CredentialsMethod;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Produces the OpenFgaClient Spring bean.
 *
 * The client is configured from {@link FgaProperties}. If a storeId / modelId is
 * already known at startup (e.g. set in application.properties) they are applied here;
 * otherwise they must be set via the bootstrap endpoint.
 */
@Configuration
@EnableConfigurationProperties(FgaProperties.class)
public class FgaClientConfig {

    @Bean
    public OpenFgaClient openFgaClient(FgaProperties props) throws Exception {
        ClientConfiguration config = new ClientConfiguration()
                .apiUrl(props.getApiUrl());

        if (props.getStoreId() != null && !props.getStoreId().isBlank()) {
            config.storeId(props.getStoreId());
        }
        if (props.getModelId() != null && !props.getModelId().isBlank()) {
            config.authorizationModelId(props.getModelId());
        }
        if (props.getApiToken() != null && !props.getApiToken().isBlank()) {
            ApiToken apiToken = new ApiToken(props.getApiToken());
            Credentials credentials = new Credentials();
            credentials.setCredentialsMethod(CredentialsMethod.API_TOKEN);
            credentials.setApiToken(apiToken);
            config.credentials(credentials);
        }

        return new OpenFgaClient(config);
    }
}
