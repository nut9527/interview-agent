import type { ResumeProfile } from '@/types/api.d';

import styles from './ResumeDocument.module.css';

interface ResumeSectionProps {
  title: string;
  sectionId: string;
  items: string[];
}

function ResumeSection({ title, sectionId, items }: ResumeSectionProps) {
  if (items.length === 0) return null;

  return (
    <section className={styles.section}>
      <h2 data-pdf-heading className={styles.sectionHeading}>{title}</h2>
      <ul className={styles.experienceList}>
        {items.map((item, index) => (
          <li data-pdf-keep className={styles.experienceItem} key={`${sectionId}-${index}-${item}`}>
            {item}
          </li>
        ))}
      </ul>
    </section>
  );
}

function SkillSection({ skills }: { skills: string[] }) {
  if (skills.length === 0) return null;

  return (
    <section className={styles.section}>
      <h2 data-pdf-heading className={styles.sectionHeading}>专业技能</h2>
      <div className={styles.skillList}>
        {skills.map((skill, index) => (
          <span className={styles.skill} key={`${index}-${skill}`}>{skill}</span>
        ))}
      </div>
    </section>
  );
}

interface ResumeDocumentProps {
  resume: ResumeProfile;
}

function ResumeDocument({ resume }: ResumeDocumentProps) {
  const projects = resume.details?.projects ?? [];
  const education = resume.details?.education ?? [];
  const contact = resume.details?.contact;
  const hasSidebar = resume.skills.length > 0 || resume.certificates.length > 0;
  const paperClassName = [styles.paper, styles.classic].join(' ');
  const gridClassName = [styles.resumeGrid, !hasSidebar ? styles.singleColumn : '']
    .filter(Boolean)
    .join(' ');

  return (
    <article className={paperClassName} aria-label={`${resume.ownerName}的简历`}>
      <header data-pdf-keep className={styles.header}>
        <div className={styles.identity}>
          <p className={styles.eyebrow}>Professional Resume</p>
          <h1 className={styles.name}>{resume.ownerName}</h1>
        </div>
        <div className={styles.roleBlock}>
          <span className={styles.roleLabel}>目标岗位</span>
          <p className={styles.role}>{resume.targetRole}</p>
        </div>
      </header>

      {contact && <address className={styles.contact} data-pdf-keep>
        <div className={styles.contactRow}>
          {contact.phone && <span>电话：{contact.phone}</span>}
          {contact.email && <a href={`mailto:${contact.email}`}>邮箱：{contact.email}</a>}
          {contact.location && <span>城市：{contact.location}</span>}
        </div>
        {contact.links?.map((link, index) => <div key={index}>
          <span>{link.label}：</span>
          {/^https?:\/\//i.test(link.url ?? '') ? <a href={link.url} target="_blank" rel="noreferrer">{link.url}</a> : link.url}
        </div>)}
      </address>}

      {(resume.details?.graduationDate || resume.details?.availability) && <p>
        {resume.details.graduationDate && `预计毕业：${resume.details.graduationDate} `}
        {resume.details.availability && `实习安排：${resume.details.availability}`}
      </p>}
      {resume.summary && (
        <section className={styles.summaryBlock}>
          <span className={styles.summaryMark}>“</span>
          <p className={styles.summary}>{resume.summary}</p>
        </section>
      )}

      <div className={gridClassName}>
        <main className={styles.mainColumn}>
          {education.length > 0 && <section className={styles.section}>
            <h2 data-pdf-heading className={styles.sectionHeading}>教育经历</h2>
            {education.map((entry, index) => <div data-pdf-keep className={styles.entry} key={index}>
              <div className={styles.entryHeading}><h3>{entry.school}</h3><span>{entry.startDate} - {entry.endDate}</span></div>
              <p className={styles.entryMeta}>{[entry.major, entry.degree].filter(Boolean).join(' · ')}</p>
            </div>)}
          </section>}
          <ResumeSection title={education.length ? '教育经历补充' : '教育经历'} sectionId="education" items={resume.educationExperiences} />
          {projects.length > 0 && <section className={styles.section}>
            <h2 data-pdf-heading className={styles.sectionHeading}>项目经历</h2>
            {projects.map((project, index) => <div data-pdf-keep className={styles.entry} key={index}>
              <div data-pdf-heading className={styles.entryHeading}><h3>{project.name}</h3><span>{project.period}</span></div>
              {project.role && <p className={styles.entryMeta}>角色：{project.role}</p>}
              {!!project.techStack?.length && <p className={styles.entryMeta}>技术栈：{project.techStack.join(' / ')}</p>}
              {project.background && <p className={styles.entryBackground}>{project.background}</p>}
              {!!project.contributions?.length && <ul className={styles.contributions}>
                {project.contributions.map((contribution, i) => <li key={i}>{contribution}</li>)}
              </ul>}
            </div>)}
          </section>}
          <ResumeSection title={projects.length ? '其他项目经历' : '项目经历'} sectionId="project" items={resume.projectExperiences} />
          <ResumeSection title="实习经历" sectionId="internship" items={resume.internshipExperiences} />
        </main>

        {hasSidebar && (
          <aside className={styles.sidebar} aria-label="技能与证书">
            <SkillSection skills={resume.skills} />
            <ResumeSection title="证书与荣誉" sectionId="certificate" items={resume.certificates} />
          </aside>
        )}
      </div>

    </article>
  );
}

export default ResumeDocument;
