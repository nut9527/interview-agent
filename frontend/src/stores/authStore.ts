import { create } from 'zustand';
import type { AuthResponse, UserProfile } from '@/types/api.d';
import { resetAccountScope } from '../api/accountScope.ts';

const readIdentity = () => {
  try {
    const token = localStorage.getItem('token');
    const raw = localStorage.getItem('user');
    const user = raw ? JSON.parse(raw) as UserProfile : null;
    return token && user && Number.isFinite(user.id) ? { token, user } : { token: null, user: null };
  } catch { return { token: null, user: null }; }
};

export interface AuthState {
  user: UserProfile | null;
  token: string | null;
  generation: number;
  setSession: (response: AuthResponse) => void;
  setUser: (user: UserProfile) => void;
  loadUserFromStorage: () => void;
  clearUser: () => void;
}

export const useAuthStore = create<AuthState>((set, get) => {
  const apply = (identity: { token: string | null; user: UserProfile | null }, newLogin = false) => {
    const previous = get();
    const changed = newLogin || previous.token !== identity.token || previous.user?.id !== identity.user?.id;
    const generation = changed ? resetAccountScope() : previous.generation;
    set({ ...identity, generation });
  };
  return {
    ...readIdentity(),
    generation: 0,
    setSession: ({ token, user }) => {
      localStorage.setItem('token', token);
      localStorage.setItem('user', JSON.stringify(user));
      apply({ token, user }, true);
    },
    setUser: (user) => {
      localStorage.setItem('user', JSON.stringify(user));
      apply({ token: get().token, user });
    },
    loadUserFromStorage: () => apply(readIdentity()),
    clearUser: () => {
      localStorage.removeItem('token');
      localStorage.removeItem('user');
      apply({ token: null, user: null });
    },
  };
});
