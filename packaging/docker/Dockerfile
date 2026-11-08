# Bases épinglées par digest (tag lisible conservé). Le builder et le runtime
# sont immuables ; aucune installation de paquet réseau n'est exécutée après le FROM runtime.
ARG MAVEN_IMAGE=maven:3.9.11-eclipse-temurin-21@sha256:6fdc855a6ed81d288ca7ca37ac6ff5e9308b612485c0801d70b25a858c83d237
ARG RUNTIME_IMAGE=eclipse-temurin:21.0.12_8-jre-ubi10-minimal@sha256:c7feea7f2dce5cf29e0ffa94e386b0a12ab44c029ad66bcb59eacc4da572ccf4

FROM ${MAVEN_IMAGE} AS build
WORKDIR /workspace
COPY . .
# La base builder reste épinglée par digest, mais le build lui-même passe par le
# wrapper repository-pinned afin d'utiliser exactement Maven 3.10.0 et de vérifier
# son archive contre l'ancre SHA-512 versionnée avant extraction.
RUN chmod +x ./mvnw \
    && ./mvnw -B -DskipTests package

FROM ${RUNTIME_IMAGE} AS runtime
# Valeurs fournies par la CI (docker-distribution.yml) ; sans valeur, le label reste vide.
ARG NEXUS_IMAGE_REVISION=""
ARG NEXUS_IMAGE_VERSION=""
LABEL org.opencontainers.image.title="nexus-context-engine" \
      org.opencontainers.image.source="https://github.com/FTurleque/nexus-context-engine" \
      org.opencontainers.image.licenses="LicenseRef-NEXUS-Proprietary" \
      org.opencontainers.image.revision="${NEXUS_IMAGE_REVISION}" \
      org.opencontainers.image.version="${NEXUS_IMAGE_VERSION}"
USER root
# Root possède le code et les scripts ; l'UID 10001 ne possède que ses données.
# Un UID/GID numérique évite d'installer shadow-utils dans l'image minimale tout
# en conservant un runtime strictement non-root.
RUN mkdir -p /opt/nexus/lib /opt/nexus/rest /data/nexus /workspace \
    && chown 10001:10001 /data/nexus /workspace

COPY --from=build /workspace/target/nexus-context-engine-*-cli.jar /opt/nexus/lib/nexus-cli.jar
COPY --from=build /workspace/adapters/mcp-java/target/nexus-mcp-java-*-runner.jar /opt/nexus/lib/nexus-mcp.jar
COPY --from=build /workspace/adapters/assistant-clients/target/nexus-assistant-clients-*-runner.jar /opt/nexus/lib/nexus-assistant-clients.jar
COPY --from=build /workspace/adapters/rest-quarkus/target/quarkus-app/ /opt/nexus/rest/
COPY --from=build /workspace/LICENSE /opt/nexus/LICENSE
COPY --from=build /workspace/target/licenses/THIRD_PARTY_NOTICES.txt /opt/nexus/THIRD_PARTY_NOTICES.txt
COPY packaging/docker/nexus-container-entrypoint.sh /usr/local/bin/nexus-entrypoint
COPY packaging/docker/nexus-container-healthcheck.sh /usr/local/bin/nexus-healthcheck

# A Windows checkout peut matérialiser les scripts avec CRLF. Les normaliser
# dans l'image conserve des shebangs Linux valides sans paquet supplémentaire.
# Modes : 0644 pour les fichiers de /opt/nexus, 0755 pour ses répertoires et les scripts.
RUN sed -i 's/\r$//' /usr/local/bin/nexus-entrypoint /usr/local/bin/nexus-healthcheck \
    && chmod -R 0644 /opt/nexus \
    && chmod -R a+X /opt/nexus \
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

# Même sonde et mêmes intervalles que docker-compose.yml.template, pour un `docker run` direct.
HEALTHCHECK --interval=10s --timeout=5s --start-period=20s --retries=6 \
    CMD ["/usr/local/bin/nexus-healthcheck"]

VOLUME ["/data/nexus", "/workspace"]
EXPOSE 8080
USER 10001:10001
WORKDIR /workspace
ENTRYPOINT ["/usr/local/bin/nexus-entrypoint"]
CMD ["rest"]