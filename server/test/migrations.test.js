import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createDb } from '../src/db.js';
import { migrate } from '../src/migrations.js';
import { loadConfig } from '../src/config.js';

test('migrations are idempotent', async () => {
  const db = await createDb({ dbMode: 'memory' });
  await migrate(db);
  await migrate(db);
  const r = await db.one('SELECT count(*)::int AS c FROM devices');
  assert.equal(r.c, 0);
  await db.close();
});

test('config requires DATABASE_URL unless DB_MODE=memory', () => {
  assert.throws(() => loadConfig({}), /DATABASE_URL/);
  const c = loadConfig({ DB_MODE: 'memory', ADMIN_EMAILS: 'A@b.c, d@E.f', GOOGLE_CLIENT_IDS: 'x,y' });
  assert.deepEqual(c.adminEmails, ['a@b.c', 'd@e.f']);
  assert.deepEqual(c.googleClientIds, ['x', 'y']);
  assert.equal(c.presenceOfflineMs, 40_000);
  assert.equal(c.aiQuestionDelayMs, 20_000);
  assert.equal(c.deepseekBaseUrl, 'https://api.deepseek.com');
  assert.deepEqual(c.aiAllowedModels, ['deepseek-flash', 'deepseek-chat', 'deepseek-reasoner']);
  assert.equal(c.maxUploadBytes, 100 * 1024 * 1024);
});
