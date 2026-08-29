package com.crates.crates.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
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
        // JDK HttpClient는 기본 버전이 HTTP/2라 평문 HTTP 상대로 "Upgrade: h2c" 헤더를 붙인다.
        // AI 서버(FastAPI/uvicorn)는 이 업그레이드 요청이 오면 본문을 버리고 핸들러에 넘기기 때문에,
        // 요청 본문을 실어 보내도 서버에서는 422 {"loc":["body"],"input":null}로 떨어진다.
        // -> HTTP/1.1로 고정해서 업그레이드 시도 자체를 막는다.
        //
        // 구현체를 명시적으로 지정하는 이유가 하나 더 있다. 이전의 ClientHttpRequestFactoryBuilder.detect()는
        // 클래스패스를 훑어 구현체를 고르므로, 나중에 다른 HTTP 클라이언트 의존성이 추가되면
        // 이 RestClient의 동작이 조용히 바뀐다.
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(connectTimeoutMillis))
                .build();

        // JDK HttpClient는 연결 타임아웃을 클라이언트가, 읽기 타임아웃을 요청 팩토리가 받는다.
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(readTimeoutMillis));

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
}
