# ---- Build stage: compile the fat jar ----
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Dependencies first so this layer is cached until pom.xml changes.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline -DexcludeScope=test

COPY src/ src/
RUN mvn -B -q -Dmaven.test.skip=true package

# ---- Runtime stage: JRE only, non-root ----
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN groupadd --system app && useradd --system --gid app --no-create-home app
COPY --from=build /workspace/target/seat-booking.jar app.jar
USER app

# Render injects PORT; 8080 is the local default.
EXPOSE 8080

# Every JVM memory region is capped so heap + metaspace + code cache + direct buffers + stacks stay
# inside a 512 MB container under load; otherwise the kernel kills the process mid-burst.
# Serial GC has the smallest footprint and is the right collector for a single small CPU.
# MALLOC_ARENA_MAX stops glibc from reserving a native arena per thread.
ENV MALLOC_ARENA_MAX=2
ENV JAVA_OPTS="-XX:MaxRAMPercentage=50 -XX:MaxMetaspaceSize=160m -XX:ReservedCodeCacheSize=64m \
-XX:MaxDirectMemorySize=64m -Xss512k -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
