import type { InterviewDifficulty, InterviewMode, InterviewSessionRequest } from '../../types/api.d';
import type { TargetJob } from '../../types/targetJob';

export const difficultyOptions: Array<{ value: InterviewDifficulty; label: string; description: string }> = [
  { value: 'BEGINNER', label: '入门', description: '基础理解 · 清晰引导' },
  { value: 'STANDARD', label: '标准', description: '岗位实践 · 决策与验证' },
  { value: 'ADVANCED', label: '挑战', description: '复杂约束 · 原理与取舍' },
];

export function buildInterviewRequest(input: {
  resumeId: number; jobSource: 'saved' | 'custom'; job: TargetJob | null;
  customRole: string; customJd: string; mode: InterviewMode; difficulty: InterviewDifficulty; rounds: number;
}): InterviewSessionRequest {
  const common = { resumeId: input.resumeId, mode: input.mode, difficulty: input.difficulty, totalRounds: input.rounds };
  if (input.jobSource === 'saved') {
    if (!input.job) throw new Error('请选择已保存的目标岗位。');
    return { ...common, targetRole: input.job.title, targetJobId: input.job.id, targetJobRevision: input.job.revision };
  }
  const role = input.customRole.trim(), jd = input.customJd.trim();
  if (!role || !jd) throw new Error('请填写岗位名称和自定义 JD。');
  if (role.length > 160 || jd.length > 20000) throw new Error('岗位名称最多 160 字，JD 最多 20,000 字。');
  return { ...common, targetRole: role, jobDescription: jd };
}
