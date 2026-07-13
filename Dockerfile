FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app

COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app

ENV DB_STORAGE_DIR=/data/dbs
EXPOSE 8080
VOLUME ["/data/dbs"]

COPY --from=build /app/target/DatabaseEngine-1.0-SNAPSHOT.jar /app/database-engine.jar

ENTRYPOINT ["java", "-jar", "/app/database-engine.jar"]
