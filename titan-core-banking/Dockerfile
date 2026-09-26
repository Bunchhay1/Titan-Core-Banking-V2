# Stage 1: Build — auto-detects platform (works on Apple Silicon arm64 + Render amd64)
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /app

COPY gradle/ gradle/
COPY gradlew build.gradle settings.gradle ./
RUN chmod +x ./gradlew

# Cache dependencies first
RUN ./gradlew dependencies --no-daemon 2>/dev/null || true

COPY src/ src/

# Auto-detect OS/arch for protoc classifier
RUN ARCH=$(uname -m) && \
    if [ "$ARCH" = "aarch64" ]; then \
      export GRADLE_OPTS="-Dos.name=Linux -Dos.arch=aarch64 -Dos.detected.classifier=linux-aarch_64"; \
    else \
      export GRADLE_OPTS="-Dos.name=Linux -Dos.arch=x86_64 -Dos.detected.classifier=linux-x86_64"; \
    fi && \
    ./gradlew clean build -x test --no-daemon

# Stage 2: Minimal runtime image
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=builder /app/build/libs/titan-core-banking-*-SNAPSHOT.jar app.jar

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -Dspring.profiles.active=${SPRING_PROFILES_ACTIVE:-docker} -jar app.jar"]
