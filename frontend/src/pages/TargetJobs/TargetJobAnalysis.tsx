import { useEffect, useState } from 'react';
import { Alert, Button, Empty, Select, Tag } from 'antd';
import { Link } from 'react-router-dom';
import { targetJobService } from '@/api/modules/targetJob';
import { resumeService } from '@/api/modules/resume';
import type { ResumeProfile } from '@/types/api.d';
import type { TargetJob, TargetAnalysis } from '@/types/targetJob';
import { kindOptions } from './TargetJobEditor';
import styles from './index.module.css';

export default function TargetJobAnalysis({ job }: { job: TargetJob }) {
  const [resumes, setResumes] = useState<ResumeProfile[]>([]);
  const [resumeId, setResumeId] = useState<number>();
  const [analysis, setAnalysis] = useState<TargetAnalysis | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [reload, setReload] = useState(0);
  useEffect(() => {
    const request = new AbortController();
    void resumeService.getList(request.signal).then(values => { if (!request.signal.aborted) setResumes(values); })
      .catch(cause => { if (!request.signal.aborted) setError(cause instanceof Error ? cause.message : '简历读取失败'); });
    return () => request.abort();
  }, [reload]);
  useEffect(() => {
    setAnalysis(null);
    if (!resumeId) return;
    const request = new AbortController(); setLoading(true); setError('');
    void targetJobService.analyze(job, resumeId, request.signal).then(result => { if (!request.signal.aborted) setAnalysis(result); })
      .catch(cause => { if (!request.signal.aborted) setError(cause instanceof Error ? cause.message : '分析失败，请重试'); })
      .finally(() => { if (!request.signal.aborted) setLoading(false); });
    return () => request.abort();
  }, [job, resumeId, reload]);

  return <div>
    <div className={styles.detailMeta}>
      <span>{job.company || '公司未知'}</span><span>{job.sourceName || '来源未填写'}</span><span>截止：{job.deadline || '未说明'}</span>
      {job.sourceUrl && <a href={job.sourceUrl} target="_blank" rel="noopener noreferrer">查看来源 ↗</a>}
    </div>
    <details className={styles.source}><summary>查看完整 JD</summary><p>{job.jdText}</p></details>
    <div className={styles.analysisPicker}><label htmlFor="target-resume">用哪份简历核对</label>
      <Select id="target-resume" placeholder="选择已保存的简历" value={resumeId} onChange={setResumeId} loading={loading}
        options={resumes.map(resume => ({ value: resume.id, label: `${resume.ownerName} · ${resume.targetRole}` }))} />
      <Button loading={loading} onClick={() => setReload(value => value + 1)}>重新核对</Button>
    </div>
    {resumes.length === 0 && <p><Link to="/resumes">先创建或保存一份简历</Link>，再对照要求查看证据。</p>}
    {error && <Alert type="error" showIcon message={error} />}
    <Alert type="info" showIcon message="关联原文不代表已满足要求" description="按已确认的检索词定位原文，每条最多展示 8 处。没有证据时保持未知，请核对熟练程度、年限及组合条件。" />
    {resumeId && <p><Link to={`/interview?${new URLSearchParams({ resumeId: String(resumeId), targetJobId: job.id })}`}>使用这份简历与岗位开始面试 →</Link></p>}
    {analysis && <p className={styles.hint}>基于简历已保存版本核对 · <Link to={`/resumes/edit?${new URLSearchParams({ resumeId: String(analysis.resumeId) })}`}>编辑这份简历 →</Link></p>}
    {kindOptions.map(kind => {
      const rows = job.requirements.filter(requirement => requirement.kind === kind.value);
      return <section className={styles.analysisSection} key={kind.value}>
        <h3>{kind.label}<span>{rows.length.toString().padStart(2, '0')}</span></h3>
        {rows.length === 0 ? <p className={styles.hint}>尚无此类要求，请对照 JD 补充。</p> : rows.map(requirement => {
          const match = analysis?.requirements.find(item => item.requirement.id === requirement.id);
          return <article key={requirement.id} className={styles.evidenceCard}>
            <div className={styles.evidenceTitle}><strong>{requirement.text}</strong><Tag color={match?.status === 'REFERENCED' ? 'blue' : 'default'}>{match?.status === 'REFERENCED' ? '有关联原文 · 待核对' : match ? '未知 · 暂无证据' : '待选择简历'}</Tag></div>
            {!requirement.keywords.length && <p className={styles.hint}>尚无检索词，可编辑要求补充，也可人工核对此条件。</p>}
            {match?.unreferencedKeywords.length ? <p className={styles.hint}>未找到原文的检索词：{match.unreferencedKeywords.join('、')}</p> : null}
            {match?.evidence.map(evidence => <blockquote key={evidence.fieldPath} data-field-path={evidence.fieldPath} className={styles.quote}>
              <div><span>{evidence.label}</span></div><p>{evidence.quote}</p>
              <small>关联：{evidence.keywords.join('、')}</small>
            </blockquote>)}
          </article>;
        })}
      </section>;
    })}
    {job.requirements.length === 0 && <Empty description="尚无岗位要求，可编辑后提取或手动补充" />}
    {analysis && <Alert type="warning" showIcon message="仍需确认的信息" description={<ul>{analysis.unknownInformation.map(item => <li key={item}>{item}</li>)}</ul>} />}
  </div>;
}
