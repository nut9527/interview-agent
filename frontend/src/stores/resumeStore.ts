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
  // ─── 现有状态 ───
  resumes: ResumeProfile[];
  loading: boolean;

  // ─── 新增状态 ───
  /** 当前查看的简历版本历史 */
  versions: ResumeVersionSummary[];
  versionTotal: number;
  /** 版本对比结果 */
  compareResult: ResumeComparison | null;
  /** 正在导入文件 */
  importing: boolean;

  // ─── 现有 Actions ───
  fetchResumes: () => Promise<void>;
  fetchResumeById: (id: number) => Promise<ResumeProfile>;
  createResume: (data: ResumeProfile) => Promise<ResumeProfile>;
  updateResume: (id: number, data: ResumeProfile) => Promise<ResumeProfile>;
  deleteResume: (id: number) => Promise<void>;

  // ─── 新增 Actions ───
  /** 获取指定简历的版本历史 */
  fetchVersions: (resumeId: number, page?: number) => Promise<void>;
  /** 恢复简历到指定历史版本 */
  restoreVersion: (resumeId: number, versionId: number, revision?: number) => Promise<ResumeProfile>;
  /** 对比两个版本 */
  compareVersions: (req: ResumeCompareRequest) => Promise<void>;
  /** 导入简历文件 */
  importResume: (file: File, targetRole?: string) => Promise<ResumeProfile>;
}

let versionRequest = 0;

export const useResumeStore = create<ResumeState>((set, get) => ({
  // ─── 初始状态 ───
  resumes: [],
  loading: false,
  versions: [],
  versionTotal: 0,
  compareResult: null,
  importing: false,

  // ─── 现有 Actions 实现 ───
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

  fetchResumeById: async (id: number) => {
    const scope = captureAccountScope();
    const resume = await resumeService.getOne(id);
    if (!scope.isCurrent()) throw new CanceledError();
    return resume;
  },

  createResume: async (data: ResumeProfile) => {
    const scope = captureAccountScope();
    const resume = await resumeService.create(data);
    if (!scope.isCurrent()) throw new CanceledError();
    const resumes = [...get().resumes, resume];
    set({ resumes });
    return resume;
  },

  updateResume: async (id: number, data: ResumeProfile) => {
    const scope = captureAccountScope();
    const resume = await resumeService.update(id, data);
    if (!scope.isCurrent()) throw new CanceledError();
    const resumes = get().resumes.map((r) => (r.id === id ? resume : r));
    set({ resumes });
    return resume;
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

  // ─── 新增 Actions 实现 ───
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

  importResume: async (file: File, targetRole?: string) => {
    const scope = captureAccountScope();
    set({ importing: true });
    try {
      const resume = await resumeService.importResume(file, targetRole);
      if (!scope.isCurrent()) throw new CanceledError();
      set({ resumes: [...get().resumes, resume], importing: false });
      return resume;
    } catch (error) {
      if (!scope.isCurrent()) throw new CanceledError();
      set({ importing: false });
      throw error;
    }
  },
}));

onAccountReset(() => {
  versionRequest += 1;
  useResumeStore.setState({ resumes: [], versions: [], versionTotal: 0,
    compareResult: null, loading: false, importing: false });
});
