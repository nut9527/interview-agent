interface WarningTarget {
  label: string;
  section: string;
  formField?: string[];
}

const fields: Record<string, WarningTarget> = {
  ownerName: { label: '姓名', section: 'resume-identity', formField: ['ownerName'] },
  targetRole: { label: '目标岗位', section: 'resume-identity', formField: ['targetRole'] },
  summary: { label: '个人简介', section: 'resume-strengths', formField: ['summary'] },
  skills: { label: '技能标签', section: 'resume-strengths', formField: ['skills'] },
  educationExperiences: { label: '教育经历文字记录', section: 'resume-education', formField: ['educationExperiences'] },
  projectExperiences: { label: '项目经历文字记录', section: 'resume-projects', formField: ['projectExperiences'] },
  internshipExperiences: { label: '实习与工作经历', section: 'resume-extra', formField: ['internshipExperiences'] },
  certificates: { label: '证书与荣誉', section: 'resume-extra', formField: ['certificates'] },
  'details.contact': { label: '联系方式', section: 'resume-identity' },
  'details.contact.phone': { label: '联系电话', section: 'resume-identity', formField: ['details', 'contact', 'phone'] },
  'details.contact.email': { label: '联系邮箱', section: 'resume-identity', formField: ['details', 'contact', 'email'] },
  'details.contact.location': { label: '所在城市', section: 'resume-identity', formField: ['details', 'contact', 'location'] },
  'details.contact.links': { label: '作品与个人链接', section: 'resume-identity' },
  'details.graduationDate': { label: '预计毕业年月', section: 'resume-identity', formField: ['details', 'graduationDate'] },
  'details.availability': { label: '实习安排', section: 'resume-identity', formField: ['details', 'availability'] },
  'details.education': { label: '教育经历', section: 'resume-education' },
  'details.projects': { label: '项目经历', section: 'resume-projects' },
};
const educationLabels: Record<string, string> = { school: '学校', major: '专业', degree: '学历', startDate: '入学年月', endDate: '毕业年月' };
const projectLabels: Record<string, string> = { name: '项目名称', role: '本人角色', period: '项目时间', background: '项目背景', contributions: '个人贡献', techStack: '项目技术栈' };

/** 警告下标对应识别原始结果；丢弃条目后只定位列表区域，不将其套到编辑器的新下标。 */
export function importWarningTarget(field: string): WarningTarget {
  const path = field.replace(/\[\d+\]/g, '');
  const name = path.slice(path.lastIndexOf('.') + 1);
  if (fields[path]) return fields[path];
  if (path.startsWith('details.education.')) return { label: educationLabels[name] ?? '教育经历', section: 'resume-education' };
  if (path.startsWith('details.projects.')) return { label: projectLabels[name] ?? '项目经历', section: 'resume-projects' };
  if (path.startsWith('details.contact.links.')) return fields['details.contact.links'];
  return { label: '导入内容', section: 'resume-identity' };
}
