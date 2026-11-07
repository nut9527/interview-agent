import type { InterviewDifficulty, InterviewMode, InterviewSessionRequest, InterviewTrainingTopic } from '../../types/api.d';
import type { TargetJob } from '../../types/targetJob';

export const difficultyOptions: Array<{ value: InterviewDifficulty; label: string; description: string }> = [
  { value: 'BEGINNER', label: '入门', description: '基础理解 · 清晰引导' },
  { value: 'STANDARD', label: '标准', description: '岗位实践 · 决策与验证' },
  { value: 'ADVANCED', label: '挑战', description: '复杂约束 · 原理与取舍' },
];

export const trainingTopics: Array<{ value: InterviewTrainingTopic; label: string; description: string }> = [
  { value: 'DATABASE', label: '数据库', description: '索引、事务与一致性' },
  { value: 'CONCURRENCY', label: '并发', description: '线程安全、幂等与边界' },
  { value: 'PERFORMANCE', label: '性能', description: '测量、瓶颈与优化验证' },
  { value: 'PROJECT', label: '项目深挖', description: '个人职责与方案取舍' },
];

export function buildInterviewRequest(input: {
  resumeId?: number; jobSource: 'saved' | 'custom'; job: TargetJob | null; trainingTopic?: InterviewTrainingTopic;
  customRole: string; customJd: string; mode: InterviewMode; difficulty: InterviewDifficulty; rounds: number;
}): InterviewSessionRequest {
  const common = { resumeId: input.resumeId, mode: input.mode, difficulty: input.difficulty, totalRounds: input.rounds };
  if (input.trainingTopic) {
    if (!Number.isInteger(input.rounds) || input.rounds < 3 || input.rounds > 6) throw new Error('专项训练仅支持 3 至 6 轮。');
    const topic = trainingTopics.find(item => item.value === input.trainingTopic);
    if (!topic) throw new Error('请选择训练主题。');
    const training = { ...common, trainingTopic: input.trainingTopic,
      mode: input.trainingTopic === 'PROJECT' ? 'PROJECT_DEEP_DIVE' as const : 'BACKEND_BASICS' as const };
    if (input.jobSource === 'saved' && input.job) return { ...training, targetRole: input.job.title,
      targetJobId: input.job.id, targetJobRevision: input.job.revision };
    if (input.jobSource === 'custom' && (input.customRole.trim() || input.customJd.trim())) {
      if (!input.customRole.trim() || !input.customJd.trim()) throw new Error('请补全岗位名称和自定义 JD，或清空后直接练习主题。');
      if (input.customRole.trim().length > 160 || input.customJd.trim().length > 20000) throw new Error('岗位名称最多 160 字，JD 最多 20,000 字。');
      return { ...training, targetRole: input.customRole.trim(), jobDescription: input.customJd.trim() };
    }
    return { ...training, targetRole: `${topic.label}专项训练` };
  }
  if (input.jobSource === 'saved') {
    if (!input.job) throw new Error('请选择已保存的目标岗位。');
    return { ...common, targetRole: input.job.title, targetJobId: input.job.id, targetJobRevision: input.job.revision };
  }
  const role = input.customRole.trim(), jd = input.customJd.trim();
  if (!role || !jd) throw new Error('请填写岗位名称和自定义 JD。');
  if (role.length > 160 || jd.length > 20000) throw new Error('岗位名称最多 160 字，JD 最多 20,000 字。');
  return { ...common, targetRole: role, jobDescription: jd };
}
