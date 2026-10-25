FROM node@sha256:55fb5f5a527ea2c5c4a95f963e48ef90aa38c7b346054fdc8ee9957a2b55d205 AS build

WORKDIR /workspace
RUN npm install --global pnpm@10.34.7
COPY frontend/package.json frontend/pnpm-lock.yaml frontend/.npmrc ./
RUN pnpm install --frozen-lockfile
COPY frontend/ ./
RUN pnpm run build

FROM nginx@sha256:47928bf67eb62a1863cb08aaf98f7082a77d318a7f92af3d9fe2c0fd2723df10
ENV NGINX_ENVSUBST_FILTER=^APP_HOSTNAME$
COPY frontend/nginx.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /workspace/dist/ /usr/share/nginx/html/
EXPOSE 80 443
