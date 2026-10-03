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

# Heap is sized from the container limit (Render free tier is 512 MB), leaving room for
# metaspace, thread stacks and the JDBC driver.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
