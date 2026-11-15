# The console, built with Node.
FROM node:22-alpine AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
RUN npm run build

# The service, built with Gradle; the console is packaged into the jar.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
COPY --from=web /web/dist web/dist
RUN ./gradlew :service:bootJar --no-daemon -q

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 cashback
COPY --from=build /src/service/build/libs/cashback-claims-ledger.jar app.jar
COPY config config
COPY reports reports
COPY demo demo
USER cashback
EXPOSE 8790
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
