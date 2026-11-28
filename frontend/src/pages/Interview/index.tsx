import { useEffect, useRef } from 'react';
import { Alert, Button, Card, Empty, Input, Modal, Pagination, Skeleton, Space, Spin, message } from 'antd';
import {
  CheckCircleFilled,
  ClockCircleOutlined,
  DeleteOutlined,
  EyeOutlined,
  HistoryOutlined,
  PlayCircleOutlined,
  ReloadOutlined,
  SendOutlined,
} from '@ant-design/icons';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import type { InterviewHistoryItem, InterviewSessionRequest, SpeakerType } from '@/types/api.d';
import InterviewSetup from './InterviewSetup';
import InterviewIllustration from './InterviewIllustration';
import { difficultyOptions, trainingTopics } from './interviewSettings';
import { RelatedRetries, TrainingComparison } from './TrainingReview';
import { AgentPlan, AgentReport } from './AgentWorkspace';
import { RunTrace } from './RunTrace';
import { AssessmentDetails } from './AssessmentDetails';
import { useAuthStore } from '@/stores/authStore';
import { useInterviewSession } from './useInterviewSession';
import { useInterviewData } from './useInterviewData';
import { isRequestCanceled } from '@/api/ApiError';
import styles from './index.module.css';
import { InterviewTimer, TrainingTasks, WeaknessResources, useAddTrainingTask } from './InterviewLearning';

const answerLimit = 4000;

const formatDateTime = (value: string) => {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '时间未知';
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(date);
};

const formatShortDate = (value: string) => {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '-- --';
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit' }).format(date).replace(/\//g, '-');
};

function InterviewPage({ userId }: { userId: number }) {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const {
    history,
    historyPage,
    setHistoryPage,
    historyTotal,
    historyLoading,
    historyError,
    fetchHistory,
    refresh,
    deleteSession,
  } = useInterviewData();
  const { view, controller } = useInterviewSession(userId, refresh, {
    sessionId: params.get('sessionId'),
    runId: params.get('runId'),
    preparing:
      params.has('resumeId') || params.has('targetJobId') || params.has('knowledgeDocumentId') || params.has('plan'),
  });
  const session = view.server;
  const formal = session?.experience?.format === 'FORMAL';
  const answers = session?.turns.filter((turn) => turn.speaker === 'user').map((turn) => turn.content) ?? [];
  const { add: addTask, saving: savingTask } = useAddTrainingTask(session?.sessionId ?? '');
  const inputValue = view.draft;
  const { phase, error: recoveryError, needsRecovery, retryAt, activeId } = view.ui;
  const sending = phase === 'submitting';
  const starting = phase === 'starting';
  const recovering = phase === 'recovering';
  const recoveryWaiting = phase === 'waiting';
  const coolingDown = retryAt > Date.now();
  const busy = phase !== 'idle';
  const historyOpening = recovering || recoveryWaiting ? activeId : null;
  const transcriptRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const transcript = transcriptRef.current;
    if (transcript) transcript.scrollTop = transcript.scrollHeight;
  }, [session?.turns]);
  const submitDisabled = !!session?.sourceRevoked || !inputValue.trim() || busy || needsRecovery || coolingDown;
  const handleSubmit = () => {
    if (!submitDisabled) void controller.current?.submit();
  };
  const handleStart = async (request: InterviewSessionRequest) => {
    if (busy || coolingDown) return;
    const started = await controller.current?.start(request);
    if (started) navigate('/interview?' + new URLSearchParams({ sessionId: started.sessionId }), { replace: true });
  };
  const handleRetry = async (round: number) => {
    if (!session || busy || coolingDown) return;
    try {
      await handleStart({
        targetRole: session.targetRole,
        mode: session.mode,
        totalRounds: 3,
        retrySessionId: session.sessionId,
        retryRound: round,
      });
    } catch (cause) {
      if (!isRequestCanceled(cause)) message.error(cause instanceof Error ? cause.message : '发起复练失败');
    }
  };
  const handleRestart = () => {
    controller.current?.restart();
    navigate('/interview');
    refresh();
  };
  const handleOpenHistory = (id: string) => navigate('/interview?' + new URLSearchParams({ sessionId: id }));
  const restoreSession = () => controller.current?.recover();

  const handleDeleteHistory = (item: InterviewHistoryItem) => {
    Modal.confirm({
      title: '删除这场面试记录？',
      content: `“${item.targetRole}”的面试问答和反馈将被永久删除，此操作不可恢复。`,
      okText: '确认删除',
      cancelText: '保留记录',
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          if (!(await deleteSession(item.sessionId))) return;
          controller.current?.forgetDeletedSession(item.sessionId);
          message.success('历史面试记录已删除');
        } catch (error) {
          if (isRequestCanceled(error)) return;
          message.error(error instanceof Error ? error.message : '删除历史面试记录失败');
          throw error;
        }
      },
    });
  };

  const resumeLabel = (resumeId: number | null) => (resumeId === null ? '个人经历摘要' : '已导入简历 #' + resumeId);

  const renderSpeakerLabel = (speaker: SpeakerType) => {
    if (speaker === 'system') return null;
    return (
      <div className={styles.speakerLabel}>
        <span>{speaker === 'interviewer' ? '面试官' : '我的回答'}</span>
      </div>
    );
  };

  const speakerClass: Record<SpeakerType, string> = {
    system: styles.systemTurn,
    interviewer: styles.interviewerTurn,
    user: styles.userTurn,
  };

  return (
    <main className={styles.page}>
      <header className={session ? styles.sessionTitle : styles.pageHeader}>
        <div>
          <h1>模拟面试</h1>
          {!session && (
            <>
              <p>按岗位要求练习面试，或选择一个主题集中练习。</p>
              <a className={styles.historyLink} href="#interview-history-heading">
                <HistoryOutlined aria-hidden="true" /> 查看历史记录
              </a>
            </>
          )}
        </div>
        {session ? (
          <Button icon={<ReloadOutlined aria-hidden="true" />} disabled={busy} onClick={handleRestart}>
            更换面试材料
          </Button>
        ) : (
          <InterviewIllustration />
        )}
      </header>
      {session?.sourceRevoked && (
        <Alert type="warning" showIcon message="本场资料已删除，相关评价已撤销。回答记录保留；请更换材料新建训练。" />
      )}
      {params.get('questionGroup') && (
        <Link to={'/questions/practice?' + new URLSearchParams(params.get('questionGroup')!)}>
          返回本组，继续下一题
        </Link>
      )}
      {session?.state === 'FINISHED' && recoveryError && <Alert type="warning" showIcon message={recoveryError} />}
      {session?.state === 'FINISHED' && starting && (
        <Alert type="info" showIcon message="正在根据原能力与回答缺口创建复练…" />
      )}
      {session?.state === 'FINISHED' && coolingDown && (
        <Alert type="info" showIcon message={`请在 ${new Date(retryAt).toLocaleTimeString()} 后重试。`} />
      )}

      {!session ? (
        <>
          {(recoveryError || recovering || recoveryWaiting) && (
            <Alert
              type={recoveryError ? 'warning' : 'info'}
              showIcon
              message={recoveryError || '正在恢复上次面试…'}
              action={
                <Button disabled={busy || coolingDown} onClick={() => void restoreSession()}>
                  核对并恢复
                </Button>
              }
            />
          )}
          {coolingDown && (
            <Alert type="info" showIcon message={`请在 ${new Date(retryAt).toLocaleTimeString()} 后重试。`} />
          )}
          {params.has('plan') && <TrainingTasks />}
          <InterviewSetup busy={busy || coolingDown} onStart={handleStart} />

          <section className={styles.historySection} aria-labelledby="interview-history-heading">
            <div className={styles.historyHeader}>
              <div>
                <h2 id="interview-history-heading">历史面试记录</h2>
                <p>继续上次的面试，或回看已完成的问答与反馈。</p>
              </div>
              <Space>
                <span className={styles.historyCount}>{historyTotal} 场记录</span>
                <Button
                  icon={<ReloadOutlined aria-hidden="true" />}
                  loading={historyLoading}
                  onClick={() => void fetchHistory()}
                >
                  刷新
                </Button>
              </Space>
            </div>

            {historyError ? (
              <Alert
                type="error"
                showIcon
                message="历史记录加载失败"
                description={historyError}
                action={<Button onClick={() => void fetchHistory()}>重试</Button>}
              />
            ) : historyLoading ? (
              <div className={styles.historySkeleton}>
                <Skeleton paragraph={{ rows: 3 }} />
                <Skeleton paragraph={{ rows: 3 }} />
              </div>
            ) : history.length === 0 ? (
              <div className={styles.historyEmpty}>
                <Empty
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description="开始第一场面试后，可以在这里继续作答或回看记录。"
                />
              </div>
            ) : (
              <div className={styles.historyTimeline}>
                {history.map((item) => (
                  <article className={styles.historyItem} key={item.sessionId}>
                    <div className={styles.historyDate}>
                      <span>{formatShortDate(item.updatedAt)}</span>
                      <i aria-hidden="true" />
                    </div>
                    <div className={styles.historyBody}>
                      <div className={styles.historyTitleRow}>
                        <div>
                          <Space size={8} wrap>
                            <span className={styles.status} data-pending={item.state !== 'FINISHED'}>
                              {item.state === 'FINISHED' ? '已完成' : '待继续'}
                            </span>
                            <span className={styles.historyResume}>{resumeLabel(item.resumeId)}</span>
                          </Space>
                          <h3>{item.targetRole}</h3>
                        </div>
                        <div className={styles.historyProgress}>
                          <strong>{item.answeredCount}</strong>
                          <span> / {item.totalRounds} 轮回答</span>
                        </div>
                      </div>
                      <p className={styles.historyPreview}>{item.preview}</p>
                      <div className={styles.historyFooter}>
                        <time dateTime={item.updatedAt}>
                          <ClockCircleOutlined aria-hidden="true" /> {formatDateTime(item.updatedAt)}
                        </time>
                        <Space>
                          <Button
                            type="text"
                            danger
                            icon={<DeleteOutlined aria-hidden="true" />}
                            onClick={() => handleDeleteHistory(item)}
                          >
                            删除
                          </Button>
                          <Button
                            type="primary"
                            ghost
                            icon={
                              item.state === 'FINISHED' ? (
                                <EyeOutlined aria-hidden="true" />
                              ) : (
                                <PlayCircleOutlined aria-hidden="true" />
                              )
                            }
                            loading={historyOpening === item.sessionId}
                            disabled={starting || coolingDown}
                            onClick={() => void handleOpenHistory(item.sessionId)}
                          >
                            {item.state === 'FINISHED' ? '查看记录' : '继续面试'}
                          </Button>
                        </Space>
                      </div>
                    </div>
                  </article>
                ))}
              </div>
            )}
            <Pagination
              hideOnSinglePage
              current={historyPage}
              total={historyTotal}
              pageSize={20}
              showSizeChanger={false}
              onChange={setHistoryPage}
            />
          </section>
        </>
      ) : (
        <section className={styles.interviewLayout} aria-label="模拟面试进行区">
          <Card className={styles.interviewCard} variant="borderless">
            <header className={styles.sessionHeader}>
              <div className={styles.sessionMeta}>
                <span>当前面试</span>
                <span className={styles.status} data-pending={session.state !== 'FINISHED'}>
                  {session.state === 'FINISHED' ? '面试结束' : '面试进行中'}
                </span>
              </div>
              <h2>{session.targetRole}</h2>
              <div className={styles.sessionMeta}>
                <span>{formal ? '正式模拟 · 结束后统一复盘' : '教学练习 · 每轮回答后查看反馈'}</span>
                <InterviewTimer session={session} />
              </div>
              <div
                className={styles.roundProgress}
                role="progressbar"
                aria-label="回答进度"
                aria-valuemin={0}
                aria-valuemax={session.totalRounds}
                aria-valuenow={answers.length}
                aria-valuetext={`已答 ${answers.length} 轮，共 ${session.totalRounds} 轮`}
              >
                <div className={styles.progressTrack}>
                  <span style={{ width: `${(answers.length / session.totalRounds) * 100}%` }} />
                </div>
                <span>
                  已答 {answers.length} / {session.totalRounds} 轮
                </span>
              </div>
            </header>

            <div className={styles.transcript} aria-live="polite" ref={transcriptRef}>
              {session.turns.map((turn, index) => (
                <article className={speakerClass[turn.speaker]} key={`${turn.speaker}-${index}`}>
                  {renderSpeakerLabel(turn.speaker)}
                  <p>{turn.content}</p>
                </article>
              ))}
              {(sending || recovering || recoveryWaiting) && (
                <div className={styles.thinkingTurn}>
                  <Spin size="small" />
                  <span>{view.ui.runPhase || (sending ? '正在提交回答…' : '正在恢复本场面试…')}</span>
                  {view.ui.activeRunId && (
                    <Button size="small" onClick={() => void controller.current?.cancelRun()}>
                      取消本轮
                    </Button>
                  )}
                </div>
              )}
            </div>

            {session.state === 'FINISHED' ? (
              <div className={styles.finishedBar}>
                <div>
                  <CheckCircleFilled aria-hidden="true" />
                  <strong>本场面试已完成</strong>
                </div>
                <Space wrap>
                  {session.agent && (
                    <Button
                      type="primary"
                      onClick={() =>
                        document
                          .getElementById('interview-agent-report')
                          ?.scrollIntoView({ behavior: 'smooth', block: 'start' })
                      }
                    >
                      查看能力复盘
                    </Button>
                  )}
                  <Button icon={<ReloadOutlined aria-hidden="true" />} onClick={handleRestart}>
                    再来一场
                  </Button>
                </Space>
              </div>
            ) : (
              <div className={styles.composer}>
                {(recoveryWaiting || recoveryError) && (
                  <Alert
                    type={recoveryError ? 'warning' : 'info'}
                    showIcon
                    message={recoveryError || '正在核对服务端回答状态，请稍候…'}
                    action={
                      recoveryError ? (
                        <Button disabled={busy || coolingDown} onClick={() => void restoreSession()}>
                          核对并恢复
                        </Button>
                      ) : undefined
                    }
                  />
                )}
                {coolingDown && (
                  <Alert type="info" showIcon message={`请在 ${new Date(retryAt).toLocaleTimeString()} 后重试。`} />
                )}
                <div className={styles.answerHeading}>
                  <label htmlFor="interview-answer">面试回答</label>
                  <span>
                    {inputValue.length} / {answerLimit}
                  </span>
                </div>
                <Input.TextArea
                  id="interview-answer"
                  aria-label="面试回答"
                  aria-describedby={formal ? 'interview-shortcuts' : 'interview-answer-tip interview-shortcuts'}
                  placeholder="输入你的回答…"
                  value={inputValue}
                  onChange={(event) => controller.current?.setDraft(event.target.value)}
                  autoSize={{ minRows: 3, maxRows: 7 }}
                  maxLength={answerLimit}
                  disabled={busy || needsRecovery}
                  readOnly={!!session.sourceRevoked}
                  enterKeyHint="send"
                  onKeyDown={(event) => {
                    const textarea = event.currentTarget;
                    textarea.onbeforeinput = null;
                    if (event.key !== 'Enter' || event.shiftKey || event.altKey || event.nativeEvent.isComposing)
                      return;
                    if (event.ctrlKey || event.metaKey) {
                      event.preventDefault();
                      if (!event.repeat) handleSubmit();
                      return;
                    }
                    // 有些输入法先结束组词，再派发确认候选的 Enter。等浏览器实际请求换行才发送。
                    textarea.onbeforeinput = (edit) => {
                      if (edit.isComposing || edit.inputType !== 'insertLineBreak') return;
                      edit.preventDefault();
                      if (!event.repeat) handleSubmit();
                    };
                  }}
                  onKeyUp={(event) => {
                    event.currentTarget.onbeforeinput = null;
                  }}
                  onBlur={(event) => {
                    event.currentTarget.onbeforeinput = null;
                  }}
                />
                <div className={styles.composerFooter}>
                  <div className={styles.composerHints}>
                    {!formal && <p id="interview-answer-tip">举例时，讲清你做了什么，以及怎样判断结果。</p>}
                    <p id="interview-shortcuts" className={styles.shortcuts}>
                      <span>
                        <kbd>Enter</kbd> 发送
                      </span>
                      <span>
                        <kbd>Shift</kbd> + <kbd>Enter</kbd> 换行
                      </span>
                    </p>
                  </div>
                  <Button
                    type="primary"
                    icon={<SendOutlined aria-hidden="true" />}
                    loading={sending || recovering || recoveryWaiting}
                    disabled={submitDisabled}
                    onClick={handleSubmit}
                  >
                    提交回答
                  </Button>
                </div>
              </div>
            )}
          </Card>

          <aside className={styles.sessionSidebar}>
            {session.agent && (
              <details className={styles.contextCard}>
                <summary>查看本场考察计划与依据</summary>
                <AgentPlan
                  agent={session.agent}
                  finished={session.state === 'FINISHED'}
                  sessionId={session.sessionId}
                  answers={answers}
                />
              </details>
            )}
            {!formal &&
              session.state !== 'FINISHED' &&
              session.agent?.evaluations
                .slice(-1)
                .map((e) => (
                  <AssessmentDetails
                    key={e.round}
                    assessment={e.assessment}
                    round={e.round}
                    sessionId={session.sessionId}
                    answer={answers[e.round - 1]}
                    training={session.agent?.training}
                  />
                ))}
            <div className={styles.contextCard}>
              <span>本场依据</span>
              {session.agent?.training?.question && (
                <div>
                  <h3>
                    {session.agent.training.question.title} · 版本 {session.agent.training.question.version}
                  </h3>
                  <Link to={'/questions/' + session.agent.training.question.id + '/practices'}>查看同题历次练习</Link>
                  <details>
                    <summary>本场原题快照</summary>
                    <p>{session.agent.training.question.question}</p>
                  </details>
                  {session.state === 'FINISHED' && (
                    <details>
                      <summary>本场参考解析</summary>
                      <p>{session.agent.training.question.referenceAnswer}</p>
                      <ul>
                        {session.agent.training.question.keyPoints.map((point) => (
                          <li key={point}>{point}</li>
                        ))}
                      </ul>
                    </details>
                  )}
                </div>
              )}
              <h3>
                {session.agent?.training?.retry
                  ? '薄弱点复练'
                  : session.agent?.training?.topic
                    ? `${trainingTopics.find((topic) => topic.value === session.agent?.training?.topic)?.label}专项训练`
                    : session.mode === 'PROJECT_DEEP_DIVE'
                      ? '项目深挖'
                      : session.mode === 'BACKEND_BASICS'
                        ? '后端基础'
                        : '通用面试'}
              </h3>
              <p>
                {session.targetRole} ·{' '}
                {difficultyOptions.find((option) => option.value === (session.difficulty ?? 'STANDARD'))?.label}难度 ·{' '}
                {session.totalRounds} 轮
              </p>
              {session.agent?.training?.retry && <p>原回答缺口：{session.agent.training.retry.gap}</p>}
              {session.resumeId && (
                <p>
                  简历 #{session.resumeId} · 修订 {session.resumeRevision ?? '历史未记录'}
                </p>
              )}
              {session.jobId && <p>岗位 #{session.jobId}</p>}
              {session.jobSnapshot && (
                <details>
                  <summary>创建时的岗位 / JD</summary>
                  <p>{session.jobSnapshot}</p>
                </details>
              )}
              {session.resumeSnapshot && (
                <details>
                  <summary>创建时的简历依据</summary>
                  <p>{session.resumeSnapshot}</p>
                </details>
              )}
            </div>
            {(!formal || session.state === 'FINISHED') && (
              <RunTrace key={session.sessionId} sessionId={session.sessionId} turnCount={session.turns.length} />
            )}
          </aside>
          {session.agent?.training?.retry && <TrainingComparison session={session} />}
          {session.state === 'FINISHED' && session.agent && (
            <>
              <AgentReport
                agent={session.agent}
                sessionId={session.sessionId}
                answers={answers}
                busy={!!session.sourceRevoked || busy || coolingDown || savingTask}
                onRetry={(round) => void handleRetry(round)}
                onAddTask={(round) => void addTask(round)}
              />
              <WeaknessResources key={session.sessionId + '-resources'} sessionId={session.sessionId} />
              <RelatedRetries key={session.sessionId} sessionId={session.sessionId} />
            </>
          )}
        </section>
      )}
    </main>
  );
}

function Interview() {
  const [params] = useSearchParams();
  const userId = useAuthStore((state) => state.user?.id);
  const generation = useAuthStore((state) => state.generation);
  return userId === undefined ? null : <InterviewPage key={`${generation}:${params.toString()}`} userId={userId} />;
}

export default Interview;
