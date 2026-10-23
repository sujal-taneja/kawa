# Build context is the repository root (the server needs the parent pom), e.g.
#   docker build -f kawa-server/Dockerfile -t kawa .

# --- stage 1: build the jar with the full JDK and the Maven wrapper ---
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src

# poms first: dependency downloads are cached as a layer and only rerun when a pom changes
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY kawa-client/pom.xml kawa-client/
COPY kawa-server/pom.xml kawa-server/
RUN ./mvnw -B -q -pl kawa-server -am dependency:go-offline -DexcludeArtifactIds=kawa-client

COPY kawa-client/src kawa-client/src
COPY kawa-server/src kawa-server/src
# tests run in CI against a real database; the image build only packages
RUN ./mvnw -B -q -pl kawa-server -am package -DskipTests \
 && cp kawa-server/target/kawa-server-*.jar /kawa.jar

# --- stage 2: only a JRE and the jar, running as a non-root user ---
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S kawa && adduser -S kawa -G kawa
USER kawa
WORKDIR /app
COPY --from=build /kawa.jar kawa.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "kawa.jar"]
