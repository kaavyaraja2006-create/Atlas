# ATLAS web tier: the built SPA served by Caddy with a strict CSP, proxying /api.
FROM node:22-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci --ignore-scripts
COPY . .
RUN npm run build \
 && printf 'header Content-Security-Policy "%s"\n' "$(node e2e/csp-policy.mjs dist)" > /src/csp.caddy

FROM caddy:2.11.6-alpine
COPY deploy/Caddyfile /etc/caddy/Caddyfile
COPY --from=build /src/csp.caddy /etc/caddy/csp.caddy
COPY --from=build /src/dist /srv
# Caddy's image runs as root by default; drop to an unprivileged user on a high port. The
# Caddyfile writes nothing to disk, so the root filesystem can be mounted read-only.
RUN adduser -D -H -u 10002 web
USER web
ENV ATLAS_WEB_PORT=8080
EXPOSE 8080
CMD ["caddy", "run", "--config", "/etc/caddy/Caddyfile", "--adapter", "caddyfile"]
