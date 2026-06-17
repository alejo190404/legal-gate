FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./pom.xml
COPY services/gateway/pom.xml ./services/gateway/pom.xml
COPY services/intake-orchestrator/pom.xml ./services/intake-orchestrator/pom.xml
COPY services/mail-ingress/pom.xml ./services/mail-ingress/pom.xml
RUN mvn -pl services/mail-ingress -DskipTests dependency:go-offline
COPY services/mail-ingress/src ./services/mail-ingress/src
RUN mvn -pl services/mail-ingress -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /workspace/services/mail-ingress/target/mail-ingress-*.jar /app/app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
