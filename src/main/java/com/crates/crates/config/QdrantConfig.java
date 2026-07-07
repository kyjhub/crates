package com.crates.crates.config;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

@Configuration
public class QdrantConfig {

    @Value("${ai.vectorstore.qdrant.host}")
    private String host;

    @Value("${ai.vectorstore.qdrant.port}")
    private int port;

    @Value("${ai.vectorstore.qdrant.grpc-port}")
    private int grpcPort;

    // Rest API
    @Bean
    public RestClient qdrantRestClient() {
        return RestClient.builder()
                .baseUrl("http://" + host + ":" + port)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    // gRPC
    @Bean
    public QdrantClient qdrantClient() {
        // 3번째 인자 false는 로컬 개발용이므로 보안 연결(TLS/SSL)을 사용하지 않겠다는 의미입니다.
        QdrantGrpcClient grpcClient = QdrantGrpcClient.newBuilder(host, grpcPort, false).build();
        return new QdrantClient(grpcClient);
    }
}
