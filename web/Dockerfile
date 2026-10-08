FROM node:22-alpine AS build
WORKDIR /workspace
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
ARG SOURCE_COMMIT=development
ENV VITE_SOURCE_COMMIT=$SOURCE_COMMIT
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.29-alpine
ARG SOURCE_COMMIT=development
LABEL org.opencontainers.image.source="https://github.com/Prodigalgal/gold_catcher"
LABEL org.opencontainers.image.revision=$SOURCE_COMMIT
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/dist /usr/share/nginx/html
EXPOSE 8080
