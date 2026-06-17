FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml ./pom.xml
COPY services/gateway/pom.xml ./services/gateway/pom.xml
COPY services/intake-orchestrator/pom.xml ./services/intake-orchestrator/pom.xml
COPY services/mail-ingress/pom.xml ./services/mail-ingress/pom.xml
RUN mvn -pl services/intake-orchestrator -DskipTests dependency:go-offline

COPY services/intake-orchestrator/src ./services/intake-orchestrator/src
RUN mvn -pl services/intake-orchestrator -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/services/intake-orchestrator/target/intake-orchestrator-*.jar /app/intake-orchestrator.jar

ENV PORT=8081
EXPOSE 8081

ENTRYPOINT ["java", "-jar", "/app/intake-orchestrator.jar"]
