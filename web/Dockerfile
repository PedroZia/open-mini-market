# Publicação da SPA (passo 1214): build do workspace `@minimarket/web` e entrega pelo Caddy, que
# também faz o proxy de `/api` — web e API na **mesma origem**, sem CORS.
#
# O contexto é a **raiz do monorepo** (docker-compose.yml → `web`), e não `web/`, porque o build
# depende do workspace `@minimarket/api-client`; o `.dockerignore` da raiz deixa o contexto enxuto.

# --- Build ---
# Node só no build: o runtime é o Caddy (decidido no 1214 e reaproveitado pelo 1305).
FROM node:22-alpine AS build
WORKDIR /app

# Manifests primeiro (cache do `npm ci`): o lockfile da raiz cobre os workspaces e o filtro limita a
# instalação ao que o web precisa — `@minimarket/api-client` é TypeScript puro no `src/`, sem deps.
COPY package.json package-lock.json ./
COPY web/package.json web/package.json
COPY packages/api-client/package.json packages/api-client/package.json
RUN npm ci --workspace=@minimarket/web

# Fontes do web e do client compartilhado; `npm run build` roda `tsc --noEmit && vite build`.
COPY web ./web
COPY packages/api-client ./packages/api-client
RUN npm run build --workspace=@minimarket/web

# --- Runtime ---
# A imagem oficial do Caddy já define `CMD caddy run --config /etc/caddy/Caddyfile` (porta 80);
# `srv` é o diretório de trabalho e o `root` do Caddyfile.
FROM caddy:2-alpine
COPY web/Caddyfile /etc/caddy/Caddyfile
COPY --from=build /app/web/dist /srv
