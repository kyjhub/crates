package com.crates.crates;

import org.springframework.ai.vectorstore.qdrant.autoconfigure.QdrantVectorStoreAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// 일단 spring AI 꺼놨음
@SpringBootApplication(exclude = {QdrantVectorStoreAutoConfiguration.class})
public class CratesApplication {

	public static void main(String[] args) {
		SpringApplication.run(CratesApplication.class, args);
	}

}
