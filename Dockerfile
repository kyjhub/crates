# openjdk 공식 이미지는 Docker Hub에서 내려갔다(openjdk:25-jdk-slim 이 resolve 되지 않는다).
# build.gradle 의 toolchain 이 21 이므로 베이스도 21로 맞춘다 — 25로 빌드할 이유가 없었다.
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /app

# Gradle Wrapper 복사
COPY gradlew .
COPY gradle gradle
COPY build.gradle settings.gradle ./

# 소스코드 복사
COPY src src

# gradlew 실행 권한 부여 및 빌드 (테스트 생략)
RUN chmod +x ./gradlew
RUN ./gradlew build -x test

# 실행 스테이지
FROM eclipse-temurin:21-jdk
WORKDIR /app
COPY --from=builder /app/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
