import axios, { type AxiosRequestConfig, type InternalAxiosRequestConfig } from 'axios';
import { captureAccountScope } from './accountScope.ts';
import { isRequestCanceled, responseError, toApiError } from './ApiError.ts';

export const createRequestClient = (options: {
  baseURL: string;
  getToken: () => string | null;
  onUnauthorized: () => void;
  adapter?: AxiosRequestConfig['adapter'];
}) => {
  const client = axios.create({ baseURL: options.baseURL, timeout: 30000, adapter: options.adapter });
  const requests = new WeakMap<InternalAxiosRequestConfig, {
    current: () => boolean; release: () => void; token: string | null;
  }>();

  client.interceptors.request.use((config) => {
    const scope = captureAccountScope();
    const token = options.getToken();
    const localSignal = config.signal;
    const controller = new AbortController();
    const abort = () => controller.abort();
    scope.signal.addEventListener('abort', abort, { once: true });
    localSignal?.addEventListener?.('abort', abort, { once: true });
    if (scope.signal.aborted || localSignal?.aborted) abort();
    config.signal = controller.signal;
    if (token) config.headers.Authorization = `Bearer ${token}`;
    requests.set(config, {
      token,
      current: () => scope.isCurrent() && token === options.getToken() && !controller.signal.aborted,
      release: () => {
        scope.signal.removeEventListener('abort', abort);
        localSignal?.removeEventListener?.('abort', abort);
      },
    });
    return config;
  }, undefined, { synchronous: true });

  const finish = (config?: InternalAxiosRequestConfig) => {
    const request = config && requests.get(config);
    request?.release();
    if (config) requests.delete(config);
    if (request && !request.current()) throw new axios.CanceledError('登录状态或页面已变化');
    return request;
  };

  client.interceptors.response.use((response) => {
    finish(response.config);
    if (response.data?.success !== true) throw responseError(response);
    return response;
  }, (error: unknown) => {
    const request = finish(axios.isAxiosError(error) ? error.config : undefined);
    if (isRequestCanceled(error)) return Promise.reject(error);
    const failure = toApiError(error);
    // Login failures and responses from a previous token must not sign out a new account.
    if (failure.status === 401 && request?.token && options.getToken() === request.token) {
      options.onUnauthorized();
    }
    return Promise.reject(failure);
  });
  return client;
};
