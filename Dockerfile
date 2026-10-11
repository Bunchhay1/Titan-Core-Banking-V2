# Dockerfile
# Stage 1: Dependency resolution and compilation
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /app

# Copy POM and aggressively cache dependencies
COPY pom.xml ./
RUN mvn dependency:go-offline -B || true

# Copy source tree and compile
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Minimal runtime image using JRE
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Run as non-root user for security compliance
RUN groupadd -r banking && useradd -r -g banking titan
USER titan:banking

COPY --from=builder /app/target/titan-core-banking-*.jar app.jar

EXPOSE 8080

# Java 21 requires --enable-preview if leveraging certain advanced features
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -Djava.security.egd=file:/dev/./urandom -jar app.jar"]