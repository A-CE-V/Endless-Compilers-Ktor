# Stage 1: Build
FROM gradle:8.5-jdk17 AS build
WORKDIR /app
# Copy the gradle files first to cache dependencies
COPY build.gradle.kts settings.gradle.kts ./
COPY src ./src
# Build the Fat JAR (shadowJar)
RUN gradle shadowJar --no-daemon

# Stage 2: Runtime
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Look for the file ending in -all.jar
COPY --from=build /app/build/libs/*-all.jar app.jar

# Koyeb uses 8080 by default
EXPOSE 8080

# MEMORY TUNING (Crucial for 512MB Free Tier)
# We use -Xmx350m to leave room for the Alpine OS and Decompiler overhead
ENTRYPOINT ["java", "-Xmx350m", "-XX:+UseSerialGC", "-jar", "app.jar"]