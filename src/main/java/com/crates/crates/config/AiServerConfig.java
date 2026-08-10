package com.crates.crates.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class AiServerConfig {

    @Value("${ai.server.base-url}")
    private String baseUrl;

    @Value("${ai.server.connect-timeout}")
    private long connectTimeoutMillis;

    @Value("${ai.server.read-timeout}")
    private long readTimeoutMillis;

    @Bean
    public RestClient aiServerRestClient() {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(Duration.ofMillis(connectTimeoutMillis))
                .withReadTimeout(Duration.ofMillis(readTimeoutMillis));
        ClientHttpRequestFactory requestFactory = ClientHttpRequestFactoryBuilder.detect().build(settings);

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
}
