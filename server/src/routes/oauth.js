// Вход через Яндекс (implicit-флоу, client_secret НЕ нужен) + аккаунт/бэкап для сохранения данных
// после переустановки (план прил. п.12). Токен проверяется серверным вызовом login.yandex.ru/info.
import { randomUUID, randomBytes } from 'node:crypto';
import { badRequest, forbidden, notFound, unauthorized } from '../errors.js';
import { iso } from '../db.js';

// Куда Яндекс вернёт пользователя — должно совпадать с redirect URI в кабинете приложения.
// Приложение ходит через релей (из РФ Railway заблокирован), поэтому разрешаем оба релея + сам Railway.
const ALLOWED_BASES = [
  'https://honer.xoner4.deno.net',
  'https://honer-relay.vladislavponomarev16.workers.dev',
  'https://honor-pk-agent-ios-production.up.railway.app',
];
const DEFAULT_BASE = ALLOWED_BASES[0];
const CALLBACK_PATH = '/v1/oauth/yandex/callback';
const MAX_BACKUP_BYTES = 8 * 1024 * 1024; // 8 МБ на резервную копию

const callbackFor = (base) => (ALLOWED_BASES.includes(base) ? base : DEFAULT_BASE) + CALLBACK_PATH;

export default async function oauthRoutes(app) {
  const { db, auth, config } = app.ctx;

  // 1) Приложение начинает вход: создаём state (связь окна браузера с устройством) и адрес авторизации.
  app.post('/v1/oauth/yandex/start', {
    preHandler: auth.requireDevice,
    schema: { body: { type: 'object', properties: { base: { type: 'string', maxLength: 200 } } } },
  }, async (req) => {
    const state = randomBytes(18).toString('hex');
    const redirect = callbackFor(req.body?.base || '');
    await db.query(
      'INSERT INTO oauth_states (state, device_id, user_id, provider, created_at) VALUES ($1,$2,$3,$4,$5)',
      [state, req.device.id, req.device.user_id, 'yandex', new Date()]);
    const authUrl = 'https://oauth.yandex.ru/authorize?response_type=token'
      + `&client_id=${encodeURIComponent(config.yandexClientId)}`
      + `&redirect_uri=${encodeURIComponent(redirect)}`
      + `&state=${encodeURIComponent(state)}`
      + '&force_confirm=yes';
    return { authUrl, state };
  });

  // 2) Яндекс возвращает пользователя сюда с токеном в #фрагменте (на сервер фрагмент не приходит).
  //    Отдаём страницу, которая читает токен из адреса и отправляет его на /complete.
  app.get(CALLBACK_PATH, async (_req, reply) => {
    reply.header('content-type', 'text/html; charset=utf-8');
    return CALLBACK_HTML;
  });

  // 3) Завершение: проверяем токен в Яндексе, привязываем аккаунт или восстанавливаем прежний.
  app.post('/v1/oauth/yandex/complete', {
    schema: {
      body: {
        type: 'object', required: ['state', 'token'],
        properties: { state: { type: 'string', maxLength: 64 }, token: { type: 'string', maxLength: 4096 } },
      },
    },
  }, async (req) => {
    const st = await db.one('SELECT * FROM oauth_states WHERE state = $1', [req.body.state]);
    if (!st) throw notFound('Сессия входа не найдена');
    if (st.done) return { ok: true };

    // Проверяем токен серверным запросом к Яндексу (секрет не нужен).
    let info;
    try {
      const r = await fetch('https://login.yandex.ru/info?format=json', {
        headers: { Authorization: `OAuth ${req.body.token}` }, signal: AbortSignal.timeout(10000),
      });
      if (!r.ok) throw new Error(`yandex info ${r.status}`);
      info = await r.json();
    } catch (e) {
      await db.query('UPDATE oauth_states SET done = TRUE, error = $2 WHERE state = $1', [st.state, 'token_invalid']);
      throw unauthorized('Не удалось подтвердить вход в Яндексе');
    }
    const yandexId = String(info.id || '');
    if (!yandexId) {
      await db.query('UPDATE oauth_states SET done = TRUE, error = $2 WHERE state = $1', [st.state, 'no_id']);
      throw badRequest('Яндекс не вернул идентификатор');
    }
    const email = info.default_email || (Array.isArray(info.emails) ? info.emails[0] : null) || null;
    const name = info.real_name || info.display_name || info.login || null;

    const existing = await db.one('SELECT * FROM users WHERE yandex_id = $1', [yandexId]);
    let restored = false;
    const now = new Date();
    if (existing && existing.id !== st.user_id) {
      // Этот Яндекс уже привязан к другому user (прошлая установка) — ПЕРЕНОС: цепляем устройство к нему.
      await db.query('UPDATE devices SET user_id = $2, updated_at = $3 WHERE id = $1', [st.device_id, existing.id, now]);
      await db.query('UPDATE users SET account_email = $2, account_name = $3, account_linked_at = $4 WHERE id = $1',
        [existing.id, email, name, now]);
      // Старый осиротевший user (если на нём больше нет устройств) — убираем.
      const left = await db.one('SELECT count(*)::int AS c FROM devices WHERE user_id = $1', [st.user_id]);
      if (Number(left?.c || 0) === 0) await db.query('DELETE FROM users WHERE id = $1', [st.user_id]).catch(() => {});
      restored = true;
    } else {
      // Первая привязка этого аккаунта к текущему пользователю.
      await db.query(
        'UPDATE users SET yandex_id = $2, account_email = $3, account_name = $4, account_linked_at = $5 WHERE id = $1',
        [st.user_id, yandexId, email, name, now]);
    }
    await db.query('UPDATE oauth_states SET done = TRUE, restored = $2, email = $3 WHERE state = $1',
      [st.state, restored, email]);
    return { ok: true };
  });

  // 4) Приложение опрашивает результат входа по своему state.
  app.get('/v1/oauth/yandex/result', {
    preHandler: auth.requireDevice,
    schema: { querystring: { type: 'object', required: ['state'], properties: { state: { type: 'string', maxLength: 64 } } } },
  }, async (req) => {
    const st = await db.one('SELECT * FROM oauth_states WHERE state = $1', [req.query.state]);
    if (!st) throw notFound('Сессия входа не найдена');
    if (st.device_id !== req.device.id) throw forbidden();
    return { done: !!st.done, restored: !!st.restored, email: st.email || null, error: st.error || null };
  });

  // --- Аккаунт и резервная копия данных ---

  app.get('/v1/account', { preHandler: auth.requireDevice }, async (req) => {
    const u = await db.one('SELECT yandex_id, account_email, account_name FROM users WHERE id = $1', [req.device.user_id]);
    const b = await db.one('SELECT size, updated_at FROM backups WHERE user_id = $1', [req.device.user_id]);
    return {
      linked: !!u?.yandex_id,
      email: u?.account_email || null,
      name: u?.account_name || null,
      backup: b ? { size: b.size, updatedAt: iso(b.updated_at) } : null,
    };
  });

  // Загрузить резервную копию (чаты+настройки) под аккаунт. Только для привязанного аккаунта.
  app.put('/v1/account/backup', {
    preHandler: auth.requireDevice,
    schema: { body: { type: 'object', required: ['data'], properties: { data: { type: 'string', maxLength: 12_000_000 } } } },
  }, async (req) => {
    const u = await db.one('SELECT yandex_id FROM users WHERE id = $1', [req.device.user_id]);
    if (!u?.yandex_id) throw forbidden('Сначала войдите в аккаунт');
    const data = req.body.data;
    const size = Buffer.byteLength(data, 'utf8');
    if (size > MAX_BACKUP_BYTES) throw badRequest('Копия слишком большая');
    await db.query(
      `INSERT INTO backups (user_id, data, size, updated_at) VALUES ($1,$2,$3,$4)
         ON CONFLICT (user_id) DO UPDATE SET data = $2, size = $3, updated_at = $4`,
      [req.device.user_id, data, size, new Date()]);
    return { ok: true, size };
  });

  // Скачать резервную копию (для восстановления после входа).
  app.get('/v1/account/backup', { preHandler: auth.requireDevice }, async (req) => {
    const b = await db.one('SELECT data, updated_at FROM backups WHERE user_id = $1', [req.device.user_id]);
    if (!b) return { data: null, updatedAt: null };
    return { data: b.data, updatedAt: iso(b.updated_at) };
  });

  // Выйти из аккаунта (отвязать этот user). Данные-копия остаются для повторного входа.
  app.post('/v1/account/logout', { preHandler: auth.requireDevice }, async (req) => {
    await db.query(
      'UPDATE users SET yandex_id = NULL, account_email = NULL, account_name = NULL, account_linked_at = NULL WHERE id = $1',
      [req.device.user_id]);
    return { ok: true };
  });
}

const CALLBACK_HTML = `<!doctype html><html lang="ru"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Honer AI — вход</title>
<style>
  body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;
    font-family:-apple-system,Segoe UI,Roboto,sans-serif;background:#0b0e14;color:#eef2f8}
  .card{max-width:340px;padding:28px;text-align:center}
  .x{font-weight:800;font-size:42px;background:linear-gradient(120deg,#5bc0ff,#2e6be6);
    -webkit-background-clip:text;background-clip:text;color:transparent}
  h1{font-size:20px;margin:14px 0 6px} p{color:#9aa3b2;font-size:14px;line-height:1.5;margin:0}
  .ok{color:#37d67a} .err{color:#ff6b6b}
</style></head><body><div class="card">
  <div class="x">✕</div>
  <h1 id="t">Завершаем вход…</h1>
  <p id="m">Секунду, подтверждаем аккаунт Яндекса.</p>
</div><script>
(function(){
  function q(h){var o={};(h||'').replace(/^#/,'').split('&').forEach(function(p){
    var i=p.indexOf('=');if(i>0)o[decodeURIComponent(p.slice(0,i))]=decodeURIComponent(p.slice(i+1));});return o;}
  var p=q(location.hash);var t=document.getElementById('t'),m=document.getElementById('m');
  if(!p.access_token||!p.state){t.textContent='Не получилось войти';t.className='err';
    m.textContent='Токен не пришёл. Попробуйте ещё раз из приложения.';return;}
  fetch('/v1/oauth/yandex/complete',{method:'POST',headers:{'content-type':'application/json'},
    body:JSON.stringify({state:p.state,token:p.access_token})})
  .then(function(r){return r.ok?r.json():Promise.reject(r);})
  .then(function(){t.textContent='Готово!';t.className='ok';
    m.textContent='Вернитесь в приложение Honer AI — вход выполнен.';
    setTimeout(function(){try{location.href='honerai://oauth-done';}catch(e){}},400);})
  .catch(function(){t.textContent='Не получилось войти';t.className='err';
    m.textContent='Попробуйте ещё раз из приложения.';});
})();
</script></body></html>`;
