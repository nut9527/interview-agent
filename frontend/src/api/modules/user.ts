import type {
  ChangePasswordRequest,
  DeleteAccountRequest,
  UserProfile,
  UpdateUserRequest,
} from '@/types/api.d';
import { del, get, put } from '@/api/request';
import { USE_MOCK } from '@/config/runtime';

const STORAGE_KEY = 'user';
const MOCK_PASSWORD_KEY = 'mock-user-password';

const DEFAULT_USER: UserProfile = {
  id: 1,
  username: 'demo',
  displayName: '演示用户',
  targetRole: '前端开发实习生',
  skillTags: ['React', 'TypeScript', 'Vue'],
  demo: true,
};

function getStoredUser(): UserProfile {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    if (stored) {
      const parsed = JSON.parse(stored) as UserProfile;
      return parsed;
    }
  } catch {
    // JSON 解析失败时回退到默认值
  }
  localStorage.setItem(STORAGE_KEY, JSON.stringify(DEFAULT_USER));
  return DEFAULT_USER;
}

export const userService = {
  getProfile: (): Promise<UserProfile> => {
    if (USE_MOCK) {
      return Promise.resolve(getStoredUser());
    }
    return get<UserProfile>('/api/users/me');
  },
  updateProfile: (data: UpdateUserRequest): Promise<UserProfile> => {
    if (USE_MOCK) {
      const current = getStoredUser();
      const updated: UserProfile = {
        ...current,
        displayName: data.displayName,
        targetRole: data.targetRole,
        skillTags: data.skillTags ?? [],
      };
      localStorage.setItem(STORAGE_KEY, JSON.stringify(updated));
      return Promise.resolve(updated);
    }
    return put<UserProfile>('/api/users/me', data);
  },
  changePassword: (data: ChangePasswordRequest): Promise<null> => {
    if (USE_MOCK) {
      const password = localStorage.getItem(MOCK_PASSWORD_KEY) || 'demo123';
      if (data.currentPassword !== password) {
        return Promise.reject(new Error('当前密码不正确'));
      }
      localStorage.setItem(MOCK_PASSWORD_KEY, data.newPassword);
      return Promise.resolve(null);
    }
    return put<null>('/api/users/me/password', data);
  },
  deleteAccount: (data: DeleteAccountRequest): Promise<null> => {
    if (USE_MOCK) {
      const password = localStorage.getItem(MOCK_PASSWORD_KEY) || 'demo123';
      if (data.currentPassword !== password) {
        return Promise.reject(new Error('当前密码不正确'));
      }
      localStorage.removeItem(STORAGE_KEY);
      localStorage.removeItem(MOCK_PASSWORD_KEY);
      return Promise.resolve(null);
    }
    return del<null>('/api/users/me', { data });
  },
};
