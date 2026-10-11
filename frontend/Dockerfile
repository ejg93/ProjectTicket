# 화면 이미지(`36a`). 세 단계다 — 의존성 · 빌드 · 실행. 실행 이미지에는 `next build` 의 standalone 결과만 들어간다.
FROM node:22-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci

FROM node:22-alpine AS build
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .
# **`rewrites()` 의 목적지는 `next build` 때 굳는다**(`stack.md` 「기억으로 쓰면 틀리는 자리」). 실행할 때 env 로 줘도
# `/api` 프록시는 안 바뀐다 — 그래서 빌드 인자다. k8s 에서는 앱 Service 이름(`k8s-up.sh` 가 준다).
ARG BACKEND_ORIGIN=http://app:8080
ENV BACKEND_ORIGIN=$BACKEND_ORIGIN
ENV NEXT_TELEMETRY_DISABLED=1
RUN npm run build

FROM node:22-alpine
WORKDIR /app
ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1
ENV PORT=3000
ENV HOSTNAME=0.0.0.0
# root 로 안 돌린다(`D9`). 이미지에 있는 `node` 사용자를 쓴다.
COPY --from=build --chown=node:node /app/.next/standalone ./
COPY --from=build --chown=node:node /app/.next/static ./.next/static
USER node
EXPOSE 3000
CMD ["node", "server.js"]
