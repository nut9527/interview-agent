import type { ResumeProfileRequest } from '@/types/api.d';

/** Keep raw multiline input in the draft, including unfinished lines. */
export interface EditorValues {
  ownerName: string;
  targetRole: string;
  summary: string;
  skills: string[];
  educationExperiences: string;
  projectExperiences: string;
  internshipExperiences: string;
  certificates: string[];
  templateKey: string;
  details: NonNullable<ResumeProfileRequest['details']>;
}

export function formValues(value: Partial<ResumeProfileRequest> = {}): EditorValues {
  return normalizeValues({ ...value, summary: value.summary ?? '', skills: value.skills ?? [],
    certificates: value.certificates ?? [], templateKey: value.templateKey ?? 'classic', details: value.details ?? undefined,
    educationExperiences: value.educationExperiences?.join('\n'),
    projectExperiences: value.projectExperiences?.join('\n'),
    internshipExperiences: value.internshipExperiences?.join('\n'),
  });
}

export function normalizeValues(value: Partial<EditorValues>): EditorValues {
  const details = value.details;
  return {
    ownerName: value.ownerName ?? '', targetRole: value.targetRole ?? '', summary: value.summary ?? '',
    skills: value.skills ?? [], certificates: value.certificates ?? [], templateKey: value.templateKey ?? 'classic',
    educationExperiences: value.educationExperiences ?? '', projectExperiences: value.projectExperiences ?? '',
    internshipExperiences: value.internshipExperiences ?? '',
    details: {
      graduationDate: details?.graduationDate ?? '', availability: details?.availability ?? '',
      contact: { phone: details?.contact?.phone ?? '', email: details?.contact?.email ?? '',
        location: details?.contact?.location ?? '', links: (details?.contact?.links ?? []).map(link => ({ label: link.label ?? '', url: link.url ?? '' })) },
      education: (details?.education ?? []).map(e => ({ school: e.school ?? '', major: e.major ?? '',
        degree: e.degree ?? '', startDate: e.startDate ?? '', endDate: e.endDate ?? '' })),
      projects: (details?.projects ?? []).map(p => ({ name: p.name ?? '', role: p.role ?? '', period: p.period ?? '',
        background: p.background ?? '', contributions: p.contributions ?? [], techStack: p.techStack ?? [] })),
    },
  };
}

export const toRequest = (values: EditorValues): ResumeProfileRequest => ({ ...values,
  educationExperiences: values.educationExperiences.split('\n').filter(Boolean),
  projectExperiences: values.projectExperiences.split('\n').filter(Boolean),
  internshipExperiences: values.internshipExperiences.split('\n').filter(Boolean),
});

export const equal = (left: unknown, right: unknown) => JSON.stringify(left) === JSON.stringify(right);

export const comparisonFields = [
  ['ownerName', '姓名'], ['targetRole', '目标岗位'], ['summary', '个人简介'], ['skills', '技能'],
  ['educationExperiences', '教育经历补充'], ['projectExperiences', '项目经历补充'],
  ['internshipExperiences', '实习经历'], ['certificates', '证书'], ['templateKey', '模板'],
  ['details.contact.phone', '电话'], ['details.contact.email', '邮箱'], ['details.contact.location', '城市'],
  ['details.contact.links', '作品链接'], ['details.education', '结构化教育经历'],
  ['details.graduationDate', '毕业时间'], ['details.availability', '实习安排'], ['details.projects', '结构化项目经历'],
] as const;
export type FieldPath = typeof comparisonFields[number][0];
export type Choices = Partial<Record<FieldPath, 'local' | 'server'>>;
export const fieldValue = (data: EditorValues, path: FieldPath): unknown => path.split('.').reduce<unknown>((value, key) =>
  (value as Record<string, unknown> | undefined)?.[key], data);

export function differences(base: EditorValues, local: EditorValues, server: EditorValues) {
  return comparisonFields.filter(([path]) => !equal(fieldValue(local, path), fieldValue(server, path)))
    .map(([path, label]) => ({ path, label, local: fieldValue(local, path), server: fieldValue(server, path),
      suggested: equal(fieldValue(local, path), fieldValue(base, path)) ? 'server' as const
        : equal(fieldValue(server, path), fieldValue(base, path)) ? 'local' as const : undefined }));
}

export function mergeChoices(base: EditorValues, local: EditorValues, server: EditorValues, choices: Choices) {
  const merged = structuredClone(local);
  for (const diff of differences(base, local, server)) {
    const choice = choices[diff.path] ?? diff.suggested;
    if (!choice) throw new Error('请为双方都修改的字段选择要保留的内容');
    if (choice === 'server') {
      const parts = diff.path.split('.');
      const last = parts.pop()!;
      const parent = parts.reduce((value, key) => value[key] as Record<string, unknown>, merged as unknown as Record<string, unknown>);
      parent[last] = structuredClone(diff.server);
    }
  }
  return normalizeValues(merged);
}
