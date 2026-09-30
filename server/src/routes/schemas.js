// Общие фрагменты JSON-схем для валидации входных данных (Fastify/Ajv).

export const uuid = { type: 'string', format: 'uuid' };
export const nullable = (schema) => ({ ...schema, type: [schema.type, 'null'] });

export const chatParams = {
  type: 'object',
  required: ['chatId'],
  properties: { chatId: uuid },
};

export const messageParams = {
  type: 'object',
  required: ['chatId', 'id'],
  properties: { chatId: uuid, id: uuid },
};

export const scopeQuery = {
  type: 'object',
  properties: { scope: { type: 'string', enum: ['me', 'everyone'], default: 'me' } },
};

export const attachmentRef = {
  type: 'object',
  required: ['id'],
  properties: {
    id: uuid,
    kind: { type: 'string', enum: ['image', 'video', 'audio', 'voice', 'file'] },
    durationMs: { type: ['integer', 'null'], minimum: 0, maximum: 86_400_000 },
    width: { type: ['integer', 'null'], minimum: 0, maximum: 100_000 },
    height: { type: ['integer', 'null'], minimum: 0, maximum: 100_000 },
  },
};

// Поля профиля устройства (register / PATCH me).
export const deviceProfileProps = {
  platform: { type: 'string', enum: ['android', 'ios'] },
  deviceModel: { type: 'string', maxLength: 200 },
  deviceName: { type: 'string', maxLength: 200 },
  osVersion: { type: 'string', maxLength: 50 },
  appVersion: { type: 'string', maxLength: 50 },
  displayName: { type: 'string', maxLength: 100 },
  birthday: { type: ['string', 'null'], pattern: '^\\d{4}-\\d{2}-\\d{2}$' },
  language: { type: 'string', maxLength: 16, pattern: '^[A-Za-z]{2,3}([-_][A-Za-z0-9]{2,8})?$' },
  licenseAcceptedAt: { type: ['string', 'null'], format: 'date-time' },
  pushToken: { type: ['string', 'null'], maxLength: 4096 },
  hardwareId: { type: ['string', 'null'], maxLength: 128 },
};

export const errorResponse = {
  type: 'object',
  properties: { error: { type: 'string' }, message: { type: 'string' } },
};
