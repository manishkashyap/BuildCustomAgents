FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml ./
COPY agent-management/pom.xml agent-management/pom.xml
COPY agent-runtime/pom.xml agent-runtime/pom.xml
RUN mvn -q -pl agent-runtime -am -DskipTests dependency:go-offline

COPY agent-runtime/src agent-runtime/src
RUN mvn -q -pl agent-runtime -am -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/agent-runtime/target/agent-runtime-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
