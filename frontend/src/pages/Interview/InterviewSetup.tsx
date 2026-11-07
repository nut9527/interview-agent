import { useCallback, useEffect, useRef, useState } from 'react';
import { Alert, Button, Input, Radio, Select, Space, Spin, Tag } from 'antd';
import { useSearchParams } from 'react-router-dom';
import {
  AimOutlined, ArrowRightOutlined, FileTextOutlined, ImportOutlined,
  LockOutlined, ReloadOutlined, SettingOutlined, SolutionOutlined,
} from '@ant-design/icons';
import { resumeService } from '@/api/modules/resume';
import { targetJobService } from '@/api/modules/targetJob';
import { captureAccountScope } from '@/api/accountScope';
import { isRequestCanceled } from '@/api/ApiError';
import type { InterviewDifficulty, InterviewMode, InterviewSessionRequest, InterviewTrainingTopic, ResumeProfile } from '@/types/api.d';
import type { TargetJob, TargetJobSummary } from '@/types/targetJob';
import { JobImport, ResumeImport } from './MaterialImport';
import { buildInterviewRequest, difficultyOptions, trainingTopics } from './interviewSettings';
import styles from './setup.module.css';
import { knowledgeService } from '@/api/modules/knowledge';
import type { KnowledgeSummary } from '@/types/knowledge';

export default function InterviewSetup({ busy, onStart }: { busy: boolean; onStart: (request: InterviewSessionRequest) => Promise<void> }) {
  const [params] = useSearchParams();
  const initialJobId = useRef(params.get('targetJobId'));
  const [resumes, setResumes] = useState<ResumeProfile[]>([]);
  const [resumeId, setResumeId] = useState<number | undefined>(() =>
    params.has('resumeId') ? Number(params.get('resumeId')) : undefined);
  const resume = resumes.find(item => item.id === resumeId);
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
  const [jobRounds, setJobRounds] = useState(6);
  const [topicRounds, setTopicRounds] = useState(6);
  const [experienceMode, setExperienceMode] = useState<'TEACHING' | 'FORMAL'>('TEACHING');
  const [timeLimitMinutes, setTimeLimitMinutes] = useState<number | undefined>();
  const [mode, setMode] = useState<InterviewMode>('GENERAL');
  const [trainingTopic, setTrainingTopic] = useState<InterviewTrainingTopic | undefined>(params.has('knowledgeDocumentId') ? 'DATABASE' : undefined);
  const [specialized, setSpecialized] = useState(params.has('knowledgeDocumentId'));
  const rounds = specialized ? topicRounds : jobRounds;
  const [knowledge, setKnowledge] = useState<KnowledgeSummary[]>([]);
  const [knowledgeIds, setKnowledgeIds] = useState<string[]>(params.getAll('knowledgeDocumentId'));
  const [knowledgeError, setKnowledgeError] = useState('');
  useEffect(() => {
    const request = new AbortController(), scope = captureAccountScope();
    void knowledgeService.choices(request.signal).then(result => { if (scope.isCurrent() && !request.signal.aborted) setKnowledge(result.items); })
      .catch(cause => { if (scope.isCurrent() && !request.signal.aborted && !isRequestCanceled(cause)) setKnowledgeError(cause instanceof Error ? cause.message : '读取知识库失败'); });
    return () => request.abort();
  }, [reload]);
  const [difficulty, setDifficulty] = useState<InterviewDifficulty>('STANDARD');
  const [jobSource, setJobSource] = useState<'saved' | 'custom'>(params.has('targetJobId') ? 'saved' : 'custom');
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
      if (current()) setResumes(rows);
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
      else if (!currentJobId.current && (initialJobId.current || result.items[0])) void chooseJob(initialJobId.current ?? result.items[0].id);
    }).catch(cause => { if (current() && !isRequestCanceled(cause)) setJobError(cause instanceof Error ? cause.message : '读取岗位失败'); })
      .finally(() => { if (current()) setJobLoading(false); });
    return () => request.abort();
  }, [page, reload, chooseJob]);
  useEffect(() => () => detailRequest.current?.abort(), []);

  const start = async () => {
    if (busy || preparationHint || starting.current) return;
    starting.current = true; setStartError(''); const scope = captureAccountScope();
    try { await onStart({ ...buildInterviewRequest({ resumeId: resume?.id, jobSource, job, customRole, customJd, mode, difficulty, rounds, trainingTopic: specialized ? trainingTopic : undefined }), experienceMode, timeLimitMinutes, ...(specialized && knowledgeIds.length ? { knowledgeDocumentIds: knowledgeIds } : {}) }); }
    catch (cause) { if (scope.isCurrent() && !isRequestCanceled(cause)) setStartError(cause instanceof Error ? cause.message : '开始面试失败'); }
    finally { starting.current = false; }
  };
  const missingJobFields = [
    !customRole.trim() && '岗位名称',
    !customJd.trim() && '岗位 JD',
  ].filter(Boolean);
  let preparationHint = '';
  if (resumeId !== undefined && resumeLoading) {
    preparationHint = '正在读取已选简历…';
  } else if (resumeId !== undefined && (!resume || resumeError)) {
    preparationHint = '已选简历暂不可用，请刷新材料、选择其他简历或移除本场简历。';
  } else if (jobSource === 'saved' && detailLoading) {
    preparationHint = '正在读取岗位说明…';
  } else if (specialized && !trainingTopic) {
    preparationHint = '请选择一个训练主题。';
  } else if (jobSource === 'custom' && missingJobFields.length && (!specialized || missingJobFields.length === 1)) {
    preparationHint = `请补充${missingJobFields.join('和')}${specialized ? '，或清空岗位信息直接练习主题' : ''}。`;
  } else if (jobSource === 'saved' && !specialized && !job) {
    preparationHint = '请选择已保存的岗位，或填写本场自定义 JD。';
  }

  return <section className={styles.setup} aria-label="导入材料并开始面试">
    <div className={styles.preparation}>
    <div className={styles.setupHeading}><h2><span className={styles.headingIcon}><SolutionOutlined aria-hidden="true" /></span>面试准备</h2>
      <Button type="text" icon={<ReloadOutlined aria-hidden="true" />} disabled={busy || jobLoading || resumeLoading} onClick={() => { setPage(1); setReload(value => value + 1); }}>刷新材料</Button></div>
    <div className={styles.trainingPicker}>
      <Radio.Group className={styles.modePicker} aria-label="训练方式" value={specialized} disabled={busy} onChange={event => {
        setSpecialized(event.target.value); setStartError('');
        if (event.target.value) setTrainingTopic(trainingTopic ?? 'DATABASE');
      }}><Radio.Button value={false}><FileTextOutlined aria-hidden="true" /> <span>岗位 JD 面试</span></Radio.Button><Radio.Button value={true}><AimOutlined aria-hidden="true" /> <span>专项训练</span></Radio.Button></Radio.Group>
      {specialized && <><p className={styles.hint}>选一个主题练习 3～6 轮，也可以补充岗位和简历。</p>
        <Radio.Group className={styles.topics} aria-label="专项训练主题" value={trainingTopic} onChange={event => setTrainingTopic(event.target.value)} disabled={busy}>
          {trainingTopics.map(topic => <Radio.Button key={topic.value} value={topic.value}><strong>{topic.label}</strong><small>{topic.description}</small></Radio.Button>)}
        </Radio.Group></>}
    </div>
    <div className={styles.materials}>
      <article className={styles.materialCard}>
        <div className={styles.materialHeading}><h3>目标岗位 <small>{specialized ? '可选' : '必填'}</small></h3><Button icon={<ImportOutlined aria-hidden="true" />} disabled={busy} onClick={() => setDialog('job')}>导入岗位</Button></div>
        <Radio.Group aria-label="岗位材料来源" value={jobSource} onChange={event => { setJobSource(event.target.value); setStartError(''); }} disabled={busy} className={styles.sourcePicker}>
          <Radio.Button value="saved">已保存目标岗位</Radio.Button><Radio.Button value="custom">本场自定义 JD</Radio.Button>
        </Radio.Group>
        {jobSource === 'custom' ? <div className={styles.customJd}>
          <label htmlFor="custom-role">面试岗位名称</label><Input id="custom-role" maxLength={160} value={customRole} onChange={event => setCustomRole(event.target.value)} disabled={busy} placeholder="例如：Java 后端实习生" />
          <label htmlFor="custom-jd">自定义岗位 JD</label><Input.TextArea id="custom-jd" rows={4} maxLength={20000} showCount value={customJd} onChange={event => setCustomJd(event.target.value)} disabled={busy} placeholder="粘贴岗位职责、任职要求和希望考察的重点…" />
          <p className={styles.hint}>JD 原文随本场记录保存。如需在多场面试中使用，可选择「导入岗位」。</p>
        </div> : <>
        <Select aria-label="选择已导入的目标岗位" placeholder="选择已导入的岗位" value={job?.id} loading={jobLoading || detailLoading} disabled={busy || !jobs.length} allowClear={specialized}
          showSearch optionFilterProp="label" options={jobs.map(item => ({ value: item.id, label: `${item.title}${item.company ? ` · ${item.company}` : ''}` }))}
          onChange={id => { if (id) void chooseJob(id); else { detailRequest.current?.abort(); currentJobId.current = undefined; setJob(null); setDetailLoading(false); } }} style={{ width: '100%' }} />
        {jobs.length < total && <Button type="link" size="small" disabled={jobLoading || busy} onClick={() => setPage(value => value + 1)}>加载更多岗位（已载入 {jobs.length} / {total}）</Button>}
        {jobError && <Alert type="warning" showIcon message={jobError} className={styles.feedback} />}
        {job ? <div className={styles.materialPreview}><p className={styles.materialStatus}><span>就绪</span><strong>{job.title}</strong>{job.company && ` · ${job.company}`}</p>
          <details><summary>查看完整 JD</summary><p className={styles.original}>{job.jdText}</p></details></div>
          : !jobError && <div className={styles.emptyMaterial}>{(jobLoading || detailLoading) && <Spin size="small" />}<p>{jobLoading || detailLoading ? '正在读取岗位…' : jobs.length ? '请选择一个已保存的岗位。' : '还没有已保存的岗位。可导入岗位，或切换到「本场自定义 JD」。'}</p></div>}
        </>}
      </article>
      <details className={styles.materialCard} open={resumeId !== undefined ? true : undefined}>
        <summary>补充个人简历（可选）</summary>
        <div className={styles.materialHeading}><p className={styles.hint}>补充简历后，可结合岗位要求追问你的项目经历。</p><Button disabled={busy} onClick={() => setDialog('resume')}>导入简历</Button></div>
        <Select aria-label="选择已导入的个人简历" placeholder="选择要用于本场面试的简历" value={resume?.id} loading={resumeLoading} disabled={busy || !resumes.length} allowClear
          options={resumes.map(item => ({ value: item.id, label: `${item.ownerName} · ${item.targetRole}` }))}
          onChange={setResumeId} style={{ width: '100%' }} />
        {resumeError && <Alert type="warning" showIcon message={resumeError} className={styles.feedback} />}
        {resumeId !== undefined && !resumeLoading && (!resume || resumeError) &&
          <Button disabled={busy} onClick={() => setResumeId(undefined)}>移除本场简历</Button>}
        {resume ? <div className={styles.materialPreview}><p className={styles.materialStatus}><span>就绪</span><strong>{resume.ownerName}</strong> · {resume.targetRole}</p>
          <details><summary>核对面试使用的经历</summary><div className={styles.original}>
            <Space size={[4, 4]} wrap>{resume.skills.slice(0, 6).map(skill => <Tag key={skill}>{skill}</Tag>)}</Space>
            <p>{resume.summary}</p>{[...resume.educationExperiences, ...resume.projectExperiences, ...resume.internshipExperiences].map((text, i) => <p key={i}>{text}</p>)}
            {resume.details?.projects.map((project, i) => <div key={i}><strong>{project.name}</strong><p>{project.background}</p><p>{project.contributions.join('\n')}</p></div>)}
          </div></details></div> : !resumeLoading && !resumeError && !resumes.length && <div className={styles.emptyMaterial}><p>可导入 PDF 或 Word 简历，核对内容后保存。</p></div>}
      </details>
    </div>
    {specialized && <section><h3>导入资料（可选）</h3><Select mode="multiple" aria-label="专项训练资料" maxCount={5} style={{ width: '100%' }} value={knowledgeIds} disabled={busy} placeholder="选择学习资料，结合原文进行专项训练" options={knowledge.map(d => ({ value: d.id, label: `${d.title} · v${d.version}` }))} onChange={setKnowledgeIds} /><p className={styles.hint}>最多 5 份、合计 100000 字；本场固定资料版本。删除资料后，相关训练将停止后续生成并隐藏该资料原文。</p>{knowledgeError && <Alert type="warning" message={knowledgeError} />}</section>}
    </div>
    <aside className={styles.settings} aria-labelledby="interview-settings-heading">
    <div className={styles.settingsHeading}><h2 id="interview-settings-heading"><span className={styles.headingIcon}><SettingOutlined aria-hidden="true" /></span>本场设置</h2></div>
    <fieldset className={styles.difficulty}><legend>反馈方式</legend>
      <Radio.Group aria-label="面试反馈模式" value={experienceMode} disabled={busy} onChange={e => setExperienceMode(e.target.value)}>
        <Radio value="TEACHING">教学练习</Radio>
        <Radio value="FORMAL">正式模拟</Radio>
      </Radio.Group>
      <p className={styles.hint}>{experienceMode === 'TEACHING' ? '每轮查看反馈与追问提示，边练边改。' : '答题中隐藏反馈与提示，结束后统一复盘。'}</p>
    </fieldset>
    <fieldset className={styles.difficulty}><legend>面试难度</legend>
      <Radio.Group aria-label="面试难度" value={difficulty} disabled={busy} onChange={event => setDifficulty(event.target.value)}>
        {difficultyOptions.map(option => <Radio key={option.value} value={option.value}><span>{option.label}</span></Radio>)}
      </Radio.Group>
      <p className={styles.hint}>{difficultyOptions.find(option => option.value === difficulty)?.description}</p>
    </fieldset>
    <div className={styles.sessionOptions}>
      <div><label htmlFor="interview-rounds">练习长度</label><Select id="interview-rounds" aria-label="练习长度" value={rounds} onChange={specialized ? setTopicRounds : setJobRounds} disabled={busy}
        options={(specialized ? [3, 4, 5, 6] : [3, 6, 9, 12]).map(value => ({ value, label: `${value} 轮${value === 6 ? ' · 标准' : ''}` }))} /></div>
      <div><label htmlFor="interview-timer">整场计时</label><Select id="interview-timer" aria-label="整场计时" value={timeLimitMinutes ?? 0} disabled={busy} onChange={value => setTimeLimitMinutes(value || undefined)}
        options={[{ value: 0, label: '不计时' }, ...[10, 20, 30, 45, 60].map(value => ({ value, label: `${value} 分钟` }))]} /></div>
      {!specialized && <div><label htmlFor="interview-direction">练习方向</label><Select id="interview-direction" aria-label="练习方向" value={mode} onChange={setMode} disabled={busy} options={[
          { value: 'GENERAL', label: '综合能力' }, { value: 'PROJECT_DEEP_DIVE', label: '项目深挖' }, { value: 'BACKEND_BASICS', label: '后端基础' },
        ]} /></div>}
    </div>
    {timeLimitMinutes && <p className={styles.hint}>离开页面后继续计时。时间到会提醒，仍可继续作答。</p>}
    <div className={styles.launch}>
      <p id="interview-start-hint" role="status">{preparationHint || `已就绪 · 本场共 ${rounds} 轮`}</p>
      <Button type="primary" block size="large" aria-describedby="interview-start-hint" loading={busy} disabled={!!preparationHint || busy} onClick={() => void start()}>{specialized ? '开始专项训练' : '开始模拟面试'}<ArrowRightOutlined aria-hidden="true" /></Button></div>
    {startError && <Alert type="error" showIcon message={startError} description="材料与输入已保留。若版本发生变化，请刷新材料后重试。" className={styles.feedback} />}
    <p className={styles.privacy}><LockOutlined aria-hidden="true" /> 岗位、简历和回答随本场记录保存，仅本人可见。</p>
    </aside>
    {dialog === 'resume' && <ResumeImport onClose={() => setDialog(null)} onSaved={saved => {
      resumeRequest.current?.abort(); setResumeLoading(false); setResumeError(''); setResumes(previous => [saved, ...previous.filter(item => item.id !== saved.id)]); setResumeId(saved.id); setDialog(null);
    }} />}
    {dialog === 'job' && <JobImport onClose={() => setDialog(null)} onSaved={saved => {
      setJobSource('saved');
      jobRequest.current?.abort(); detailRequest.current?.abort(); setJobLoading(false); setDetailLoading(false); setJobError('');
      setJobs(previous => [saved, ...previous.filter(item => item.id !== saved.id)]); setTotal(value => value + 1); currentJobId.current = saved.id; setJob(saved); setDialog(null);
    }} />}
  </section>;
}
