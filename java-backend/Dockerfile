# Generic multi-stage Dockerfile for any GitOracle Java microservice.
# Build with: docker build --build-arg SERVICE=orchestrator -t gitoracle/orchestrator .
ARG SERVICE

FROM gradle:8.7-jdk21 AS build
# Not gradle:8.7-jdk21-alpine — Gradle's native file-system-watching library
# (file-events) SIGSEGVs immediately inside that image on aarch64 (confirmed
# live: "libnative-platform-file-events.so ... _init"), a known musl-libc
# incompatibility with that native lib, and the crash happens during the
# build's own single-use-daemon bootstrap — before --no-watch-fs would even
# take effect, so that flag alone doesn't help. The glibc-based image doesn't
# hit it.
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

# test-runner shells out to `docker run` to sandbox test execution, but this
# base image ships no docker binary — so `docker info` failed at startup,
# dockerAvailable stayed false, and every containerised test run failed closed.
# The socket mount it used to carry was therefore pure liability: host-root
# privilege that nothing could even use. Only the client is installed here; the
# daemon it talks to is the rootless DinD sidecar (DOCKER_HOST), never a host
# socket. See docker-compose.services.yml.
#
# Scoped to test-runner rather than added to every image, since no other
# service invokes Docker and a docker client in, say, the gateway is just more
# attack surface.
RUN if [ "$SERVICE" = "test-runner" ]; then \
      apk add --no-cache docker-cli; \
    fi

COPY --from=build /out/app.jar /app/app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
