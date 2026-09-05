# Bases épinglées par digest (tag lisible conservé) : aucune installation APT
# réseau n'est exécutée ensuite, donc le contenu OS dépend uniquement de ces digests.
ARG MAVEN_IMAGE=maven:3.9.11-eclipse-temurin-21@sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237
ARG RUNTIME_IMAGE=eclipse-temurin:21.0.12_8-jre-jammy@sha256:eebd356ad7358b7094758e5787a6726f332917cfd56feab6457c56dab895cdbf

FROM ${MAVEN_IMAGE} AS build
WORKDIR /workspace
COPY . .
# Utilise le Maven déjà contenu dans l'image builder épinglée afin d'éviter
# d'installer unzip via un dépôt APT mutable pendant la build Docker.
RUN mvn -B -DskipTests package

FROM ${RUNTIME_IMAGE} AS runtime
USER root
RUN groupadd --system --gid 10001 nexus \
    && useradd --system --uid 10001 --gid 10001 --home-dir /data/nexus --shell /usr/sbin/nologin nexus \
    && mkdir -p /opt/nexus/lib /opt/nexus/rest /data/nexus /workspace \
    && chown -R 10001:10001 /opt/nexus /data/nexus /workspace

COPY --from=build /workspace/target/nexus-context-engine-*-cli.jar /opt/nexus/lib/nexus-cli.jar
COPY --from=build /workspace/adapters/mcp-java/target/nexus-mcp-java-*-runner.jar /opt/nexus/lib/nexus-mcp.jar
COPY --from=build /workspace/adapters/assistant-clients/target/nexus-assistant-clients-*-runner.jar /opt/nexus/lib/nexus-assistant-clients.jar
COPY --from=build /workspace/adapters/rest-quarkus/target/quarkus-app/ /opt/nexus/rest/
COPY --chown=10001:10001 packaging/docker/nexus-container-entrypoint.sh /usr/local/bin/nexus-entrypoint
COPY --chown=10001:10001 packaging/docker/nexus-container-healthcheck.sh /usr/local/bin/nexus-healthcheck

# A Windows checkout peut matérialiser les scripts avec CRLF. Les normaliser
# dans l'image conserve des shebangs Linux valides sans paquet supplémentaire.
RUN sed -i 's/\r$//' /usr/local/bin/nexus-entrypoint /usr/local/bin/nexus-healthcheck \
    && chmod 0755 /usr/local/bin/nexus-entrypoint /usr/local/bin/nexus-healthcheck

# NEXUS_DOCKER_HOST_FORWARD_ADDRESS intentionally has no image default. A container cannot infer
# the host address chosen by the Docker daemon for -p/--publish. Official Compose injects the
# declaration from NEXUS_DOCKER_BIND_ADDRESS; manual REST runs must declare it explicitly.
# JDK_JAVA_OPTIONS applies the qualified native-access contract even to supported direct
# `docker exec ... java` MCP/CLI integrations that bypass the entrypoint.
ENV JDK_JAVA_OPTIONS=--enable-native-access=ALL-UNNAMED \
    NEXUS_HOME=/data/nexus \
    NEXUS_MODE=rest \
    NEXUS_RUNTIME=docker \
    NEXUS_REST_HOST=0.0.0.0 \
    NEXUS_REST_PORT=8080 \
    NEXUS_REST_ALLOWED_PROJECT_ROOTS=/workspace \
    NEXUS_REST_EXPOSURE_MODE=loopback-forward \
    NEXUS_MAX_SCIP_INDEX_BYTES=268435456 \
    NEXUS_MAX_SCIP_MESSAGE_BYTES=16777216

VOLUME ["/data/nexus", "/workspace"]
EXPOSE 8080
USER 10001:10001
WORKDIR /workspace
ENTRYPOINT ["/usr/local/bin/nexus-entrypoint"]
CMD ["rest"]