import type { PageResult, ResumeVersionSummary } from '@/types/api.d';
import type {
  ResumeProfile,
  ResumeProfileRequest,
  ResumeCompareRequest,
  ResumeComparison,
} from '@/types/api.d';
import { get, post, put, del } from '@/api/request';
import type { Schemas } from '@/types/generated/openapi';

export type ResumeImportResult = Schemas['cn.edu.whu.zhizhida.resume.api.ResumeImportResult'];
export type ResumeImportReview = Pick<ResumeImportResult, 'warnings' | 'recognitionMode'>;

export const resumeImportNotice = '启用 AI 识别时，系统会将提取的简历正文（含其中的联系方式）发送给已配置的模型服务。离线模式使用本地规则识别。';

/** 由后端按当前身份校验简历归属；请求失败以异常交给调用方处理。 */
export const resumeService = {
  /** 获取当前用户全部简历 */
  getList: (signal?: AbortSignal): Promise<ResumeProfile[]> => {
    return get<ResumeProfile[]>('/api/resumes', { signal });
  },

  getOne: (id: number, signal?: AbortSignal): Promise<ResumeProfile> => {
    return get<ResumeProfile>(`/api/resumes/${id}`, { signal });
  },

  create: (data: ResumeProfileRequest, signal?: AbortSignal): Promise<ResumeProfile> => {
    return post<ResumeProfile>('/api/resumes', data, { signal });
  },

  /** 传入编辑基线 revision 检查并发修改；HTTP 409 由编辑器进入冲突审阅。 */
  update: (id: number, data: ResumeProfileRequest, signal?: AbortSignal): Promise<ResumeProfile> => {
    return put<ResumeProfile>(`/api/resumes/${id}`, data, { signal });
  },

  delete: (id: number): Promise<null> => {
    return del<null>(`/api/resumes/${id}`);
  },

  /** 仅解析资料，确认时通过表单校验后调用正常创建接口。 */
  parseDocument: (file: File, signal?: AbortSignal): Promise<ResumeImportResult> => {
    const data = new FormData();
    data.append('file', file);
    return post<ResumeImportResult>('/api/resumes/import/parse', data, { signal, timeout: 120000 });
  },

  getVersionHistory: (resumeId: number, page = 1): Promise<PageResult<ResumeVersionSummary>> => {
    return get<PageResult<ResumeVersionSummary>>(`/api/resumes/${resumeId}/versions`, { params: { page, size: 20 } });
  },

  /** 使用当前 revision 恢复历史快照，返回恢复后的完整内容和新的 revision。 */
  restoreVersion: (resumeId: number, versionId: number, revision?: number): Promise<ResumeProfile> => {
    return post<ResumeProfile>(`/api/resumes/${resumeId}/versions/${versionId}/restore`, { revision });
  },


  /** 对比已保存内容或历史快照；不包含编辑器尚未保存的本地输入。 */
  compareVersions: (req: ResumeCompareRequest): Promise<ResumeComparison> => {
    return post<ResumeComparison>('/api/resumes/compare', req);
  },
};
