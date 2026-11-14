# MiniPolya backend: Maven on JDK 21 builds the jar, a slim JRE 21 runs it.
#
# Built by deploy/docker-compose.yml with this repository as the context. Both stages are
# multi-architecture, so the same file builds on a t4g (ARM, Graviton) and on a t3 (x86).

# ---------------------------------------------------------------------------------------
# Stage 1 — build. The full JDK and Maven exist only here and never reach the server image.
# ---------------------------------------------------------------------------------------
FROM maven:3.9.11-eclipse-temurin-21 AS build

WORKDIR /build

# Dependencies first, in their own layer: pom.xml changes rarely, so a code-only change
# re-uses the downloaded repository instead of fetching it again. go-offline resolves the
# plugins and the annotation processors (Lombok, lombok-mapstruct-binding, MapStruct) too.
COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline

COPY src ./src

# The tests are not run here. They need a PostgreSQL (@DatabaseTest) that the build stage
# has no access to, and a 2 GB server is the wrong place to run 1061 of them: run
# `./mvnw test` on your machine before deploying. -DskipTests, not -Dmaven.test.skip, so
# the test sources still have to compile.
RUN mvn -B -ntp -DskipTests clean package

# ---------------------------------------------------------------------------------------
# Stage 2 — run. JRE only: no compiler, no Maven, no build cache.
# ---------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-noble AS runtime

# curl is for the container health check below and nothing else. --no-install-recommends
# keeps it to the library it needs, and the apt lists are deleted in the same layer so
# they are not carried in the image.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/*

# A non-root account owning nothing it does not need. The jar is owned by root and only
# readable: the process cannot rewrite its own code. /app/uploads is the mount point for
# the uploads volume and is the one place it writes.
RUN groupadd --system --gid 10001 minipolya \
 && useradd --system --uid 10001 --gid minipolya --home-dir /app --shell /usr/sbin/nologin minipolya \
 && mkdir -p /app/uploads \
 && chown minipolya:minipolya /app/uploads

WORKDIR /app
COPY --from=build --chown=root:root --chmod=444 /build/target/minipolya-backend.jar /app/minipolya-backend.jar

USER 10001:10001

# What the JVM may use, expressed against the container's limit rather than the host's RAM,
# because the server has 2 GB in total and PostgreSQL and Caddy need their share.
#
#   MaxRAMPercentage=70        the heap tops out at 70% of the container limit (about
#                              630 MB of the 900 MB in docker-compose.yml), leaving room
#                              for metaspace, thread stacks and the JVM itself. A
#                              percentage, not -Xmx, so raising the limit is enough.
#   InitialRAMPercentage=30    start with a usable heap instead of growing into it.
#   UseSerialGC                one GC thread, no parallel-GC bookkeeping: on a 2 vCPU box
#                              with a heap this size it uses less memory and less CPU than
#                              G1, and nothing here needs G1's pause guarantees.
#   MaxMetaspaceSize=256m      Spring loads a lot of classes; a ceiling turns a class-loader
#                              leak into a clear error instead of the kernel killing the
#                              container.
#   ExitOnOutOfMemoryError     die on OOM rather than limp on with a broken heap, so
#                              `restart: unless-stopped` restarts a working process.
#   HeapDumpOnOutOfMemoryError is deliberately NOT set: a heap dump of this size would fill
#                              the disk and would contain users' data.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70.0 -XX:InitialRAMPercentage=30.0 -XX:+UseSerialGC \
-XX:MaxMetaspaceSize=256m -XX:+ExitOnOutOfMemoryError -Dfile.encoding=UTF-8"

EXPOSE 8080

# Is the application actually serving? /actuator/health is the only actuator endpoint
# exposed and answers {"status":"UP"} with no detail (application.yaml), and its DB
# indicator means a reachable database as well — so this is a real check, not a port probe.
# 90 s of start-period covers Liquibase on a cold database; after that three failures in a
# row mark the container unhealthy.
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8080/actuator/health || exit 1

# sh -c so $JAVA_OPTS is split into words. exec so the JVM becomes PID 1 and receives
# SIGTERM from `docker compose stop` directly, letting Spring shut down in an orderly way.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/minipolya-backend.jar"]
