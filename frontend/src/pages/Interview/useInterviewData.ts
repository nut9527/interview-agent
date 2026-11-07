import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { interviewService } from '@/api/modules/interview';
import { captureAccountScope } from '@/api/accountScope';
import { describeApiError, isRequestCanceled } from '@/api/ApiError';
import type { InterviewHistoryItem } from '@/types/api.d';

export const useInterviewData = () => {
  const account = useMemo(() => captureAccountScope(), []);
  const [history, setHistory] = useState<InterviewHistoryItem[]>([]);
  const [historyPage, setHistoryPage] = useState(1);
  const [historyTotal, setHistoryTotal] = useState(0);
  const [historyLoading, setHistoryLoading] = useState(true);
  const [historyError, setHistoryError] = useState('');
  const historyRequest = useRef<AbortController | null>(null);
  const lifetime = useRef<AbortController | null>(null);

  useEffect(() => {
    const request = new AbortController();
    lifetime.current = request;
    return () => { request.abort(); historyRequest.current?.abort(); };
  }, [account]);

  const fetchHistory = useCallback(async () => {
    if (lifetime.current?.signal.aborted || !account.isCurrent()) return;
    historyRequest.current?.abort();
    const request = new AbortController();
    historyRequest.current = request;
    const active = () => !request.signal.aborted && account.isCurrent();
    setHistoryLoading(true);
    setHistoryError('');
    try {
      const result = await interviewService.getSessions(historyPage, request.signal);
      if (!active()) return;
      setHistory(result.items);
      setHistoryTotal(result.total);
      if (!result.items.length && historyPage > 1) setHistoryPage(historyPage - 1);
    } catch (error) {
      if (active() && !isRequestCanceled(error)) setHistoryError(describeApiError(error));
    } finally {
      if (active()) setHistoryLoading(false);
    }
  }, [historyPage, account]);

  useEffect(() => {
    void fetchHistory();
    return () => historyRequest.current?.abort();
  }, [fetchHistory]);

  const refresh = useCallback(() => {
    void fetchHistory();
  }, [fetchHistory]);

  const deleteSession = async (id: string) => {
    const signal = lifetime.current?.signal;
    if (!signal || signal.aborted || !account.isCurrent()) return false;
    await interviewService.deleteSession(id, signal);
    if (signal.aborted || !account.isCurrent()) return false;
    refresh();
    return true;
  };

  return {
    history, historyPage, setHistoryPage, historyTotal, historyLoading, historyError,
    fetchHistory, refresh, deleteSession,
  };
};
