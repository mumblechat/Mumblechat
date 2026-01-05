# MumbleChat Desktop Relay Node
# Multi-stage build for minimal production image

# Build stage
FROM node:20-alpine AS builder

WORKDIR /app

# Copy package files
COPY package*.json ./
COPY tsconfig.json ./

# Install dependencies
RUN npm ci

# Copy source
COPY src ./src

# Build TypeScript
RUN npm run build

# Production stage
FROM node:20-alpine

WORKDIR /app

# Install production dependencies only
COPY package*.json ./
RUN npm ci --production && npm cache clean --force

# Copy built files
COPY --from=builder /app/dist ./dist
COPY config.example.json ./config.json

# Create data directories
RUN mkdir -p data logs keystore

# Create non-root user
RUN addgroup -g 1001 -S mumblechat && \
    adduser -S -D -H -u 1001 -s /sbin/nologin -G mumblechat mumblechat && \
    chown -R mumblechat:mumblechat /app

USER mumblechat

# Expose ports
# 19370 - TCP P2P
# 19371 - WebSocket
# 19380 - Monitoring API
EXPOSE 19370 19371 19380

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=10s --retries=3 \
  CMD node -e "require('http').get('http://localhost:19380/health', (r) => process.exit(r.statusCode === 200 ? 0 : 1))"

# Volume for persistent data
VOLUME ["/app/data", "/app/keystore"]

# Environment
ENV NODE_ENV=production

# Start relay
ENTRYPOINT ["node", "dist/cli.js"]
CMD ["start"]
