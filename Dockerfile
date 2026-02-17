# Stage 1: Build the Application
FROM gradle:8.5-jdk17 AS build
WORKDIR /app
COPY --chown=gradle:gradle . /app
# Skip tests to save build time
RUN gradle build --no-daemon -x test

# Stage 2: Runtime Image (Small & Fast)
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Copy the Fat Jar (Shadow Jar) or standard Jar
# Ktor usually outputs to build/libs/
COPY --from=build /app/build/libs/*-all.jar app.jar
# OR if not using shadow plugin: COPY --from=build /app/build/libs/java-decompiler-ktor-0.0.1.jar app.jar

# Copy tools if you still have external binaries
# COPY tools ./tools

EXPOSE 8080

# MEMORY TUNING FOR FREE TIER
# -Xmx300m: Gives 200MB headroom for the OS
# -XX:+UseSerialGC: The most efficient GC for single-core/low-RAM
ENTRYPOINT ["java", "-Xmx300m", "-XX:+UseSerialGC", "-jar", "app.jar"]