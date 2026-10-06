FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /src
COPY gradle/libs.versions.toml gradle/libs.versions.toml
COPY shared shared
COPY server server
WORKDIR /src/server
RUN chmod +x ./gradlew && ./gradlew installDist --no-daemon

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --create-home --uid 10001 goga \
    && mkdir -p /data \
    && chown goga:goga /data
WORKDIR /app
COPY --from=build --chown=goga:goga /src/server/build/install/goga-gateway /app
USER goga
ENV PORT=8080
ENV DATABASE_URL=jdbc:sqlite:/data/goga.db
EXPOSE 8080
VOLUME ["/data"]
ENTRYPOINT ["/app/bin/goga-gateway"]
