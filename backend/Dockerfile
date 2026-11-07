# Build stage
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B -DskipTests package && cp target/atlas-backend-*.jar /app.jar

# Runtime stage: JRE only, non-root user
FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 atlas
USER atlas
COPY --from=build /app.jar /app/app.jar
EXPOSE 8080
# Liveness/readiness are probed by the orchestrator at /actuator/health/{liveness,readiness};
# the JRE image ships no HTTP client, so no in-image HEALTHCHECK.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
