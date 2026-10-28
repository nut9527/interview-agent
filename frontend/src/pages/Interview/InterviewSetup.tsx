import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, Button, Input, Radio, Select, Space, Spin, Tag } from 'antd';
import { useSearchParams } from 'react-router-dom';
import { ReloadOutlined } from '@ant-design/icons';
import { resumeService } from '@/api/modules/resume';
import { targetJobService } from '@/api/modules/targetJob';
import { captureAccountScope } from '@/api/accountScope';
import { isRequestCanceled } from '@/api/ApiError';
import type { InterviewDifficulty, InterviewMode, InterviewSessionRequest, InterviewTrainingTopic, ResumeProfile } from '@/types/api.d';
import type { TargetJob, TargetJobSummary } from '@/types/targetJob';
import { JobImport, ResumeImport } from './MaterialImport';
import { buildInterviewRequest, difficultyOptions, trainingTopics } from './interviewSettings';
import styles from './setup.module.css';

export default function InterviewSetup({ busy, onStart }: { busy: boolean; onStart: (request: InterviewSessionRequest) => Promise<void> }) {
  const [params] = useSearchParams();
  const initial = useRef({ resumeId: Number(params.get('resumeId')), jobId: params.get('targetJobId') });
  const [resumes, setResumes] = useState<ResumeProfile[]>([]);
  const [resume, setResume] = useState<ResumeProfile | null>(null);
  const [jobs, setJobs] = useState<TargetJobSummary[]>([]);
  const [job, setJob] = useState<TargetJob | null>(null);
  const [page, setPage] = useState(1);
  const [total, setTotal] = useState(0);
  const [reload, setReload] = useState(0);
  const [resumeLoading, setResumeLoading] = useState(true);
  const [jobLoading, setJobLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(false);
  const [resumeError, setResumeError] = useState('');
  const [jobError, setJobError] = useState('');
  const [startError, setStartError] = useState('');
  const [dialog, setDialog] = useState<'resume' | 'job' | null>(null);
  const [rounds, setRounds] = useState(6);
  const [mode, setMode] = useState<InterviewMode>('GENERAL');
  const [trainingTopic, setTrainingTopic] = useState<InterviewTrainingTopic | undefined>();
  const [specialized, setSpecialized] = useState(false);
  const [difficulty, setDifficulty] = useState<InterviewDifficulty>('STANDARD');
  const [jobSource, setJobSource] = useState<'saved' | 'custom'>('saved');
  const [customRole, setCustomRole] = useState('');
  const [customJd, setCustomJd] = useState('');
  const resumeRequest = useRef<AbortController>();
  const jobRequest = useRef<AbortController>();
  const detailRequest = useRef<AbortController>();
  const currentJobId = useRef<string>();
  const starting = useRef(false);

  const chooseJob = useCallback(async (id: string) => {
    detailRequest.current?.abort(); const request = new AbortController(); detailRequest.current = request;
    const scope = captureAccountScope(); currentJobId.current = id;
    setDetailLoading(true); setJob(null); setJobError('');
    try { const result = await targetJobService.get(id, request.signal); if (!request.signal.aborted && scope.isCurrent()) setJob(result); }
    catch (cause) { if (!request.signal.aborted && scope.isCurrent() && !isRequestCanceled(cause)) setJobError(cause instanceof Error ? cause.message : '读取岗位失败'); }
    finally { if (!request.signal.aborted && scope.isCurrent()) setDetailLoading(false); }
  }, []);

  useEffect(() => {
    const request = new AbortController(); resumeRequest.current = request; const scope = captureAccountScope();
    const current = () => !request.signal.aborted && scope.isCurrent(); setResumeLoading(true); setResumeError('');
    void resumeService.getList(request.signal).then(rows => {
      if (current()) { setResumes(rows); setResume(previous => rows.find(item => item.id === (previous?.id ?? initial.current.resumeId)) ?? rows[0] ?? null); }
    }).catch(cause => { if (current() && !isRequestCanceled(cause)) setResumeError(cause instanceof Error ? cause.message : '读取简历失败'); })
      .finally(() => { if (current()) setResumeLoading(false); });
    return () => request.abort();
  }, [reload]);

  useEffect(() => {
    const request = new AbortController(); jobRequest.current = request; const scope = captureAccountScope();
    const current = () => !request.signal.aborted && scope.isCurrent(); setJobLoading(true); setJobError('');
    void targetJobService.list(page, request.signal).then(result => {
      if (!current()) return;
      setJobs(previous => page === 1 ? result.items : [...previous, ...result.items.filter(item => !previous.some(old => old.id === item.id))]);
      setTotal(result.total);
      const selected = result.items.find(item => item.id === currentJobId.current);
      if (selected) void chooseJob(selected.id);
      else if (!currentJobId.current && (initial.current.jobId || result.items[0])) void chooseJob(initial.current.jobId ?? result.items[0].id);
    }).catch(cause => { if (current() && !isRequestCanceled(cause)) setJobError(cause instanceof Error ? cause.message : '读取岗位失败'); })
      .finally(() => { if (current()) setJobLoading(false); });
    return () => request.abort();
  }, [page, reload, chooseJob]);
  useEffect(() => () => detailRequest.current?.abort(), []);

  const start = async () => {
    if ((!specialized && !resume) || busy || (jobSource === 'saved' && detailLoading) || starting.current) return;
    starting.current = true; setStartError(''); const scope = captureAccountScope();
    try { await onStart(buildInterviewRequest({ resumeId: resume?.id, jobSource, job, customRole, customJd, mode, difficulty, rounds, trainingTopic: specialized ? trainingTopic : undefined })); }
    catch (cause) { if (scope.isCurrent() && !isRequestCanceled(cause)) setStartError(cause instanceof Error ? cause.message : '开始面试失败'); }
    finally { starting.current = false; }
  };
  const materialsReady = specialized ? !!trainingTopic && !detailLoading
    : !!resume && (jobSource === 'custom' ? !!customRole.trim() && !!customJd.trim() : !!job && !detailLoading);

  return <section className={styles.setup} aria-label="导入材料并开始面试">
    <div className={styles.setupHeading}><div><h2>面试准备</h2><p>选择简历与 JD，按自己的节奏设置难度和轮次。</p></div>
      <Button type="text" icon={<ReloadOutlined />} disabled={busy || jobLoading || resumeLoading} onClick={() => { setPage(1); setReload(value => value + 1); }}>刷新材料</Button></div>
    <div className={styles.trainingPicker}>
      <Radio.Group aria-label="训练方式" value={specialized} disabled={busy} onChange={event => {
        setSpecialized(event.target.value); setStartError('');
        if (event.target.value) { setTrainingTopic(trainingTopic ?? 'DATABASE'); setRounds(value => Math.min(6, value)); }
      }}><Radio.Button value={false}>综合面试</Radio.Button><Radio.Button value={true}>专项训练</Radio.Button></Radio.Group>
      {specialized && <><p className={styles.hint}>围绕一个主题集中练习 3～6 轮，根据回答继续追问。岗位与简历可选。</p>
        <Radio.Group className={styles.topics} aria-label="专项训练主题" value={trainingTopic} onChange={event => setTrainingTopic(event.target.value)} disabled={busy}>
          {trainingTopics.map(topic => <Radio.Button key={topic.value} value={topic.value}><strong>{topic.label}</strong><small>{topic.description}</small></Radio.Button>)}
        </Radio.Group></>}
    </div>
    <div className={styles.materials}>
      <article className={styles.materialCard}>
        <div className={styles.materialHeading}><h3><span>01</span> 目标岗位{specialized && '（可选）'}</h3><Button disabled={busy} onClick={() => setDialog('job')}>导入岗位</Button></div>
        <Radio.Group aria-label="岗位材料来源" value={jobSource} onChange={event => { setJobSource(event.target.value); setStartError(''); }} disabled={busy} className={styles.sourcePicker}>
          <Radio.Button value="saved">已保存目标岗位</Radio.Button><Radio.Button value="custom">本场自定义 JD</Radio.Button>
        </Radio.Group>
        {jobSource === 'custom' ? <div className={styles.customJd}>
          <label htmlFor="custom-role">面试岗位名称</label><Input id="custom-role" maxLength={160} value={customRole} onChange={event => setCustomRole(event.target.value)} disabled={busy} placeholder="例如：Java 后端实习生" />
          <label htmlFor="custom-jd">自定义岗位 JD</label><Input.TextArea id="custom-jd" rows={7} maxLength={20000} showCount value={customJd} onChange={event => setCustomJd(event.target.value)} disabled={busy} placeholder="粘贴岗位职责、任职要求和希望考察的重点…" />
          <p className={styles.hint}>仅用于本场面试，原文会保存在面试记录中。需要重复使用时可导入目标岗位。</p>
        </div> : <>
        <Select aria-label="选择已导入的目标岗位" placeholder="选择已导入的岗位" value={job?.id} loading={jobLoading || detailLoading} disabled={busy || !jobs.length} allowClear={specialized}
          showSearch optionFilterProp="label" options={jobs.map(item => ({ value: item.id, label: `${item.title}${item.company ? ` · ${item.company}` : ''}` }))}
          onChange={id => { if (id) void chooseJob(id); else { detailRequest.current?.abort(); currentJobId.current = undefined; setJob(null); setDetailLoading(false); } }} style={{ width: '100%' }} />
        {jobs.length < total && <Button type="link" size="small" disabled={jobLoading || busy} onClick={() => setPage(value => value + 1)}>加载更多岗位（已载入 {jobs.length} / {total}）</Button>}
        {jobError && <Alert type="warning" showIcon message={jobError} className={styles.feedback} />}
        {job ? <div className={styles.materialPreview}><p className={styles.materialStatus}><span>就绪</span><strong>{job.title}</strong>{job.company && ` · ${job.company}`}</p>
          <details><summary>查看完整 JD</summary><p className={styles.original}>{job.jdText}</p></details></div>
          : <div className={styles.emptyMaterial}>{detailLoading && <Spin size="small" />}<p>{detailLoading ? '正在读取岗位说明…' : '未导入 · 粘贴岗位 JD，或读取 TXT / Markdown 文件。'}</p></div>}
        </>}
      </article>
      <article className={styles.materialCard}>
        <div className={styles.materialHeading}><h3><span>02</span> 个人简历{specialized && '（可选）'}</h3><Button disabled={busy} onClick={() => setDialog('resume')}>导入简历</Button></div>
        <Select aria-label="选择已导入的个人简历" placeholder="选择已导入的简历" value={resume?.id} loading={resumeLoading} disabled={busy || !resumes.length} allowClear={specialized}
          options={resumes.map(item => ({ value: item.id, label: `${item.ownerName} · ${item.targetRole}` }))}
          onChange={id => setResume(resumes.find(item => item.id === id) ?? null)} style={{ width: '100%' }} />
        {resumeError && <Alert type="warning" showIcon message={resumeError} className={styles.feedback} />}
        {resume ? <div className={styles.materialPreview}><p className={styles.materialStatus}><span>就绪</span><strong>{resume.ownerName}</strong> · {resume.targetRole}</p>
          <details><summary>核对面试使用的经历</summary><div className={styles.original}>
            <Space size={[4, 4]} wrap>{resume.skills.slice(0, 6).map(skill => <Tag key={skill}>{skill}</Tag>)}</Space>
            <p>{resume.summary}</p>{[...resume.educationExperiences, ...resume.projectExperiences, ...resume.internshipExperiences].map((text, i) => <p key={i}>{text}</p>)}
            {resume.details?.projects.map((project, i) => <div key={i}><strong>{project.name}</strong><p>{project.background}</p><p>{project.contributions.join('\n')}</p></div>)}
          </div></details></div> : <div className={styles.emptyMaterial}><p>未导入 · 上传 PDF / Word，核对内容后保存。</p></div>}
      </article>
    </div>
    <fieldset className={styles.difficulty}><legend>面试难度</legend>
      <Radio.Group aria-label="面试难度" value={difficulty} disabled={busy} onChange={event => setDifficulty(event.target.value)}>
        {difficultyOptions.map(option => <Radio key={option.value} value={option.value}><strong>{option.label}</strong><span className={styles.difficultyDescription}>{option.description}</span></Radio>)}
      </Radio.Group>
    </fieldset>
    <div className={styles.launch}><div><strong>{specialized ? '选定主题，集中练习' : materialsReady ? '两份材料已就绪' : '选择两份材料后即可开始'}</strong><p>问题依据主题、材料和所选难度生成，结束后可从复盘卡片复练。</p>
      <Space wrap><label htmlFor="interview-rounds">练习长度</label><Select id="interview-rounds" aria-label="练习长度" value={rounds} onChange={setRounds} disabled={busy}
        options={(specialized ? [3, 4, 5, 6] : [3, 6, 9, 12]).map(value => ({ value, label: `${value} 轮${value === 6 ? ' · 标准' : ''}` }))} />
        {!specialized && <details className={styles.advanced}><summary>练习方向</summary><Select aria-label="练习方向" value={mode} onChange={setMode} disabled={busy} style={{ minWidth: 150 }} options={[
          { value: 'GENERAL', label: '综合能力' }, { value: 'PROJECT_DEEP_DIVE', label: '项目深挖' }, { value: 'BACKEND_BASICS', label: '后端基础' },
        ]} /></details>}</Space></div>
      <Button type="primary" size="large" loading={busy} disabled={!materialsReady || busy} onClick={() => void start()}>{specialized ? '开始专项训练' : '开始模拟面试'}</Button></div>
    <aside className={styles.briefing} aria-labelledby="interview-briefing-heading"><h3 id="interview-briefing-heading">面试须知</h3>
      <ol><li><strong>按轮回答</strong><p>{specialized ? '专项训练可选 3～6 轮，围绕主题动态追问。' : '标准 6 轮，可选择 3、9 或 12 轮。'}</p></li>
        <li><strong>选择适合的难度</strong><p>入门梳理基础，标准模拟面试，挑战深入原理与复杂约束。</p></li>
        <li><strong>结束后复盘</strong><p>回看回答原文、能力证据和下一次练习建议。</p></li></ol>
      <p className={styles.briefingNote}>岗位、简历和回答随本场记录保存，仅本人可见。</p>
    </aside>
    {startError && <Alert type="error" showIcon message={startError} description="材料与输入已保留。若版本发生变化，请刷新材料后重试。" className={styles.feedback} />}
    {dialog === 'resume' && <ResumeImport onClose={() => setDialog(null)} onSaved={saved => {
      resumeRequest.current?.abort(); setResumeLoading(false); setResumeError(''); setResumes(previous => [saved, ...previous.filter(item => item.id !== saved.id)]); setResume(saved); setDialog(null);
    }} />}
    {dialog === 'job' && <JobImport onClose={() => setDialog(null)} onSaved={saved => {
      setJobSource('saved');
      jobRequest.current?.abort(); detailRequest.current?.abort(); setJobLoading(false); setDetailLoading(false); setJobError('');
      setJobs(previous => [saved, ...previous.filter(item => item.id !== saved.id)]); setTotal(value => value + 1); currentJobId.current = saved.id; setJob(saved); setDialog(null);
    }} />}
  </section>;
}
