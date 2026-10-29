# ---- build -------------------------------------------------------------------
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package && \
    java -Djarmode=tools -jar target/shortener-api-*.jar extract --layers --launcher --destination /layers

# ---- runtime -----------------------------------------------------------------
FROM eclipse-temurin:25-jre-alpine
# apk upgrade pulls the distro's security fixes (e.g. expat) that lag behind the base image tag
RUN apk upgrade --no-cache && apk add --no-cache wget && addgroup -S app && adduser -S -G app app
WORKDIR /app
COPY --from=build /layers/dependencies/ ./
COPY --from=build /layers/spring-boot-loader/ ./
COPY --from=build /layers/snapshot-dependencies/ ./
COPY --from=build /layers/application/ ./
USER app
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseZGC -XX:+ExitOnOutOfMemoryError"
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=5 \
  CMD wget -qO- http://localhost:8080/actuator/health/liveness || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
