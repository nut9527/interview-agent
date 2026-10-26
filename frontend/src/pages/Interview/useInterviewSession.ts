import { useEffect, useRef, useState } from 'react';
import { interviewService } from '@/api/modules/interview';
import { createSessionController, type SessionView } from './sessionController';

export const useInterviewSession = (userId: number, onChanged: () => void, entry?: { sessionId: string | null; preparing: boolean }) => {
  const initialEntry = useRef(entry);
  const changed = useRef(onChanged);
  changed.current = onChanged;
  const controller = useRef<ReturnType<typeof createSessionController> | null>(null);
  const [view, setView] = useState<SessionView>({
    server: null, draft: '',
    ui: { phase: 'idle', error: '', needsRecovery: false, retryAt: 0, activeId: null },
  });
  useEffect(() => {
    const current = createSessionController(userId, interviewService, () => changed.current());
    controller.current = current;
    const unsubscribe = current.subscribe(() => setView(current.getSnapshot()));
    setView(current.getSnapshot());
    if (initialEntry.current?.sessionId) void current.open(initialEntry.current.sessionId);
    else if (!initialEntry.current?.preparing) current.initialize();
    return () => { unsubscribe(); current.dispose(); controller.current = null; };
  }, [userId]);

  return { view, controller };
};
