import type { PageResult, ResumeVersionSummary } from '@/types/api.d';
import type {
  ResumeProfile,
  ResumeProfileRequest,
  ResumeVersionView,
  ResumeCompareRequest,
  ResumeComparison,
} from '@/types/api.d';
import { get, post, put, del } from '@/api/request';
import { onAccountReset } from '../accountScope';
import { ApiError } from '@/api/ApiError';
import { USE_MOCK } from '@/config/runtime';

const mockResumes: ResumeProfile[] = [
  {
    id: 1,
    ownerName: '张三',
    targetRole: '前端开发工程师',
    summary: '3年前端开发经验，熟练掌握React、Vue等主流框架，有大型项目开发经验。',
    skills: ['React', 'Vue', 'TypeScript', 'Node.js'],
    educationExperiences: ['2018-2022 本科 北京大学 计算机科学与技术'],
    projectExperiences: ['电商平台前端重构项目 - 技术负责人', '企业后台管理系统 - 核心开发者'],
    internshipExperiences: ['2021年暑期 阿里巴巴 前端实习生'],
    certificates: ['CET-6', '计算机二级'],
    templateKey: 'classic' as const,
  },
  {
    id: 2,
    ownerName: '李四',
    targetRole: 'Java开发工程师',
    summary: '5年Java后端开发经验，精通Spring Boot框架，熟悉微服务架构。',
    skills: ['Java', 'Spring Boot', 'MySQL', 'Redis'],
    educationExperiences: ['2016-2020 本科 清华大学 软件工程'],
    projectExperiences: ['分布式订单系统 - 架构师', '用户中心服务 - 开发负责人'],
    internshipExperiences: [],
    certificates: ['PMP', 'Oracle认证'],
    templateKey: 'modern' as const,
  },
];


const mockVersions: ResumeVersionView[] = [
  {
    id: 1,
    resumeId: 1,
    versionNumber: 1,
    changeType: 'CREATED' as const,
    changeNote: '手动创建',
    createdAt: '2026-07-10T10:00:00Z',
    snapshot: mockResumes[0],
  },
  {
    id: 2,
    resumeId: 1,
    versionNumber: 2,
    changeType: 'UPDATED' as const,
    changeNote: '更新了技能列表',
    createdAt: '2026-07-12T14:30:00Z',
    snapshot: mockResumes[0],
  },
];

const mockSeed = structuredClone({ resumes: mockResumes, versions: mockVersions });
onAccountReset(() => {
  if (!USE_MOCK) return;
  const seed = structuredClone(mockSeed);
  mockResumes.splice(0, mockResumes.length, ...seed.resumes);
  mockVersions.splice(0, mockVersions.length, ...seed.versions);
});

export const resumeService = {
  /** 获取当前用户全部简历 */
  getList: (signal?: AbortSignal): Promise<ResumeProfile[]> => {
    if (USE_MOCK) {
      return Promise.resolve(mockResumes);
    }
    return get<ResumeProfile[]>('/api/resumes', { signal });
  },

  /** 获取单份简历详情 */
  getOne: (id: number, signal?: AbortSignal): Promise<ResumeProfile> => {
    if (USE_MOCK) {
      const resume = mockResumes.find((r) => r.id === id);
      if (resume) {
        return Promise.resolve(resume);
      }
      return Promise.reject(new Error('简历不存在'));
    }
    return get<ResumeProfile>(`/api/resumes/${id}`, { signal });
  },

  /** 手动创建简历 */
  create: (data: ResumeProfileRequest, signal?: AbortSignal): Promise<ResumeProfile> => {
    if (USE_MOCK) {
      const newResume: ResumeProfile = {
        ...data,
        id: Date.now(),
        summary: data.summary ?? null,
        skills: data.skills ?? [],
        educationExperiences: data.educationExperiences ?? [],
        projectExperiences: data.projectExperiences ?? [],
        internshipExperiences: data.internshipExperiences ?? [],
        certificates: data.certificates ?? [],
        details: data.details,
        templateKey: (data.templateKey as ResumeProfile['templateKey']) ?? 'classic',
      };
      mockResumes.push(newResume);
      return Promise.resolve(newResume);
    }
    return post<ResumeProfile>('/api/resumes', data, { signal });
  },

  /** 更新简历 */
  update: (id: number, data: ResumeProfileRequest, signal?: AbortSignal): Promise<ResumeProfile> => {
    if (USE_MOCK) {
      const index = mockResumes.findIndex((r) => r.id === id);
      if (index === -1) return Promise.reject(new Error('简历不存在'));
      const currentRevision = mockResumes[index].revision ?? 0;
      if (data.revision !== undefined && data.revision !== currentRevision) {
        return Promise.reject(new ApiError('简历已被修改，请比较并选择要保留的内容。', { status: 409 }));
      }
      const updated: ResumeProfile = {
        ...data,
        id,
        revision: currentRevision + 1,
        summary: data.summary ?? null,
        skills: data.skills ?? [],
        educationExperiences: data.educationExperiences ?? [],
        projectExperiences: data.projectExperiences ?? [],
        internshipExperiences: data.internshipExperiences ?? [],
        certificates: data.certificates ?? [],
        details: data.details,
        templateKey: (data.templateKey as ResumeProfile['templateKey']) ?? 'classic',
      };
      if (index !== -1) {
        mockResumes[index] = updated;
      }
      return Promise.resolve(updated);
    }
    return put<ResumeProfile>(`/api/resumes/${id}`, data, { signal });
  },

  /** 删除简历 */
  delete: (id: number): Promise<null> => {
    if (USE_MOCK) {
      const index = mockResumes.findIndex((r) => r.id === id);
      if (index !== -1) {
        mockResumes.splice(index, 1);
      }
      return Promise.resolve(null);
    }
    return del<null>(`/api/resumes/${id}`);
  },

  /** Parse only. Confirmation uses the normal create endpoint after form validation. */
  parseDocument: (file: File, signal?: AbortSignal): Promise<ResumeProfileRequest> => {
    if (USE_MOCK) return Promise.resolve({ ownerName: '导入用户', targetRole: '', summary: '从文件解析的内容，请检查纠正' });
    const data = new FormData();
    data.append('file', file);
    return post<ResumeProfileRequest>('/api/resumes/import/parse', data, { signal });
  },

  // ─── 文件导入 ───

  /**
   * 导入简历文件（multipart/form-data）
   * @param file 简历文件（.pdf / .doc / .docx，最大 5MB）
   * @param targetRole 文档未识别出岗位时作为回退值
   */
  importResume: (file: File, targetRole?: string): Promise<ResumeProfile> => {
    if (USE_MOCK) {
      const newResume: ResumeProfile = {
        id: Date.now(),
        ownerName: '导入用户',
        targetRole: targetRole ?? '未识别岗位',
        summary: '从文件导入的简历内容',
        skills: [],
        educationExperiences: [],
        projectExperiences: [],
        internshipExperiences: [],
        certificates: [],
        templateKey: 'classic',
      };
      mockResumes.push(newResume);
      return Promise.resolve(newResume);
    }
    const formData = new FormData();
    formData.append('file', file);
    if (targetRole) {
      formData.append('targetRole', targetRole);
    }
    return post<ResumeProfile>('/api/resumes/import', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
  },

  // ─── 版本管理 ───

  /** 获取简历版本历史 */
  getVersionHistory: (resumeId: number, page = 1): Promise<PageResult<ResumeVersionSummary>> => {
    if (USE_MOCK) {
      const rows = mockVersions.filter((v) => v.resumeId === resumeId).sort((a,b) => b.versionNumber - a.versionNumber);
      return Promise.resolve({ items: rows.slice((page-1)*20,page*20), total: rows.length, page, size: 20 });
    }
    return get<PageResult<ResumeVersionSummary>>(`/api/resumes/${resumeId}/versions`, { params: { page, size: 20 } });
  },

  /** 恢复简历到指定历史版本 */
  restoreVersion: (resumeId: number, versionId: number, revision?: number): Promise<ResumeProfile> => {
    if (USE_MOCK) {
      const version = mockVersions.find(
        (v) => v.resumeId === resumeId && v.id === versionId,
      );
      if (!version) {
        return Promise.reject(new Error('版本不存在'));
      }
      // 用版本快照覆盖当前简历
      const index = mockResumes.findIndex((r) => r.id === resumeId);
      if (index === -1) return Promise.reject(new Error('简历不存在'));
      const currentRevision = mockResumes[index].revision ?? 0;
      if (revision !== undefined && revision !== currentRevision) {
        return Promise.reject(new ApiError('简历已被修改，请比较并选择要保留的内容。', { status: 409 }));
      }
      const restored = { ...version.snapshot, id: resumeId, revision: currentRevision + 1 };
      mockResumes[index] = restored;
      return Promise.resolve(restored);
    }
    return post<ResumeProfile>(`/api/resumes/${resumeId}/versions/${versionId}/restore`, { revision });
  },

  // ─── 版本对比 ───

  /** 对比两份简历（当前保存内容或历史版本） */
  compareVersions: (req: ResumeCompareRequest): Promise<ResumeComparison> => {
    if (USE_MOCK) {
      const leftResume = mockResumes.find((r) => r.id === req.left.resumeId);
      const rightResume = mockResumes.find((r) => r.id === req.right.resumeId);
      if (!leftResume || !rightResume) {
        return Promise.reject(new Error('简历不存在'));
      }
      const mockComparison: ResumeComparison = {
        left: {
          id: req.left.versionId ?? null,
          resumeId: req.left.resumeId,
          versionNumber: req.left.versionId ? 1 : 0,
          changeType: req.left.versionId ? 'UPDATED' : 'CURRENT',
          changeNote: req.left.versionId ? '历史版本' : '当前版本',
          createdAt: req.left.versionId ? '2026-07-12T14:30:00Z' : null,
          snapshot: leftResume,
        },
        right: {
          id: req.right.versionId ?? null,
          resumeId: req.right.resumeId,
          versionNumber: req.right.versionId ? 1 : 0,
          changeType: req.right.versionId ? 'UPDATED' : 'CURRENT',
          changeNote: req.right.versionId ? '历史版本' : '当前版本',
          createdAt: req.right.versionId ? '2026-07-12T14:30:00Z' : null,
          snapshot: rightResume,
        },
        changedFieldCount: 1,
        fields: [],
      };
      return Promise.resolve(mockComparison);
    }
    return post<ResumeComparison>('/api/resumes/compare', req);
  },
};
