ARG MAVEN_IMAGE=maven:3.9.11-eclipse-temurin-21
ARG RUNTIME_IMAGE=eclipse-temurin:21-jre-jammy

FROM ${MAVEN_IMAGE} AS build
USER root
RUN apt-get update \
    && apt-get install -y --no-install-recommends unzip \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /workspace
COPY . .
RUN chmod +x ./mvnw && ./mvnw -B -DskipTests package

FROM ${RUNTIME_IMAGE} AS runtime
USER root
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --gid root --home-dir /data/nexus --shell /usr/sbin/nologin nexus \
    && mkdir -p /opt/nexus/lib /opt/nexus/rest /data/nexus /workspace \
    && chown -R 10001:0 /opt/nexus /data/nexus /workspace

COPY --from=build /workspace/target/nexus-context-engine-*-cli.jar /opt/nexus/lib/nexus-cli.jar
COPY --from=build /workspace/adapters/mcp-java/target/nexus-mcp-java-*-runner.jar /opt/nexus/lib/nexus-mcp.jar
COPY --from=build /workspace/adapters/assistant-clients/target/nexus-assistant-clients-*-runner.jar /opt/nexus/lib/nexus-assistant-clients.jar
COPY --from=build /workspace/adapters/rest-quarkus/target/quarkus-app/ /opt/nexus/rest/
COPY --chown=10001:0 packaging/docker/nexus-container-entrypoint.sh /usr/local/bin/nexus-entrypoint

RUN chmod 0755 /usr/local/bin/nexus-entrypoint

ENV NEXUS_HOME=/data/nexus \
    NEXUS_MODE=rest \
    NEXUS_REST_HOST=0.0.0.0 \
    NEXUS_REST_PORT=8080

VOLUME ["/data/nexus", "/workspace"]
EXPOSE 8080
USER 10001
WORKDIR /workspace
ENTRYPOINT ["/usr/local/bin/nexus-entrypoint"]
CMD ["rest"]
