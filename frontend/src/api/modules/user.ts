import type {
  ChangePasswordRequest,
  DeleteAccountRequest,
} from '@/types/api.d';
import { del, put } from '@/api/request';

export const userService = {
  changePassword: (data: ChangePasswordRequest): Promise<null> => {
    return put<null>('/api/users/me/password', data);
  },
  deleteAccount: (data: DeleteAccountRequest): Promise<null> => {
    return del<null>('/api/users/me', { data });
  },
};
