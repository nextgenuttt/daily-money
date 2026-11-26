# Built by Cloud Build (gcloud run deploy --source .). Stage 1 compiles, stage 2 is a small Java 17 runtime.
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle* build.gradle* ./
COPY gradle gradle
RUN ./gradlew --no-daemon -q dependencies > /dev/null || true
COPY src src
RUN ./gradlew --no-daemon -q bootJar -x test && cp build/libs/*.jar /app.jar

FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S app && adduser -S app -G app
USER app
COPY --from=build /app.jar /app.jar
# Fast start matters more than peak speed on a server that sleeps when idle.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:TieredStopAtLevel=1 -Xss512k"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app.jar"]
