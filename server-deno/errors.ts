// Единый формат ошибок API: HTTP-статус + {"error":"code","message":"текст (ru)"}.

export class ApiError extends Error {
  statusCode: number;
  code: string;
  extra: Record<string, unknown> | null;
  constructor(status: number, code: string, message?: string, extra: Record<string, unknown> | null = null) {
    super(message || code);
    this.statusCode = status;
    this.code = code;
    this.extra = extra;
  }
  body() {
    return { error: this.code, message: this.message, ...(this.extra || {}) };
  }
}

export const badRequest = (msg = "Некорректный запрос") => new ApiError(400, "bad_request", msg);
export const unauthorized = (msg = "Требуется авторизация") => new ApiError(401, "unauthorized", msg);
export const forbidden = (msg = "Доступ запрещён") => new ApiError(403, "forbidden", msg);
export const blocked = (reason?: string | null, until: string | number | Date | null = null) =>
  new ApiError(403, "blocked", reason || "", { until: until ? new Date(until).toISOString() : null });
export const notFound = (msg = "Не найдено") => new ApiError(404, "not_found", msg);
export const conflict = (msg = "Конфликт") => new ApiError(409, "conflict", msg);
export const rateLimited = (msg = "Слишком много запросов, попробуйте позже") => new ApiError(429, "rate_limited", msg);
