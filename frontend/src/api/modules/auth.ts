import type { LoginRequest, AuthResponse, RegisterRequest } from '@/types/api.d';
import { post } from '@/api/request';

export const authService = {
  login: (data: LoginRequest): Promise<AuthResponse> => {
    return post<AuthResponse>('/api/auth/login', data);
  },
  demoLogin: (): Promise<AuthResponse> => {
    return post<AuthResponse>('/api/auth/demo');
  },
  register: (data: RegisterRequest): Promise<AuthResponse> => {
    return post<AuthResponse>('/api/auth/register', data);
  },
  logout: (): Promise<null> => {
    return post<null>('/api/auth/logout');
  },
};
