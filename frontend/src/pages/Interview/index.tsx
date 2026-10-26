import { useEffect, useRef } from 'react';
import {
  Alert,
  Button,
  Card,
  Empty,
  Input,
  Modal,
  Pagination,
  Skeleton,
  Space,
  Spin,
  Typography,
  message,
} from 'antd';
import {
  CheckCircleFilled,
  ClockCircleOutlined,
  DeleteOutlined,
  EyeOutlined,
  PlayCircleOutlined,
  ReloadOutlined,
  SendOutlined,
} from '@ant-design/icons';
import { useNavigate, useSearchParams } from 'react-router-dom';
import type { InterviewHistoryItem, InterviewSessionRequest, SpeakerType } from '@/types/api.d';
import InterviewSetup from './InterviewSetup';
import { difficultyOptions } from './interviewSettings';
import { AgentPlan, AgentReport } from './AgentWorkspace';
import { useAuthStore } from '@/stores/authStore';
import { useInterviewSession } from './useInterviewSession';
import { useInterviewData } from './useInterviewData';
import { isRequestCanceled } from '@/api/ApiError';
import styles from './index.module.css';
import { USE_MOCK } from '@/config/runtime';

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
  const { history, historyPage, setHistoryPage, historyTotal, historyLoading, historyError,
    fetchHistory, refresh, deleteSession } = useInterviewData();
  const { view, controller } = useInterviewSession(userId, refresh, { sessionId: params.get('sessionId'),
    preparing: params.has('resumeId') || params.has('targetJobId') });
  const session = view.server;
  const inputValue = view.draft;
  const { phase, error: recoveryError, needsRecovery, retryAt, activeId } = view.ui;
  const sending = phase === 'submitting';
  const starting = phase === 'starting';
  const recovering = phase === 'recovering';
  const recoveryWaiting = phase === 'waiting';
  const coolingDown = retryAt > Date.now();
  const busy = phase !== 'idle';
  const historyOpening = recovering || recoveryWaiting ? activeId : null;
  const messagesEndRef = useRef<HTMLDivElement>(null);
  useEffect(() => { messagesEndRef.current?.scrollIntoView({ behavior: 'smooth' }); }, [session?.turns]);
  const handleSubmit = () => controller.current?.submit();
  const handleStart = async (request: InterviewSessionRequest) => {
    if (busy || coolingDown) return;
    const started = await controller.current?.start(request);
    if (started) navigate('/interview?' + new URLSearchParams({ sessionId: started.sessionId }), { replace: true });
  };
  const handleRestart = () => { controller.current?.restart(); navigate('/interview'); refresh(); };
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
          if (!await deleteSession(item.sessionId)) return;
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

  const resumeLabel = (resumeId: number | null) => resumeId === null ? '个人经历摘要' : '已导入简历 #' + resumeId;

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
      <header className={styles.pageHeader}>
        <div>
          <h1>面试训练室</h1>
          <p>导入岗位与简历，完成提问、回答和复盘。</p>
        </div>
        <div className={styles.headerBadge}>
          <span><strong>隐私提示</strong>岗位与简历依据会随面试记录保存，供本人回看</span>
        </div>
      </header>
      {USE_MOCK && <Alert type="info" showIcon message="完整的材料导入与 Agent 面试需要连接后端。后端支持无密钥的 Mock AI，可直接体验完整流程。" />}
      {session && <Button icon={<ReloadOutlined />} disabled={busy} onClick={handleRestart} style={{ marginBottom: 16 }}>更换面试材料</Button>}

      {!session ? (
        <>
          {(recoveryError || recovering || recoveryWaiting) && (
            <Alert type={recoveryError ? 'warning' : 'info'} showIcon
              message={recoveryError || '正在恢复上次面试…'}
              action={activeId && <Button disabled={busy || coolingDown} onClick={() => void restoreSession()}>核对并恢复</Button>} />
          )}
          {coolingDown && <Alert type="info" showIcon message={`请在 ${new Date(retryAt).toLocaleTimeString()} 后重试。`} />}
          <InterviewSetup busy={busy || coolingDown} onStart={handleStart} />

          <section className={styles.historySection} aria-labelledby="interview-history-heading">
            <div className={styles.historyHeader}>
              <div>
                <h2 id="interview-history-heading">历史面试记录</h2>
                <p>回看每轮回答与反馈，未完成的面试可以从上次进度继续。</p>
              </div>
              <Space>
                <span className={styles.historyCount}>{historyTotal} 场记录</span>
                <Button icon={<ReloadOutlined />} loading={historyLoading} onClick={() => void fetchHistory()}>
                  刷新
                </Button>
              </Space>
            </div>

            <Pagination current={historyPage} total={historyTotal} pageSize={20} showSizeChanger={false} onChange={setHistoryPage} />
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
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="完成第一场模拟面试后，记录会保存在这里" />
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
                          <strong>{item.answeredCount}</strong><span> / {item.totalRounds} 轮回答</span>
                        </div>
                      </div>
                      <p className={styles.historyPreview}>{item.preview}</p>
                      <div className={styles.historyFooter}>
                        <time dateTime={item.updatedAt}><ClockCircleOutlined /> {formatDateTime(item.updatedAt)}</time>
                        <Space>
                          <Button
                            type="text"
                            danger
                            icon={<DeleteOutlined />}
                            onClick={() => handleDeleteHistory(item)}
                          >
                            删除
                          </Button>
                          <Button
                            type="primary"
                            ghost
                            icon={item.state === 'FINISHED' ? <EyeOutlined /> : <PlayCircleOutlined />}
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
          </section>
        </>
      ) : (
        <section className={styles.interviewLayout} aria-label="模拟面试进行区">
          <Card className={styles.interviewCard} bordered={false}>
            <div className={styles.sessionHeader}>
              <div className={styles.interviewerIdentity}>
                <div>
                  <span>当前面试</span>
                  <h2>{session.targetRole}</h2>
                </div>
              </div>
              <div className={styles.sessionState}>
                <span className={styles.status} data-pending={session.state !== 'FINISHED'}>
                  {session.state === 'FINISHED' ? '面试结束' : '面试进行中'}
                </span>
              </div>
            </div>
            <div className={styles.roundProgress} role="progressbar" aria-label="回答进度" aria-valuemin={0}
              aria-valuemax={session.totalRounds} aria-valuenow={session.turns.filter(turn => turn.speaker === 'user').length}>
              <div className={styles.progressTrack}><span style={{ width: `${Math.round((session.turns.filter(turn => turn.speaker === 'user').length / session.totalRounds) * 100)}%` }} /></div>
              <span>{session.turns.filter(turn => turn.speaker === 'user').length} / {session.totalRounds}</span>
            </div>

            <div className={styles.transcript} aria-live="polite">
              {session.turns.map((turn, index) => (
                <article className={speakerClass[turn.speaker]} key={`${turn.speaker}-${index}`}>
                  {renderSpeakerLabel(turn.speaker)}
                  <p>{turn.content}</p>
                </article>
              ))}
              {(sending || recovering || recoveryWaiting) && (
                <div className={styles.thinkingTurn}>
                  <Spin size="small" />
                  <span>Agent 正在评估回答证据并决定下一步…</span>
                </div>
              )}
              <div ref={messagesEndRef} />
            </div>

            {session.state === 'FINISHED' ? (
              <div className={styles.finishedBar}>
                <div><CheckCircleFilled /><span><strong>本轮练习已完成</strong>可以更换岗位或简历再练一次。</span></div>
                <Space wrap>{session.agent && <Button type="primary" onClick={() => document.getElementById('interview-agent-report')?.scrollIntoView({ behavior: 'smooth', block: 'start' })}>查看能力复盘</Button>}
                  <Button icon={<ReloadOutlined />} onClick={handleRestart}>再来一场</Button></Space>
              </div>
            ) : (
              <div className={styles.composer}>
                {(recoveryWaiting || recoveryError) && <Alert type={recoveryError ? 'warning' : 'info'} showIcon
                  message={recoveryError || '正在核对服务端回答状态，请稍候…'}
                  action={recoveryError ? <Button disabled={busy || coolingDown} onClick={() => void restoreSession()}>核对并恢复</Button> : undefined} />}
                {coolingDown && <Alert type="info" showIcon message={`请在 ${new Date(retryAt).toLocaleTimeString()} 后重试。`} />}
                <Input.TextArea
                  aria-label="面试回答"
                  placeholder="用 STAR 结构组织回答：情境、任务、行动、结果…"
                  value={inputValue}
                  onChange={(event) => controller.current?.setDraft(event.target.value)}
                  autoSize={{ minRows: 3, maxRows: 7 }}
                  showCount
                  maxLength={4000}
                  disabled={busy || needsRecovery}
                  onKeyDown={(event) => {
                    if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
                      event.preventDefault();
                      void handleSubmit();
                    }
                  }}
                />
                <div className={styles.composerFooter}>
                  <Typography.Text type="secondary">Enter 换行 · Ctrl/⌘ + Enter 发送</Typography.Text>
                  <Button
                    type="primary"
                    icon={<SendOutlined />}
                    loading={sending || recovering || recoveryWaiting}
                    disabled={!inputValue.trim() || busy || needsRecovery || coolingDown}
                    onClick={handleSubmit}
                  >
                    提交回答
                  </Button>
                </div>
              </div>
            )}
          </Card>

          <aside className={styles.sessionSidebar}>
            {session.agent && <AgentPlan agent={session.agent} finished={session.state === 'FINISHED'} />}
            <div className={styles.contextCard}>
              <span>本场依据</span>
              <h3>{session.mode === 'PROJECT_DEEP_DIVE' ? '项目深挖'
                : session.mode === 'BACKEND_BASICS' ? '后端基础' : '通用面试'}</h3>
              <p>{session.targetRole} · {difficultyOptions.find(option => option.value === (session.difficulty ?? 'STANDARD'))?.label}难度 · {session.totalRounds} 轮</p>
              {session.resumeId && <p>简历 #{session.resumeId} · 修订 {session.resumeRevision ?? '历史未记录'}</p>}
              {session.jobId && <p>岗位 #{session.jobId}</p>}
              {session.jobSnapshot && <details><summary>创建时的岗位 / JD</summary><p>{session.jobSnapshot}</p></details>}
              {session.resumeSnapshot && <details><summary>创建时的简历依据</summary><p>{session.resumeSnapshot}</p></details>}
            </div>
            <div className={styles.answerGuide}>
              <span>回答提示</span>
              <ul>
                <li>先讲清你面对的具体情境</li>
                <li>突出由你完成的关键动作</li>
                <li>用真实数据说明最终结果</li>
              </ul>
            </div>
          </aside>
          {session.state === 'FINISHED' && session.agent && <AgentReport agent={session.agent} />}
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
