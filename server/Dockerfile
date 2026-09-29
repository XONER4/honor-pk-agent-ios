# Honer Cloud — образ для Railway (builder: DOCKERFILE, см. railway.json)
FROM node:22-alpine

ENV NODE_ENV=production \
    PORT=3000 \
    MEDIA_DIR=/data/media

WORKDIR /app

# Сначала только манифесты — слой с зависимостями кешируется между деплоями.
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --no-audit --no-fund && npm cache clean --force

COPY src ./src

# Том Railway монтируется в MEDIA_DIR и принадлежит root, поэтому процесс запускается от root
# (иначе запись файлов в том невозможна). Каталог создаётся и для запуска без тома.
RUN mkdir -p /data/media

EXPOSE 3000
# exec-форма: SIGTERM от Railway приходит напрямую в node → корректное завершение.
CMD ["node", "src/server.js"]
