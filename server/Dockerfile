# Honer Cloud — образ для Railway (builder: DOCKERFILE, см. railway.json)
FROM node:22-alpine

ENV NODE_ENV=production \
    PORT=3000 \
    MEDIA_DIR=/data/media \
    # Облачный браузер использует системный Chromium из apk, а не браузеры Playwright.
    PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 \
    CHROMIUM_PATH=/usr/bin/chromium-browser

WORKDIR /app

# Системный Chromium и шрифты (в т. ч. кириллица) для облачного браузера.
RUN apk add --no-cache \
    chromium nss freetype harfbuzz ca-certificates \
    ttf-freefont font-noto font-noto-cjk

# Сначала только манифесты — слой с зависимостями кешируется между деплоями.
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --no-audit --no-fund && npm cache clean --force

COPY src ./src
# Статические материалы (скриншоты карточки магазина и пр.), которые отдаёт сервер.
COPY assets ./assets

# Том Railway монтируется в MEDIA_DIR и принадлежит root, поэтому процесс запускается от root
# (иначе запись файлов в том невозможна). Каталоги создаются и для запуска без тома.
RUN mkdir -p /data/media /data/cloud

EXPOSE 3000
# exec-форма: SIGTERM от Railway приходит напрямую в node → корректное завершение.
CMD ["node", "src/server.js"]
