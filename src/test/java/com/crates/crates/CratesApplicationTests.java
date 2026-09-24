package com.crates.crates;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 컨텍스트가 뜨는지만 본다.
 *
 * <p><b>@ActiveProfiles 를 지우지 말 것.</b> 이 테스트는 @SpringBootTest 라 실제 데이터소스에
 * 붙는데, 기본 프로필의 ddl-auto 가 {@code create} 다. 프로필을 지정하지 않으면 컨텍스트가
 * 뜨는 순간 Hibernate 가 개발 DB 의 모든 테이블을 drop/create 하고 import.sql 이
 * flyway_schema_history 까지 지운다. 실제로 한 번 일어났다 — content 191,239행과
 * V7~V10 인덱스가 통째로 날아갔고, 복구에 flyway-test 프로필 재시딩이 필요했다.</p>
 *
 * <p>benchmark 프로필이 {@code ddl-auto: none} 과 {@code flyway.enabled: false} 를 고정한다.
 * 이름은 벤치마크용이지만 역할은 "테스트가 DB 를 건드리지 않게 한다"이므로 모든 테스트가 쓴다.</p>
 *
 * <p>근본 해결은 기본 프로필에서 {@code ddl-auto: create} 를 걷어내고 초기화 전용 프로필로
 * 옮기는 것이다. 지금은 테스트 한쪽에 애노테이션이 빠지면 그대로 터진다.</p>
 */
@SpringBootTest
@ActiveProfiles("benchmark")
class CratesApplicationTests {

	@Test
	void contextLoads() {
	}

}
