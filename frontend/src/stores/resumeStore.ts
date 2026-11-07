import { CanceledError } from 'axios';
import { captureAccountScope, onAccountReset } from '../api/accountScope.ts';
import { create } from 'zustand';
import { useAuthStore } from './authStore';
import { removeDraft } from '@/pages/Resumes/Editor/resumeDraft';
import type {
  ResumeProfile,
  ResumeVersionSummary,
  ResumeCompareRequest,
  ResumeComparison,
} from '@/types/api.d';
import { resumeService } from '@/api/modules/resume';

export interface ResumeState {
  resumes: ResumeProfile[];
  loading: boolean;

  versions: ResumeVersionSummary[];
  versionTotal: number;
  compareResult: ResumeComparison | null;
  fetchResumes: () => Promise<void>;
  deleteResume: (id: number) => Promise<void>;

  /** 共享版本列表只接纳最近一次请求，快速切换简历或页码不会被旧响应覆盖。 */
  fetchVersions: (resumeId: number, page?: number) => Promise<void>;
  /** 返回恢复后的完整简历；传入 revision 时以它检查并发修改，冲突继续交由调用方处理。 */
  restoreVersion: (resumeId: number, versionId: number, revision?: number) => Promise<ResumeProfile>;
  compareVersions: (req: ResumeCompareRequest) => Promise<void>;
}

let versionRequest = 0;

export const useResumeStore = create<ResumeState>((set, get) => ({
  resumes: [],
  loading: false,
  versions: [],
  versionTotal: 0,
  compareResult: null,

  fetchResumes: async () => {
    const scope = captureAccountScope();
    set({ loading: true });
    try {
      const resumes = await resumeService.getList();
      if (!scope.isCurrent()) throw new CanceledError();
      set({ resumes, loading: false });
    } catch (error) {
      if (!scope.isCurrent()) return;
      set({ loading: false });
      throw error;
    }
  },

  deleteResume: async (id: number) => {
    const scope = captureAccountScope();
    const userId = useAuthStore.getState().user?.id;
    await resumeService.delete(id);
    if (!scope.isCurrent()) throw new CanceledError();
    if (userId) removeDraft(userId, id);
    const resumes = get().resumes.filter((r) => r.id !== id);
    set({ resumes });
  },

  fetchVersions: async (resumeId: number, page = 1) => {
    const scope = captureAccountScope();
    const request = ++versionRequest;
    const versions = await resumeService.getVersionHistory(resumeId, page);
    if (!scope.isCurrent()) throw new CanceledError();
    if (request !== versionRequest) return;
    set({ versions: versions.items, versionTotal: versions.total });
  },

  restoreVersion: async (resumeId: number, versionId: number, revision?: number) => {
    const scope = captureAccountScope();
    const resume = await resumeService.restoreVersion(resumeId, versionId, revision);
    if (!scope.isCurrent()) throw new CanceledError();
    const resumes = get().resumes.map((r) => (r.id === resumeId ? resume : r));
    set({ resumes });
    return resume;
  },

  compareVersions: async (req: ResumeCompareRequest) => {
    const scope = captureAccountScope();
    const compareResult = await resumeService.compareVersions(req);
    if (!scope.isCurrent()) throw new CanceledError();
    set({ compareResult });
  },
}));

onAccountReset(() => {
  versionRequest += 1;
  useResumeStore.setState({ resumes: [], versions: [], versionTotal: 0,
    compareResult: null, loading: false });
});
