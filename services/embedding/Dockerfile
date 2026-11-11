ARG DOCKER_REGISTRY=public.ecr.aws/docker/library
FROM ${DOCKER_REGISTRY}/maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY backend/.mvn/project-parent/pom.xml backend/.mvn/project-parent/pom.xml
COPY services/embedding/pom.xml services/embedding/pom.xml
COPY services/embedding/src services/embedding/src
RUN --mount=type=cache,id=zhizhida-embedding-maven,target=/root/.m2 \
    mvn -B -ntp -f services/embedding/pom.xml -Dmaven.test.skip=true compile dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=/workspace/lib
COPY backend/docker/Healthcheck.java /workspace/healthcheck/Healthcheck.java
RUN javac --release 21 -d /workspace/healthcheck /workspace/healthcheck/Healthcheck.java

FROM ${DOCKER_REGISTRY}/eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/.runtime/embedding-build/classes /app/classes
COPY --from=build /workspace/lib /app/lib
COPY --from=build /workspace/healthcheck/Healthcheck.class /app/healthcheck/Healthcheck.class
EXPOSE 18092
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s CMD ["java", "-cp", "/app/healthcheck", "Healthcheck", "http://127.0.0.1:18092/health"]
ENTRYPOINT ["java", "-Xmx768m", "-XX:ActiveProcessorCount=2", "-Dembedding.host=0.0.0.0", "-cp", "/app/classes:/app/lib/*", "cn.edu.whu.zhizhida.embedding.EmbeddingServer"]
