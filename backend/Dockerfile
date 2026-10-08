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
COPY --from=build /workspace/build/libs/*.jar /app/app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65", "-jar", "/app/app.jar"]
