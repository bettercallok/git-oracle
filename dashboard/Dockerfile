# The dashboard as static files behind nginx.
#   docker build --build-arg VITE_API_URL=/api/v1 -t gitoracle/dashboard dashboard
# VITE_API_URL is baked in at build time. The default, /api/v1, assumes the
# ingress sends /api and /webhook to the gateway on the dashboard's own host.
FROM node:22-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
ARG VITE_API_URL=/api/v1
ENV VITE_API_URL=${VITE_API_URL}
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist /usr/share/nginx/html
EXPOSE 8080
