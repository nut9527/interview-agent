import axios, { AxiosHeaders, type AxiosResponse } from 'axios';

export const parseRetryAfter = (value: string | undefined, now = Date.now()): number | undefined => {
  if (!value?.trim()) return undefined;
  if (/^\d+$/.test(value.trim())) {
    const delay = Number(value) * 1000;
    return Number.isFinite(delay) ? delay : undefined;
  }
  // Do not interpret malformed numbers as calendar dates.
  if (!/[A-Za-z]/.test(value)) return undefined;
  const date = Date.parse(value);
  return Number.isFinite(date) ? Math.max(0, date - now) : undefined;
};

export class ApiError extends Error {
  readonly status?: number;
  readonly code?: string | number;
  readonly requestId?: string;
  readonly retryAfter?: string;
  readonly retryAfterMs?: number;
  readonly transportCode?: string;
  readonly cause: unknown;

  constructor(message: string, details: {
    status?: number; code?: string | number; requestId?: string; retryAfter?: string;
    transportCode?: string; cause?: unknown;
  } = {}) {
    super(message);
    this.name = 'ApiError';
    this.status = details.status;
    this.code = details.code;
    this.requestId = details.requestId;
    this.retryAfter = details.retryAfter;
    this.retryAfterMs = parseRetryAfter(details.retryAfter);
    this.transportCode = details.transportCode;
    this.cause = details.cause;
  }

  get transient() {
    return this.status === 408 || this.status === 429
      || (this.status !== undefined && this.status >= 500 && this.status !== 501)
      || ['ERR_NETWORK', 'ECONNABORTED', 'ETIMEDOUT'].includes(this.transportCode ?? '');
  }
}

const textHeader = (response: AxiosResponse, name: string) => {
  const value: unknown = response.headers instanceof AxiosHeaders ? response.headers.get(name)
    : Object.entries(response.headers).find(([key]) => key.toLowerCase() === name)?.[1];
  return typeof value === 'string' || typeof value === 'number' ? String(value) : undefined;
};

export const responseError = (response: AxiosResponse, cause?: unknown) => {
  const body: unknown = response.data;
  const data = body && typeof body === 'object' ? body as Record<string, unknown> : {};
  const code = data.code ?? data.errorCode;
  return new ApiError(typeof data.message === 'string' ? data.message : `请求失败（HTTP ${response.status}）`, {
    status: response.status,
    code: typeof code === 'string' || typeof code === 'number' ? code : undefined,
    requestId: textHeader(response, 'x-request-id'),
    retryAfter: textHeader(response, 'retry-after'),
    cause,
  });
};

export const toApiError = (error: unknown): ApiError => {
  if (error instanceof ApiError) return error;
  if (axios.isAxiosError(error)) {
    if (error.response) return responseError(error.response, error);
    return new ApiError(error.message, { transportCode: error.code, cause: error });
  }
  return new ApiError(error instanceof Error ? error.message : '请求失败', { cause: error });
};

export const isRequestCanceled = (error: unknown) => axios.isCancel(error)
  || (error instanceof Error && error.name === 'AbortError');

export const describeApiError = (error: unknown) => {
  const failure = toApiError(error);
  return `${failure.message}${failure.requestId ? `（请求编号：${failure.requestId}）` : ''}`;
};
