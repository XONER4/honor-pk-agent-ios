// Облачный браузер: на сервере крутится headless-Chromium (Playwright + stealth), который открывает
// сайты сервисов (Wildberries, Ozon, ВК и т. п.), проходит анти-бот и держит сессию входа пользователя.
// Пользователь логинится сам через окно в чате (пересылаем тапы/ввод), дальше нейросеть действует в этом
// же сеансе. Модуль полностью изолирован: Playwright и Chromium подгружаются лениго; если их нет —
// облачные функции возвращают понятную ошибку, а основной сервер продолжает работать.
//
// Память дорогая: держим ОДИН браузер и не больше N контекстов; простаивающие закрываем, cookи храним
// на диске, чтобы вход не терялся.

import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

const DATA_DIR = process.env.CLOUD_BROWSER_DIR || path.join(process.env.MEDIA_DIR ? path.dirname(process.env.MEDIA_DIR) : '/data', 'cloud');
const MAX_CONTEXTS = Number(process.env.CLOUD_MAX_CONTEXTS || 4);
const IDLE_MS = Number(process.env.CLOUD_IDLE_MS || 5 * 60 * 1000);
const CHROMIUM_PATH = process.env.CHROMIUM_PATH || process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH || undefined;

// Мобильный «отпечаток» — сайты отдают лёгкую мобильную вёрстку, как на телефоне пользователя.
const DEVICE = {
  viewport: { width: 412, height: 915 },
  userAgent: 'Mozilla/5.0 (Linux; Android 14; V2312A) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36',
  locale: 'ru-RU', timezoneId: 'Europe/Moscow', isMobile: true, hasTouch: true, deviceScaleFactor: 2,
};

// Стартовые адреса известных сервисов (веб-версии). Мобильные-только приложения сюда не входят.
export const SERVICE_URLS = {
  wildberries: 'https://www.wildberries.ru/',
  ozon: 'https://www.ozon.ru/',
  yandex_market: 'https://market.yandex.ru/',
  vk: 'https://m.vk.com/',
  yandex_mail: 'https://mail.yandex.ru/',
  mts: 'https://login.mts.ru/',
};

let browserPromise = null;
let stealthApplied = false;
const contexts = new Map(); // sessionId -> { context, page, service, deviceId, lastUsed, timer }

async function getChromium() {
  // Динамический импорт: если пакеты/браузер не установлены — вернём внятную ошибку, сервер не падает.
  const extra = await import('playwright-extra').catch(() => null);
  if (!extra) throw new CloudUnavailable('playwright-extra не установлен на сервере');
  const chromium = extra.chromium;
  if (!stealthApplied) {
    try {
      const stealthMod = await import('puppeteer-extra-plugin-stealth');
      chromium.use((stealthMod.default || stealthMod)());
      stealthApplied = true;
    } catch { /* без stealth анти-бот пройдён не будет, но не падаем */ }
  }
  return chromium;
}

export class CloudUnavailable extends Error {}

async function getBrowser() {
  if (browserPromise) return browserPromise;
  browserPromise = (async () => {
    const chromium = await getChromium();
    const launchOpts = {
      headless: true,
      args: ['--no-sandbox', '--disable-dev-shm-usage', '--disable-blink-features=AutomationControlled'],
    };
    if (CHROMIUM_PATH) launchOpts.executablePath = CHROMIUM_PATH;
    const browser = await chromium.launch(launchOpts);
    browser.on('disconnected', () => { browserPromise = null; });
    return browser;
  })().catch((e) => { browserPromise = null; throw e; });
  return browserPromise;
}

function statePath(deviceId, service) {
  const safe = crypto.createHash('sha256').update(`${deviceId}|${service}`).digest('hex').slice(0, 24);
  return path.join(DATA_DIR, `${service}-${safe}.json`);
}

function loadState(deviceId, service) {
  try { return JSON.parse(fs.readFileSync(statePath(deviceId, service), 'utf8')); } catch { return undefined; }
}

async function saveState(entry) {
  try {
    fs.mkdirSync(DATA_DIR, { recursive: true });
    const state = await entry.context.storageState();
    fs.writeFileSync(statePath(entry.deviceId, entry.service), JSON.stringify(state));
  } catch { /* не критично */ }
}

function touch(entry) {
  entry.lastUsed = Date.now();
  if (entry.timer) clearTimeout(entry.timer);
  entry.timer = setTimeout(() => closeSession(entry.sessionId), IDLE_MS);
}

async function evictIfNeeded() {
  if (contexts.size < MAX_CONTEXTS) return;
  // Закрываем самый давно не использованный.
  let oldest = null;
  for (const e of contexts.values()) if (!oldest || e.lastUsed < oldest.lastUsed) oldest = e;
  if (oldest) await closeSession(oldest.sessionId);
}

/** Открыть (или переиспользовать) сессию сервиса для устройства. Возвращает первый снимок экрана. */
export async function openSession({ deviceId, service }) {
  const url = SERVICE_URLS[service];
  if (!url) throw new CloudUnavailable(`Сервис не поддерживается облачным браузером: ${service}`);
  // Переиспользуем живую сессию этого же сервиса.
  for (const e of contexts.values()) {
    if (e.deviceId === deviceId && e.service === service) { touch(e); return snapshot(e); }
  }
  await evictIfNeeded();
  const browser = await getBrowser();
  const contextOpts = { ...DEVICE };
  const saved = loadState(deviceId, service);
  if (saved) contextOpts.storageState = saved;
  const context = await browser.newContext(contextOpts);
  await context.addInitScript(() => {
    Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
  });
  const page = await context.newPage();
  const sessionId = crypto.randomUUID();
  const entry = { sessionId, context, page, service, deviceId, lastUsed: Date.now(), timer: null };
  contexts.set(sessionId, entry);
  touch(entry);
  try {
    await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 30000 });
    // Ждём, пока пройдёт анти-бот-проверка и появится реальный контент (не «Проверяем браузер»).
    for (let i = 0; i < 8; i++) {
      await page.waitForTimeout(1500);
      const body = (await page.innerText('body').catch(() => '')).replace(/\s+/g, ' ').trim();
      if (!/проверяем браузер|подозрительн|captcha|капч|доступ ограничен/i.test(body) && body.length > 200) break;
    }
  } catch { /* отдадим снимок как есть */ }
  return snapshot(entry);
}

function requireSession(sessionId) {
  const e = contexts.get(sessionId);
  if (!e) throw new CloudUnavailable('Сессия облачного браузера не найдена или закрыта');
  return e;
}

async function snapshot(entry) {
  touch(entry);
  let png = null, title = '', url = '', loggedInHint = false;
  try {
    png = await entry.page.screenshot({ type: 'jpeg', quality: 60 });
    title = await entry.page.title().catch(() => '');
    url = entry.page.url();
    const body = (await entry.page.innerText('body').catch(() => '')).slice(0, 400);
    loggedInHint = !/войти|вход|log ?in|sign ?in|авториз/i.test(body);
    const blocked = /проверяем браузер|подозрительн|robot|доступ ограничен|captcha|капч/i.test(body);
    await saveState(entry);
    return {
      sessionId: entry.sessionId, service: entry.service, title, url,
      width: DEVICE.viewport.width, height: DEVICE.viewport.height,
      image: png ? `data:image/jpeg;base64,${png.toString('base64')}` : null,
      blocked, loggedInHint,
    };
  } catch (e) {
    return { sessionId: entry.sessionId, service: entry.service, title, url, image: png ? `data:image/jpeg;base64,${png.toString('base64')}` : null, error: String(e.message || e) };
  }
}

const scale = { x: 1, y: 1 }; // экран отдаём в CSS-пикселях, координаты приходят в них же

/** Пользовательский ввод в сеанс: тап, текст, клавиша, скролл, переход, назад. */
export async function input(sessionId, action) {
  const e = requireSession(sessionId);
  const p = e.page;
  switch (action.type) {
    case 'tap': await p.mouse.click(action.x * scale.x, action.y * scale.y); break;
    case 'type': await p.keyboard.type(String(action.text || ''), { delay: 20 }); break;
    case 'key': await p.keyboard.press(String(action.key || 'Enter')); break;
    case 'scroll': await p.mouse.wheel(0, Number(action.dy || 400)); break;
    case 'navigate': await p.goto(String(action.url || SERVICE_URLS[e.service]), { waitUntil: 'domcontentloaded', timeout: 30000 }); break;
    case 'back': await p.goBack({ timeout: 15000 }).catch(() => {}); break;
    case 'wait': break;
    default: throw new CloudUnavailable(`Неизвестное действие: ${action.type}`);
  }
  await p.waitForTimeout(action.settle ?? 1200);
  return snapshot(e);
}

/** Текстовый слепок страницы для нейросети (что видно и что можно нажать). */
export async function readPage(sessionId) {
  const e = requireSession(sessionId);
  touch(e);
  const text = (await e.page.innerText('body').catch(() => '')).slice(0, 6000);
  const controls = await e.page.evaluate(() => {
    const out = [];
    const els = document.querySelectorAll('a,button,input,[role=button],[onclick]');
    for (const el of els) {
      const r = el.getBoundingClientRect();
      if (r.width < 4 || r.height < 4 || r.bottom < 0 || r.top > innerHeight) continue;
      const label = (el.innerText || el.getAttribute('placeholder') || el.getAttribute('aria-label') || el.value || '').trim().slice(0, 60);
      if (!label) continue;
      out.push({ label, x: Math.round(r.left + r.width / 2), y: Math.round(r.top + r.height / 2), tag: el.tagName.toLowerCase() });
      if (out.length > 60) break;
    }
    return out;
  }).catch(() => []);
  return { url: e.page.url(), text, controls };
}

export async function closeSession(sessionId) {
  const e = contexts.get(sessionId);
  if (!e) return;
  contexts.delete(sessionId);
  if (e.timer) clearTimeout(e.timer);
  try { await saveState(e); } catch { /* ignore */ }
  try { await e.context.close(); } catch { /* ignore */ }
}

/** Быстрая проверка: проходит ли облачный браузер анти-бот сервиса с этого сервера (датацентр-IP). */
export async function probe(url = SERVICE_URLS.wildberries) {
  const browser = await getBrowser();
  const context = await browser.newContext({ ...DEVICE });
  const page = await context.newPage();
  try {
    await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 30000 });
    let body = '';
    for (let i = 0; i < 6; i++) {
      await page.waitForTimeout(2500);
      body = (await page.innerText('body').catch(() => '')).replace(/\s+/g, ' ').trim();
      if (!/проверяем браузер|подозрительн|captcha|капч|доступ ограничен/i.test(body) && body.length > 60) break;
    }
    const blocked = /проверяем браузер|подозрительн|captcha|капч|доступ ограничен|не так/i.test(body);
    return { url, ok: !blocked && body.length > 60, blocked, len: body.length, sample: body.slice(0, 200), title: await page.title().catch(() => '') };
  } finally {
    await context.close().catch(() => {});
  }
}

/** Кому принадлежит сессия (для проверки доступа в маршрутах). */
export function sessionDevice(sessionId) {
  return contexts.get(sessionId)?.deviceId;
}

export function status() {
  return { sessions: contexts.size, maxContexts: MAX_CONTEXTS, chromiumPath: CHROMIUM_PATH || 'bundled', services: Object.keys(SERVICE_URLS) };
}
