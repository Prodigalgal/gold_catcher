FROM ghcr.io/sagernet/sing-box:v1.14.2@sha256:afbffd294c5eb3519cc7b4587299ef189bb0a2ca2f667cb6301fdb6b9bce9558 AS proxy-core

FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY . .
RUN chmod +x gradlew && ./gradlew --no-daemon bootJar

FROM eclipse-temurin:25-jre
ARG SOURCE_COMMIT=development
ENV GOLD_SOURCE_COMMIT=$SOURCE_COMMIT
LABEL org.opencontainers.image.source="https://github.com/Prodigalgal/gold_catcher"
LABEL org.opencontainers.image.revision=$SOURCE_COMMIT
WORKDIR /app
RUN useradd --system --uid 10001 --create-home goldcatcher
RUN mkdir -p /app/data && chown goldcatcher /app/data
COPY --from=build /workspace/build/libs/*.jar /app/app.jar
COPY --from=proxy-core /usr/local/bin/sing-box /usr/local/bin/sing-box
RUN /usr/local/bin/sing-box version
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-XX:MaxRAMPercentage=65", "-jar", "/app/app.jar"]
