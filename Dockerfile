# 빌드 스테이지
#
# 베이스가 JDK 21인 것이 중요하다. build.gradle의 toolchain이 21이라 다른 버전으로 빌드하면
# Gradle이 JDK 21을 찾다가 실패한다(자동 내려받기는 설정돼 있지 않다).
# 예전에는 openjdk:25였는데, openjdk 공식 이미지는 deprecated이기도 해서 temurin으로 옮겼다.
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /app

# 의존성 해석을 소스 복사보다 먼저 둔다. 소스만 바뀌면 이 레이어가 캐시에서 재사용돼
# 매번 의존성을 다시 받지 않는다.
COPY gradlew .
COPY gradle gradle
COPY build.gradle settings.gradle ./
RUN chmod +x ./gradlew && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

COPY src src

# 실행에 필요한 bootJar만 만든다. 테스트는 별도 검증 단계에서 실행한다.
RUN ./gradlew --no-daemon bootJar

# 실행 스테이지 — JRE가 아니라 JDK. JRE가 이미지는 절반 이하지만 jcmd가 없어서
# load-test/README.md의 JFR 프로파일링(docker exec backend_server jcmd 1 JFR.start)을 못 한다.
# 성능을 재는 단계라 진단 도구를 남긴다. 운영 이미지로 쓸 때는 JRE로 돌려도 된다.
FROM eclipse-temurin:21-jdk
WORKDIR /app
COPY --from=builder /app/build/libs/*.jar app.jar

EXPOSE 8080

# 컨테이너 메모리 상한을 기준으로 힙을 잡는다. 지정하지 않으면 기본 25%라,
# 3GB 제한에서 힙이 750MB뿐이라 시딩 중에 빠듯하다.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "app.jar"]
