import { useState } from 'react';
import { Form, Input, Select } from 'antd';
import type { ResumeProfileRequest } from '@/types/api.d';
import { ContactFields, EducationFields, ProjectFields } from './ResumeDetailFields';
import styles from './ResumeDetailFields.module.css';

const { TextArea } = Input;

export const resumeSections = [
  { id: 'resume-identity', title: '基本资料' },
  { id: 'resume-strengths', title: '简介与技能' },
  { id: 'resume-education', title: '教育背景' },
  { id: 'resume-projects', title: '项目经验' },
  { id: 'resume-extra', title: '实习与荣誉' },
] as const;

function ExperienceNotes({ name, entries, label, imported }: {
  name: 'educationExperiences' | 'projectExperiences';
  entries: 'education' | 'projects';
  label: string;
  imported: boolean;
}) {
  const form = Form.useFormInstance();
  const text = Form.useWatch(name, { form, preserve: true });
  const rows = Form.useWatch(['details', entries], { form, preserve: true });
  const [editing, setEditing] = useState(false);
  // 已有文字记录可逐行修改；清空最后一行时保留焦点，以便继续输入。
  if (!imported && !text && !editing) return null;
  return <details className={styles.disclosure} open={!rows?.length}>
    <summary>{label}</summary>
    <Form.Item name={name}>
      <TextArea aria-label={label} autoSize={{ minRows: 2, maxRows: 8 }}
        placeholder="每行一条" onFocus={() => setEditing(true)} />
    </Form.Item>
  </details>;
}

export default function ResumeEditorFields({ imported = false }: { imported?: boolean }) {
  const [identity, strengths, education, projects, extra] = resumeSections;
  const form = Form.useFormInstance();
  const [showSchedule] = useState(() => {
    const details = form.getFieldValue('details') as ResumeProfileRequest['details'];
    return !!details?.availability || (!!details?.graduationDate
      && !details.education?.some(entry => entry.endDate === details.graduationDate));
  });
  return <>
    <section className={styles.section} id={identity.id} tabIndex={-1}>
      <h2>{identity.title}</h2>
      <div className={styles.grid}>
        <Form.Item name="ownerName" label="姓名" rules={[{ required: true, whitespace: true, message: '请输入姓名' }]}>
          <Input autoComplete="name" maxLength={80} />
        </Form.Item>
        <Form.Item name="targetRole" label="目标岗位" rules={[{ required: true, whitespace: true, message: '请输入目标岗位' }]}>
          <Input maxLength={120} placeholder="例如：后端开发工程师" />
        </Form.Item>
      </div>
      <ContactFields />
      <details className={styles.disclosure} open={imported || showSchedule}>
        <summary>求职安排</summary>
        <div className={styles.grid}>
          <Form.Item name={['details', 'graduationDate']} label="预计毕业年月" rules={[{ max: 120 }]}>
            <Input maxLength={120} placeholder="例如：2028 年 6 月" />
          </Form.Item>
          <Form.Item name={['details', 'availability']} label="实习安排" rules={[{ max: 200 }]}>
            <Input maxLength={200} placeholder="到岗日期、每周天数、持续月数" />
          </Form.Item>
        </div>
      </details>
    </section>

    <section className={styles.section} id={strengths.id} tabIndex={-1}>
      <h2>{strengths.title}</h2>
      <Form.Item name="summary" label="个人简介">
        <TextArea rows={3} placeholder="专业背景和与岗位相关的优势" />
      </Form.Item>
      <Form.Item name="skills" label="技能标签">
        <Select mode="tags" placeholder="请输入技能标签，回车确认" />
      </Form.Item>
    </section>

    <section className={styles.section} id={education.id} tabIndex={-1}>
      <h2>{education.title}</h2>
      <EducationFields showHeading={false} />
      <ExperienceNotes name="educationExperiences" entries="education" label="教育经历文字记录" imported={imported} />
    </section>

    <section className={styles.section} id={projects.id} tabIndex={-1}>
      <h2>{projects.title}</h2>
      <ProjectFields showHeading={false} />
      <ExperienceNotes name="projectExperiences" entries="projects" label="项目经历文字记录" imported={imported} />
    </section>

    <section className={styles.section} id={extra.id} tabIndex={-1}>
      <h2>{extra.title}</h2>
      <Form.Item name="internshipExperiences" label="实习与工作经历">
        <TextArea rows={3} placeholder="每行一条，填写公司、岗位和主要工作" />
      </Form.Item>
      <Form.Item name="certificates" label="证书与荣誉">
        <Select mode="tags" placeholder="输入证书或奖项，回车确认" />
      </Form.Item>
    </section>
  </>;
}
