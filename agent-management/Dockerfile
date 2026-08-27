FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml ./
COPY agent-contracts/pom.xml agent-contracts/pom.xml
COPY agent-management/pom.xml agent-management/pom.xml
COPY agent-runtime/pom.xml agent-runtime/pom.xml
RUN mvn -q -pl agent-management -am -DskipTests dependency:go-offline

COPY agent-contracts/src agent-contracts/src
COPY agent-management/src agent-management/src
RUN mvn -q -pl agent-management -am -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/agent-management/target/agent-management-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
