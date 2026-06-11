FROM node:24-alpine AS build
WORKDIR /app
COPY services/frontend/package*.json ./
RUN npm ci
COPY services/frontend/ ./
ARG LEGALGATE_API_BASE_URL=""
ENV LEGALGATE_API_BASE_URL=$LEGALGATE_API_BASE_URL
RUN npm run build

FROM nginx:1.29-alpine
COPY services/frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist/frontend/browser /usr/share/nginx/html
EXPOSE 80
HEALTHCHECK --interval=10s --timeout=3s --retries=5 CMD wget -qO- http://127.0.0.1/ >/dev/null || exit 1
