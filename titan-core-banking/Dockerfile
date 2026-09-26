# Stage 1: Build with Maven
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /app

# Copy POM files
COPY pom.xml ./
COPY titan-event-consumer-lib/pom.xml ./titan-event-consumer-lib/
COPY titan-core-banking/pom.xml ./titan-core-banking/
COPY titan-notifications-service/pom.xml ./titan-notifications-service/
COPY titan-promotions-service/pom.xml ./titan-promotions-service/

# Copy source trees for shared library and core banking
COPY titan-event-consumer-lib/src ./titan-event-consumer-lib/src
COPY titan-core-banking/src ./titan-core-banking/src

# Build package
RUN mvn clean package -pl titan-event-consumer-lib,titan-core-banking -am -DskipTests

# Stage 2: Minimal runtime image
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY --from=builder /app/titan-core-banking/target/titan-core-banking-*-SNAPSHOT.jar app.jar

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -Dspring.profiles.active=${SPRING_PROFILES_ACTIVE:-docker} -jar app.jar"]
