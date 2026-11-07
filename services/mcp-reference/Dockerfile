ARG DOCKER_REGISTRY=public.ecr.aws/docker/library
FROM ${DOCKER_REGISTRY}/node:22.14.0-alpine
WORKDIR /app
COPY server.mjs catalog.mjs ./
ENV MCP_BIND_HOST=0.0.0.0
EXPOSE 18091
USER node
CMD ["node", "server.mjs"]
