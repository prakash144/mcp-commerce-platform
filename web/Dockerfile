# syntax=docker/dockerfile:1
# Dev container: Node 22 + Vite dev server with hot reload.
# The compose file bind-mounts host source over /app; anonymous volume at
# /app/node_modules persists npm packages across restarts.

FROM node:22-alpine
RUN apk add --no-cache bash
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
EXPOSE 5173
CMD ["sh", "-c", "npm run dev -- --host 0.0.0.0"]