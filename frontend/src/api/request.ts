import type { AxiosRequestConfig } from 'axios';
import type { ApiResponse } from '@/types/api';
import { useAuthStore } from '@/stores/authStore';
import { createRequestClient } from './createRequestClient';

// 默认使用同源相对路径：开发环境由 Vite 代理，生产环境由 Nginx 代理。
// 仅在前后端确实跨域部署时才设置 VITE_API_BASE_URL。
const baseURL = import.meta.env.VITE_API_BASE_URL || '';

const request = createRequestClient({
  baseURL,
  getToken: () => useAuthStore.getState().token,
  onUnauthorized: () => useAuthStore.getState().clearUser(),
});

export const get = async <T>(
  url: string,
  config?: AxiosRequestConfig
): Promise<T> => {
  const response = await request.get<ApiResponse<T>>(url, config);
  return response.data.data as T;
};

export const post = async <T>(
  url: string,
  data?: unknown,
  config?: AxiosRequestConfig
): Promise<T> => {
  const response = await request.post<ApiResponse<T>>(url, data, config);
  return response.data.data as T;
};

export const put = async <T>(
  url: string,
  data?: unknown,
  config?: AxiosRequestConfig
): Promise<T> => {
  const response = await request.put<ApiResponse<T>>(url, data, config);
  return response.data.data as T;
};

export const del = async <T>(
  url: string,
  config?: AxiosRequestConfig
): Promise<T> => {
  const response = await request.delete<ApiResponse<T>>(url, config);
  return response.data.data as T;
};

export default request;
