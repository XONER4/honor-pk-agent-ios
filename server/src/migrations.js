// Идемпотентные миграции: выполняются при каждом старте. Новые изменения схемы добавлять в конец
// (ALTER TABLE … ADD COLUMN IF NOT EXISTS …), существующие операторы не менять.
// Временные метки пишутся из JS (миллисекундная точность — важно для курсоров `after=`).

const STATEMENTS = [
  `CREATE TABLE IF NOT EXISTS users (
     id UUID PRIMARY KEY,
     created_at TIMESTAMPTZ NOT NULL
   )`,

  `CREATE TABLE IF NOT EXISTS devices (
     id UUID PRIMARY KEY,
     user_id UUID NOT NULL,
     install_id TEXT NOT NULL UNIQUE,
     platform TEXT NOT NULL,
     device_model TEXT,
     device_name TEXT,
     os_version TEXT,
     app_version TEXT,
     display_name TEXT,
     birthday TEXT,
     language TEXT,
     license_accepted_at TIMESTAMPTZ,
     push_token TEXT,
     blocked BOOLEAN NOT NULL DEFAULT FALSE,
     block_reason TEXT,
     messages_sent BIGINT NOT NULL DEFAULT 0,
     seconds_in_app BIGINT NOT NULL DEFAULT 0,
     register_count INT NOT NULL DEFAULT 1,
     installed_at TIMESTAMPTZ NOT NULL,
     updated_at TIMESTAMPTZ NOT NULL,
     last_seen_at TIMESTAMPTZ
   )`,
  `CREATE INDEX IF NOT EXISTS devices_user_idx ON devices (user_id)`,
  `CREATE INDEX IF NOT EXISTS devices_push_idx ON devices (push_token)`,

  `CREATE TABLE IF NOT EXISTS admins (
     id UUID PRIMARY KEY,
     email TEXT NOT NULL UNIQUE,
     name TEXT,
     created_at TIMESTAMPTZ NOT NULL,
     last_login_at TIMESTAMPTZ,
     last_seen_at TIMESTAMPTZ
   )`,

  // Храним только SHA-256 токена. kind: device | admin.
  `CREATE TABLE IF NOT EXISTS tokens (
     hash TEXT PRIMARY KEY,
     kind TEXT NOT NULL,
     subject_id UUID NOT NULL,
     created_at TIMESTAMPTZ NOT NULL,
     expires_at TIMESTAMPTZ
   )`,
  `CREATE INDEX IF NOT EXISTS tokens_subject_idx ON tokens (subject_id)`,

  // Один чат «пользователь ↔ админ» на устройство; *_read_seq / *_cleared_seq — указатели по сторонам.
  `CREATE TABLE IF NOT EXISTS chats (
     id UUID PRIMARY KEY,
     device_id UUID NOT NULL UNIQUE,
     kind TEXT NOT NULL DEFAULT 'admin',
     ai_enabled BOOLEAN NOT NULL DEFAULT FALSE,
     pinned_message_id UUID,
     user_read_seq BIGINT NOT NULL DEFAULT 0,
     admin_read_seq BIGINT NOT NULL DEFAULT 0,
     user_read_message_id UUID,
     admin_read_message_id UUID,
     user_cleared_seq BIGINT NOT NULL DEFAULT 0,
     admin_cleared_seq BIGINT NOT NULL DEFAULT 0,
     created_at TIMESTAMPTZ NOT NULL,
     last_message_at TIMESTAMPTZ
   )`,

  `CREATE TABLE IF NOT EXISTS messages (
     seq BIGSERIAL,
     id UUID PRIMARY KEY,
     chat_id UUID NOT NULL,
     client_id TEXT NOT NULL,
     sender TEXT NOT NULL,
     text TEXT NOT NULL DEFAULT '',
     attachments JSONB NOT NULL DEFAULT '[]'::jsonb,
     reply_to UUID,
     created_at TIMESTAMPTZ NOT NULL,
     edited_at TIMESTAMPTZ,
     deleted BOOLEAN NOT NULL DEFAULT FALSE,
     UNIQUE (chat_id, client_id)
   )`,
  `CREATE INDEX IF NOT EXISTS messages_chat_seq_idx ON messages (chat_id, seq)`,
  `CREATE INDEX IF NOT EXISTS messages_created_idx ON messages (created_at)`,

  // «Удалить у себя»: side = user | admin.
  `CREATE TABLE IF NOT EXISTS message_hidden (
     message_id UUID NOT NULL,
     side TEXT NOT NULL,
     chat_id UUID NOT NULL,
     PRIMARY KEY (message_id, side)
   )`,
  `CREATE INDEX IF NOT EXISTS message_hidden_chat_idx ON message_hidden (chat_id)`,

  // По одной реакции от каждой стороны (who = admin | user) на сообщение.
  `CREATE TABLE IF NOT EXISTS reactions (
     message_id UUID NOT NULL,
     who TEXT NOT NULL,
     emoji TEXT NOT NULL,
     chat_id UUID NOT NULL,
     created_at TIMESTAMPTZ NOT NULL,
     PRIMARY KEY (message_id, who)
   )`,

  `CREATE TABLE IF NOT EXISTS media (
     id UUID PRIMARY KEY,
     uploader_kind TEXT NOT NULL,
     uploader_id UUID NOT NULL,
     kind TEXT NOT NULL,
     name TEXT NOT NULL,
     mime TEXT NOT NULL,
     size BIGINT NOT NULL,
     duration_ms INT,
     width INT,
     height INT,
     created_at TIMESTAMPTZ NOT NULL
   )`,
  // В каких чатах вложение использовано (для проверки доступа при скачивании).
  `CREATE TABLE IF NOT EXISTS media_links (
     media_id UUID NOT NULL,
     chat_id UUID NOT NULL,
     PRIMARY KEY (media_id, chat_id)
   )`,

  `CREATE TABLE IF NOT EXISTS notifications (
     id UUID PRIMARY KEY,
     device_id UUID NOT NULL,
     title TEXT NOT NULL,
     body TEXT NOT NULL,
     kind TEXT NOT NULL,
     chat_id UUID,
     created_at TIMESTAMPTZ NOT NULL,
     read_at TIMESTAMPTZ
   )`,
  `CREATE INDEX IF NOT EXISTS notifications_device_idx ON notifications (device_id, created_at)`,

  // Глобальные счётчики статистики (installs и т.п.).
  `CREATE TABLE IF NOT EXISTS counters (
     name TEXT PRIMARY KEY,
     value BIGINT NOT NULL DEFAULT 0
   )`,

  // ---------- admin2: только добавления (существующие данные сохраняются) ----------

  // Аккаунты администраторов с логином и паролем (scrypt). Старые строки (вход по ключу/Google) остаются без пароля.
  `ALTER TABLE admins ADD COLUMN IF NOT EXISTS login TEXT`,
  `ALTER TABLE admins ADD COLUMN IF NOT EXISTS password_hash TEXT`,
  `CREATE UNIQUE INDEX IF NOT EXISTS admins_login_idx ON admins (login)`,

  // Публичный ID пользователя из 4 цифр ("0000".."9999"; когда заняты все — 5 цифр и т. д.).
  `ALTER TABLE users ADD COLUMN IF NOT EXISTS public_id TEXT`,
  `CREATE UNIQUE INDEX IF NOT EXISTS users_public_id_idx ON users (public_id)`,

  // Блокировка на срок (NULL — навсегда) и персональные ограничения устройства (JSON).
  `ALTER TABLE devices ADD COLUMN IF NOT EXISTS blocked_until TIMESTAMPTZ`,
  `ALTER TABLE devices ADD COLUMN IF NOT EXISTS overrides JSONB`,

  // События установки/обновления/удаления. kind: install | update | uninstall | open.
  `CREATE TABLE IF NOT EXISTS device_events (
     id UUID PRIMARY KEY,
     device_id UUID NOT NULL,
     kind TEXT NOT NULL,
     from_version TEXT,
     to_version TEXT,
     at TIMESTAMPTZ NOT NULL
   )`,
  `CREATE INDEX IF NOT EXISTS device_events_device_idx ON device_events (device_id, at)`,
  `CREATE INDEX IF NOT EXISTS device_events_kind_idx ON device_events (kind)`,

  // Расход ИИ по дням (UTC, "YYYY-MM-DD") и устройствам; запросы админа — под нулевым UUID.
  `CREATE TABLE IF NOT EXISTS usage_daily (
     day TEXT NOT NULL,
     device_id UUID NOT NULL,
     tokens_prompt BIGINT NOT NULL DEFAULT 0,
     tokens_completion BIGINT NOT NULL DEFAULT 0,
     requests BIGINT NOT NULL DEFAULT 0,
     errors BIGINT NOT NULL DEFAULT 0,
     PRIMARY KEY (day, device_id)
   )`,
  `CREATE INDEX IF NOT EXISTS usage_daily_device_idx ON usage_daily (device_id)`,

  // Ошибки и падения, присланные приложениями. kind: error | crash.
  `CREATE TABLE IF NOT EXISTS client_reports (
     id UUID PRIMARY KEY,
     device_id UUID NOT NULL,
     kind TEXT NOT NULL,
     message TEXT NOT NULL,
     stack TEXT,
     app_version TEXT,
     created_at TIMESTAMPTZ NOT NULL
   )`,
  `CREATE INDEX IF NOT EXISTS client_reports_device_idx ON client_reports (device_id, created_at)`,
  `CREATE INDEX IF NOT EXISTS client_reports_created_idx ON client_reports (created_at)`,

  // Журнал действий администраторов (admin_id NULL — действие сервера, например снятие блокировки по сроку).
  `CREATE TABLE IF NOT EXISTS admin_actions (
     id UUID PRIMARY KEY,
     admin_id UUID,
     device_id UUID,
     action TEXT NOT NULL,
     detail TEXT,
     at TIMESTAMPTZ NOT NULL
   )`,
  `CREATE INDEX IF NOT EXISTS admin_actions_device_idx ON admin_actions (device_id, at)`,
  `CREATE INDEX IF NOT EXISTS admin_actions_at_idx ON admin_actions (at)`,

  // Заметки администраторов о пользователе.
  `CREATE TABLE IF NOT EXISTS admin_notes (
     id UUID PRIMARY KEY,
     device_id UUID NOT NULL,
     admin_id UUID,
     text TEXT NOT NULL,
     created_at TIMESTAMPTZ NOT NULL,
     updated_at TIMESTAMPTZ
   )`,
  `CREATE INDEX IF NOT EXISTS admin_notes_device_idx ON admin_notes (device_id, created_at)`,

  // Настройки сервера «ключ → JSON-строка» (например, ai: включён ли ИИ и расписание).
  `CREATE TABLE IF NOT EXISTS server_settings (
     key TEXT PRIMARY KEY,
     value TEXT NOT NULL,
     updated_at TIMESTAMPTZ NOT NULL
   )`,

  // Стабильный идентификатор устройства (Android ID). Переустановка/сброс данных меняет install_id,
  // но hardware_id остаётся — по нему регистрация переиспользует ту же строку (без дублей «пользователей»).
  `ALTER TABLE devices ADD COLUMN IF NOT EXISTS hardware_id TEXT`,
  `CREATE INDEX IF NOT EXISTS devices_hardware_idx ON devices (hardware_id)`,

  // Одноразовая чистка синтетических тестовых регистраций (нагрузочные/проверочные). Их install_id
  // имеют служебные префиксы, которых нет у реальных устройств (там UUID). Идемпотентно (после чистки
  // ничего не совпадает). Можно удалить этот блок позже.
  `DELETE FROM tokens WHERE kind = 'device' AND subject_id IN (
     SELECT id FROM devices WHERE install_id LIKE 'stress-%' OR install_id LIKE 'dedup-%'
       OR install_id LIKE 'cf-%' OR install_id LIKE 'runpod-%')`,
  `DELETE FROM chats WHERE device_id IN (
     SELECT id FROM devices WHERE install_id LIKE 'stress-%' OR install_id LIKE 'dedup-%'
       OR install_id LIKE 'cf-%' OR install_id LIKE 'runpod-%')`,
  `DELETE FROM notifications WHERE device_id IN (
     SELECT id FROM devices WHERE install_id LIKE 'stress-%' OR install_id LIKE 'dedup-%'
       OR install_id LIKE 'cf-%' OR install_id LIKE 'runpod-%')`,
  `DELETE FROM devices WHERE install_id LIKE 'stress-%' OR install_id LIKE 'dedup-%'
     OR install_id LIKE 'cf-%' OR install_id LIKE 'runpod-%'`,
  `DELETE FROM users WHERE id NOT IN (SELECT DISTINCT user_id FROM devices)`,

  // ---------- админ-панель v2: роли, страна, логи входов, просмотры профилей, общий чат админов ----------

  // Роль администратора: 'admin' (красный) | 'developer' (фиолетовый). Старые строки — 'admin'.
  `ALTER TABLE admins ADD COLUMN IF NOT EXISTS role TEXT NOT NULL DEFAULT 'admin'`,
  // Аватар администратора (ссылка на media) — для чата админов и подписи.
  `ALTER TABLE admins ADD COLUMN IF NOT EXISTS avatar_media_id UUID`,

  // Страна устройства (ISO-код, напр. "RU"), определяется по заголовку CF-IPCountry при регистрации/активности.
  `ALTER TABLE devices ADD COLUMN IF NOT EXISTS country TEXT`,
  // Аватар пользователя, назначенный админом (media id). Пользователь увидит его после обновления приложения.
  `ALTER TABLE devices ADD COLUMN IF NOT EXISTS avatar_media_id UUID`,

  // Журнал входов/попыток входа в админ-панель (кто пытался, кто вошёл).
  `CREATE TABLE IF NOT EXISTS admin_logins (
     id UUID PRIMARY KEY,
     admin_id UUID,
     login_tried TEXT,
     success BOOLEAN NOT NULL,
     method TEXT,
     ip TEXT,
     user_agent TEXT,
     at TIMESTAMPTZ NOT NULL
   )`,
  `CREATE INDEX IF NOT EXISTS admin_logins_at_idx ON admin_logins (at)`,
  `CREATE INDEX IF NOT EXISTS admin_logins_admin_idx ON admin_logins (admin_id, at)`,

  // История просмотров карточек пользователей администраторами (кто смотрел, когда).
  `CREATE TABLE IF NOT EXISTS profile_views (
     id UUID PRIMARY KEY,
     admin_id UUID NOT NULL,
     device_id UUID NOT NULL,
     at TIMESTAMPTZ NOT NULL
   )`,
  `CREATE INDEX IF NOT EXISTS profile_views_device_idx ON profile_views (device_id, at)`,
  `CREATE INDEX IF NOT EXISTS profile_views_admin_idx ON profile_views (admin_id, at)`,

  // Общий чат администраторов и разработчиков (канал "staff"). Сообщения с медиа и отметками прочтения.
  `CREATE TABLE IF NOT EXISTS staff_messages (
     seq BIGSERIAL,
     id UUID PRIMARY KEY,
     admin_id UUID NOT NULL,
     text TEXT NOT NULL DEFAULT '',
     attachments JSONB NOT NULL DEFAULT '[]'::jsonb,
     created_at TIMESTAMPTZ NOT NULL,
     deleted BOOLEAN NOT NULL DEFAULT FALSE
   )`,
  `CREATE INDEX IF NOT EXISTS staff_messages_seq_idx ON staff_messages (seq)`,
  // Отметки прочтения общего чата: по каждому админу — до какого seq прочитано.
  `CREATE TABLE IF NOT EXISTS staff_reads (
     admin_id UUID PRIMARY KEY,
     read_seq BIGINT NOT NULL DEFAULT 0,
     at TIMESTAMPTZ NOT NULL
   )`,

  // Первый созданный аккаунт (вход по логину/паролю) делаем разработчиком-владельцем, если роли ещё не назначены.
  `UPDATE admins SET role = 'developer'
     WHERE password_hash IS NOT NULL
       AND id = (SELECT id FROM admins WHERE password_hash IS NOT NULL ORDER BY created_at ASC LIMIT 1)
       AND NOT EXISTS (SELECT 1 FROM admins WHERE role = 'developer')`,

  // Владелец мог войти через Google (без пароля) — тогда миграция выше его не повышает.
  // Если разработчика до сих пор НЕТ, делаем им самый старый аккаунт (владельца). Срабатывает один раз.
  `UPDATE admins SET role = 'developer'
     WHERE id = (SELECT id FROM admins ORDER BY created_at ASC LIMIT 1)
       AND NOT EXISTS (SELECT 1 FROM admins WHERE role = 'developer')`,

  // Часовой пояс устройства (IANA, напр. Europe/Moscow) — приложение шлёт его начиная с новой версии.
  `ALTER TABLE devices ADD COLUMN IF NOT EXISTS timezone TEXT`,

  // Назначение обращения в поддержке: какой админ «взял» чат в работу и когда (план админка п.16).
  // Остальные видят, что чат занят (подсветка/таймер/read-only — на клиенте); снятие — явным действием.
  `ALTER TABLE chats ADD COLUMN IF NOT EXISTS assigned_admin_id UUID`,
  `ALTER TABLE chats ADD COLUMN IF NOT EXISTS assigned_at TIMESTAMPTZ`,

  // Права администратора (план админка п.3): JSON-флаги. По умолчанию {} — у не-разработчика всё закрыто.
  // Разработчик имеет все права всегда (в коде). Новые админы создаются с {} (закрыто).
  `ALTER TABLE admins ADD COLUMN IF NOT EXISTS permissions JSONB NOT NULL DEFAULT '{}'::jsonb`,
  // ОДНОРАЗОВО: существующим не-разработчикам даём базовый набор (ответы+назначение), чтобы не потеряли
  // доступ резко. Защита от повтора — маркер в server_settings (иначе рестарт затёр бы ручную отмену прав).
  `UPDATE admins SET permissions = '{"reply":true,"assign":true}'::jsonb
     WHERE role <> 'developer'
       AND NOT EXISTS (SELECT 1 FROM server_settings WHERE key = 'perms_backfilled')`,
  `INSERT INTO server_settings (key, value, updated_at) VALUES ('perms_backfilled', '1', now())
     ON CONFLICT (key) DO NOTHING`,

  // Диагностика ошибок ИИ (AI-400/5xx): сохраняем причину от DeepSeek, чтобы видеть её в админке,
  // а не только в логах Railway. Кольцо последних записей (старые чистятся при вставке).
  `CREATE TABLE IF NOT EXISTS ai_errors (
     seq BIGSERIAL PRIMARY KEY,
     at TIMESTAMPTZ NOT NULL,
     status INT NOT NULL,
     model TEXT,
     device_id UUID,
     messages INT,
     body TEXT
   )`,
];

export async function migrate(db) {
  for (const sql of STATEMENTS) await db.query(sql);
}
