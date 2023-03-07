FROM clojure:temurin-17-tools-deps-focal AS builder

# Copying and building deps as a separate step in order to mitigate
# the need to download new dependencies every build.
COPY deps.edn /usr/src/app/deps.edn
WORKDIR /usr/src/app
RUN clojure -P
COPY . /usr/src/app
RUN clojure -T:build uber

# Using image without lein for deployment.
FROM eclipse-temurin:17-focal
LABEL maintainer="Terry ONeill <toneill@broadinstitute.org>"

COPY --from=builder /usr/src/app/target/watcher.jar /app/app.jar

CMD ["java", "-jar", "/app/app.jar"]
