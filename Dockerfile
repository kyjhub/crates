FROM openjdk:25-jdk-slim AS builder
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
FROM openjdk:25-jdk-slim
WORKDIR /app
COPY --from=builder /app/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
