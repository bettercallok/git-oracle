# Generic multi-stage Dockerfile for any GitOracle Java microservice.
# Build with: docker build --build-arg SERVICE=orchestrator -t gitoracle/orchestrator .
ARG SERVICE

FROM gradle:8.7-jdk21-alpine AS build
ARG SERVICE
WORKDIR /workspace
COPY . .
RUN gradle :${SERVICE}:bootJar --no-daemon -x test \
    && mkdir -p /out \
    && find ${SERVICE}/build/libs -type f -name '*.jar' ! -name '*-plain.jar' -exec cp {} /out/app.jar \;

FROM eclipse-temurin:21-jre-alpine
ARG SERVICE
ENV SERVICE_MODULE=${SERVICE}
WORKDIR /app
COPY --from=build /out/app.jar /app/app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
