# syntax=docker/dockerfile:1

FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /workspace

COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
RUN mvn -B -ntp -DskipTests package \
    && cp target/cityseek-server-*.jar /workspace/app.jar


FROM eclipse-temurin:17-jre-jammy AS runtime

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 cityseek \
    && useradd --system --uid 10001 --gid cityseek --home-dir /app cityseek

WORKDIR /app

RUN mkdir -p /app/data/images \
    && chown -R cityseek:cityseek /app

COPY --from=build --chown=cityseek:cityseek /workspace/app.jar /app/app.jar

USER cityseek

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "/app/app.jar"]