import type { UserProfile } from '@/types/api.d';

export const SUGGESTED_SKILLS = [
  'Java',
  'Spring Boot',
  'MySQL',
  'Redis',
  'Docker',
  'Git',
  'React',
  'TypeScript',
  'Python',
  'LLM',
  '数据分析',
  '项目管理',
];

export interface CompletionStep {
  key: 'displayName' | 'targetRole' | 'skillTags';
  label: string;
  hint: string;
  complete: boolean;
}

export function getProfileCompletion(profile: UserProfile | null): {
  percentage: number;
  steps: CompletionStep[];
} {
  const steps: CompletionStep[] = [
    {
      key: 'displayName',
      label: '确认你的称呼',
      hint: '便于生成更自然的报告与反馈',
      complete: Boolean(profile?.displayName?.trim() && profile.displayName.trim().length >= 2),
    },
    {
      key: 'targetRole',
      label: '设定目标岗位',
      hint: '让面试准备更聚焦',
      complete: Boolean(profile?.targetRole?.trim()),
    },
    {
      key: 'skillTags',
      label: '添加至少 3 项技能',
      hint: '技能越具体，匹配结果越准确',
      complete: (profile?.skillTags?.length ?? 0) >= 3,
    },
  ];
  const completed = steps.filter((step) => step.complete).length;
  return { percentage: Math.round((completed / steps.length) * 100), steps };
}

export function getUserInitials(profile: UserProfile | null): string {
  const source = profile?.displayName?.trim() || profile?.username || 'U';
  return source.slice(0, 2).toUpperCase();
}
