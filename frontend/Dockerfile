ARG DOCKER_REGISTRY=public.ecr.aws/docker/library

FROM ${DOCKER_REGISTRY}/node:20-alpine AS build
WORKDIR /app
COPY package.json pnpm-lock.yaml ./
RUN corepack pnpm install --frozen-lockfile
COPY . .
RUN VITE_API_BASE_URL= pnpm build

FROM ${DOCKER_REGISTRY}/nginx:1.27-alpine
COPY --from=build /app/dist /usr/share/nginx/html
# Inject healthz endpoint
RUN echo "OK" > /usr/share/nginx/html/healthz
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
