import { CanceledError } from 'axios';
import { captureAccountScope, onAccountReset } from '../api/accountScope.ts';
import { create } from 'zustand';
import type { InterviewHistoryItem } from '@/types/api.d';
import { interviewService } from '@/api/modules/interview';

export interface InterviewState {
  history: InterviewHistoryItem[];
  total: number;
  loading: boolean;

  fetchHistory: () => Promise<void>;
}

export const useInterviewStore = create<InterviewState>((set) => ({
  history: [],
  total: 0,
  loading: false,

  fetchHistory: async () => {
    const scope = captureAccountScope();
    set({ loading: true });
    try {
      const history = await interviewService.getSessions();
      if (!scope.isCurrent()) throw new CanceledError();
      set({ history: history.items, total: history.total, loading: false });
    } catch (error) {
      if (!scope.isCurrent()) return;
      set({ loading: false });
    }
  },
}));

onAccountReset(() => useInterviewStore.setState({ history: [], total: 0, loading: false }));
