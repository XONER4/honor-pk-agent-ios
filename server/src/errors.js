// Единый формат ошибок API: HTTP-статус + {"error":"code","message":"текст (ru)"}.

export class ApiError extends Error {
  constructor(status, code, message) {
    super(message || code);
    this.statusCode = status;
    this.code = code;
    this.expose = true;
  }
}

export const badRequest = (msg = 'Некорректный запрос') => new ApiError(400, 'bad_request', msg);
export const unauthorized = (msg = 'Требуется авторизация') => new ApiError(401, 'unauthorized', msg);
export const forbidden = (msg = 'Доступ запрещён') => new ApiError(403, 'forbidden', msg);
export const blocked = (reason) => new ApiError(403, 'blocked', reason || '');
export const notFound = (msg = 'Не найдено') => new ApiError(404, 'not_found', msg);
export const conflict = (msg = 'Конфликт') => new ApiError(409, 'conflict', msg);
export const rateLimited = (msg = 'Слишком много запросов, попробуйте позже') => new ApiError(429, 'rate_limited', msg);

const STATUS_CODES = {
  400: ['bad_request', 'Некорректный запрос'],
  401: ['unauthorized', 'Требуется авторизация'],
  403: ['forbidden', 'Доступ запрещён'],
  404: ['not_found', 'Не найдено'],
  405: ['method_not_allowed', 'Метод не поддерживается'],
  413: ['payload_too_large', 'Слишком большой запрос или файл'],
  415: ['unsupported_media_type', 'Неподдерживаемый тип содержимого'],
  429: ['rate_limited', 'Слишком много запросов, попробуйте позже'],
};

/** Fastify error handler: приводит любые ошибки к формату контракта, не раскрывая внутренности. */
export function errorHandler(err, request, reply) {
  if (err instanceof ApiError) {
    return reply.code(err.statusCode).send({ error: err.code, message: err.message });
  }
  if (err.validation) {
    const detail = err.validation[0];
    const where = [err.validationContext, detail?.instancePath].filter(Boolean).join('');
    return reply.code(400).send({ error: 'bad_request', message: `Некорректные данные: ${where} ${detail?.message || ''}`.trim() });
  }
  const status = err.statusCode && err.statusCode >= 400 && err.statusCode < 500 ? err.statusCode : 500;
  if (status === 500) {
    request.log.error({ err: { message: err.message, stack: err.stack, code: err.code } }, 'unhandled error');
    return reply.code(500).send({ error: 'internal', message: 'Внутренняя ошибка сервера' });
  }
  const [code, message] = STATUS_CODES[status] || ['bad_request', 'Некорректный запрос'];
  return reply.code(status).send({ error: code, message });
}

export function notFoundHandler(request, reply) {
  reply.code(404).send({ error: 'not_found', message: 'Не найдено' });
}
