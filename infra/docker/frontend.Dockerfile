# syntax=docker/dockerfile:1
# ERIKA front end (Next.js standalone server).
# Build context: repository root (the build generates the API client from api/openapi.yaml).

FROM node:26-alpine AS base
RUN corepack enable
WORKDIR /src/frontend

FROM base AS deps
COPY frontend/package.json frontend/pnpm-lock.yaml frontend/pnpm-workspace.yaml ./
RUN --mount=type=cache,target=/root/.local/share/pnpm/store pnpm install --frozen-lockfile

FROM base AS build
COPY api/ /src/api/
COPY --from=deps /src/frontend/node_modules ./node_modules
COPY frontend/ ./
ENV NEXT_TELEMETRY_DISABLED=1
RUN pnpm build

FROM node:26-alpine AS runtime
WORKDIR /app
ENV NODE_ENV=production NEXT_TELEMETRY_DISABLED=1 PORT=3000 HOSTNAME=0.0.0.0
RUN addgroup --system erika && adduser --system --ingroup erika erika
COPY --from=build --chown=erika:erika /src/frontend/.next/standalone ./
COPY --from=build --chown=erika:erika /src/frontend/.next/static ./.next/static
USER erika
EXPOSE 3000
CMD ["node", "server.js"]
