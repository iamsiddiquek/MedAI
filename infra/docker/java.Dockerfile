# Builds one Java module. Build context is the repository root:
#   docker build -f infra/docker/java.Dockerfile --build-arg MODULE=core .
FROM maven:3.9-eclipse-temurin-25 AS build
ARG MODULE
WORKDIR /src
COPY pom.xml .
COPY services/core/pom.xml services/core/pom.xml
COPY services/api-gateway/pom.xml services/api-gateway/pom.xml
RUN mvn -q -B -pl services/${MODULE} -am dependency:go-offline
COPY services/${MODULE} services/${MODULE}
RUN mvn -q -B -pl services/${MODULE} -am package -DskipTests \
 && cp services/${MODULE}/target/*.jar /app.jar

FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 afia
USER 10001
COPY --from=build /app.jar /app.jar
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app.jar"]
