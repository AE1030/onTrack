package org.tracker.gpatracker.avenue.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import java.util.Map;

@Service
public class D2LTokenService {

    private final RestClient restClient;

    public D2LTokenService(@Value("${d2l.token-service.url}") String tokenServiceUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(tokenServiceUrl)
                .build();
    }

    public String getActiveToken() {
        try {
            Map<String, Object> response = restClient.get()
                    .uri("/api/token")
                    .retrieve()
                    .body(Map.class);

            if (response != null && response.containsKey("token")) {
                return (String) response.get("token");
            }
            throw new RuntimeException("Token field not found in response payload.");
        } catch (Exception e) {
            throw new RuntimeException("Failed to reach Playwright microservice: " + e.getMessage());
        }
    }
}