ARG DOCKER_REGISTRY=public.ecr.aws/docker/library

FROM ${DOCKER_REGISTRY}/maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -Dmaven.test.skip=true dependency:go-offline
COPY src ./src
RUN mvn -q -Dmaven.test.skip=true package
COPY docker/Healthcheck.java ./healthcheck/Healthcheck.java
RUN javac -d ./healthcheck ./healthcheck/Healthcheck.java

FROM ${DOCKER_REGISTRY}/eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
COPY --from=build /app/healthcheck/Healthcheck.class healthcheck/Healthcheck.class
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
