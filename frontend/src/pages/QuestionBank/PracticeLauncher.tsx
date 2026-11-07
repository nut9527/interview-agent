import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Modal, Select, Space } from 'antd';
import { useNavigate } from 'react-router-dom';
import { interviewService } from '@/api/modules/interview';
import { captureAccountScope } from '@/api/accountScope';
import { isRequestCanceled } from '@/api/ApiError';
import { difficultyOptions } from '@/pages/Interview/interviewSettings';
import type { InterviewDifficulty } from '@/types/api';
import { useAuthStore } from '@/stores/authStore';

export default function PracticeLauncher({
  question,
  rounds: initialRounds = 3,
  retry,
  group,
}: {
  question: { id: string; title: string; version: number; difficulty: InterviewDifficulty };
  rounds?: number;
  retry?: { sessionId: string; round: number };
  group?: string;
}) {
  const navigate = useNavigate();
  const userId = useAuthStore((state) => state.user?.id);
  const [open, setOpen] = useState(false);
  const [rounds, setRounds] = useState(initialRounds);
  const [difficulty, setDifficulty] = useState(question.difficulty);
  const [experienceMode, setExperienceMode] = useState<'TEACHING' | 'FORMAL'>('TEACHING');
  const [timeLimitMinutes, setTimeLimitMinutes] = useState<number | undefined>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const request = useRef<AbortController>();
  useEffect(() => () => request.current?.abort(), []);
  const start = async () => {
    if (request.current || userId === undefined) return;
    const current = new AbortController();
    request.current = current;
    const scope = captureAccountScope();
    setBusy(true);
    setError('');
    try {
      // 标签页只保留账号、题目和设置对应的幂等键，刷新后重试也能找回同一场练习。
      const practiceKey =
        'interview:practice-start:' +
        JSON.stringify([
          userId,
          question.id,
          question.version,
          rounds,
          difficulty,
          experienceMode,
          timeLimitMinutes,
          retry?.sessionId,
          retry?.round,
        ]);
      const session = await interviewService.startSession(
        {
          targetRole: question.title,
          mode: 'BACKEND_BASICS',
          totalRounds: rounds,
          difficulty,
          experienceMode,
          timeLimitMinutes,
          ...(retry
            ? { retrySessionId: retry.sessionId, retryRound: retry.round }
            : { questionId: question.id, questionVersion: question.version }),
        },
        current.signal,
        practiceKey,
      );
      if (scope.isCurrent() && !current.signal.aborted) {
        const params = new URLSearchParams({ sessionId: session.sessionId });
        if (group) params.set('questionGroup', group);
        navigate('/interview?' + params);
      }
    } catch (reason) {
      if (scope.isCurrent() && !current.signal.aborted && !isRequestCanceled(reason))
        setError(reason instanceof Error ? reason.message : '创建练习失败');
    } finally {
      if (scope.isCurrent() && !current.signal.aborted) setBusy(false);
      if (request.current === current) request.current = undefined;
    }
  };
  return (
    <>
      <Button onClick={() => setOpen(true)}>{retry ? '同题复练' : '练习这道题'}</Button>
      <Modal
        title={retry ? '同题复练' : '练习这道题'}
        open={open}
        onCancel={() => {
          if (!busy) setOpen(false);
        }}
        closable={!busy}
        maskClosable={!busy}
        okText="进入训练室"
        cancelText="取消"
        confirmLoading={busy}
        onOk={() => void start()}
      >
        <p>
          {question.title} · 版本 {question.version}
        </p>
        <p>首问使用原题，后续根据回答追问。每题独立会话，至少保留两轮深入验证。</p>
        <Space wrap>
          <label>
            练习难度{' '}
            <Select
              aria-label="练习难度"
              value={difficulty}
              disabled={busy || !!retry}
              onChange={setDifficulty}
              options={difficultyOptions.map((d) => ({ value: d.value, label: d.label }))}
            />
          </label>
          <label>
            每题轮数{' '}
            <Select
              aria-label="每题轮数"
              value={rounds}
              disabled={busy}
              onChange={setRounds}
              options={[3, 4, 5, 6].map((value) => ({ value, label: value + ' 轮' }))}
            />
          </label>
          <label>
            反馈方式{' '}
            <Select
              aria-label="选题反馈模式"
              value={experienceMode}
              disabled={busy}
              onChange={setExperienceMode}
              options={[
                { value: 'TEACHING', label: '教学练习' },
                { value: 'FORMAL', label: '正式模拟' },
              ]}
            />
          </label>
          <label>
            整场计时{' '}
            <Select
              aria-label="选题计时"
              value={timeLimitMinutes ?? 0}
              disabled={busy}
              onChange={(v) => setTimeLimitMinutes(v || undefined)}
              options={[
                { value: 0, label: '不计时' },
                ...[10, 20, 30, 45, 60].map((v) => ({ value: v, label: v + ' 分钟' })),
              ]}
            />
          </label>
        </Space>
        <p>教学练习逐轮反馈；正式模拟结束后统一复盘。计时离开页面后仍继续，到时提示并允许继续作答。</p>
        {retry && <p>沿用原题版本、难度及回答缺口，便于前后对照。</p>}
        {error && <Alert type="error" message={error} description="设置已保留。如题目已修订，请关闭窗口并刷新题目。" />}
      </Modal>
    </>
  );
}
