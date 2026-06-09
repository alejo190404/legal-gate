FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml ./pom.xml
COPY services/gateway/pom.xml ./services/gateway/pom.xml
COPY services/intake-orchestrator/pom.xml ./services/intake-orchestrator/pom.xml
RUN mvn -pl services/gateway -DskipTests dependency:go-offline

COPY services/gateway/src ./services/gateway/src
RUN mvn -pl services/gateway -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/services/gateway/target/gateway-*.jar /app/gateway.jar

ENV PORT=8080
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/gateway.jar"]