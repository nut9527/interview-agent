import type { DraftOptimizationRequest, DraftOptimizationResponse } from '@/types/api.d';
import { post } from '@/api/request';

export const aiService = {
  optimizeDraft: (request: DraftOptimizationRequest, signal: AbortSignal): Promise<DraftOptimizationResponse> => {
    return post<DraftOptimizationResponse>('/api/ai/resume/optimize-draft', request, { signal, timeout: 120000 });
  },
};
