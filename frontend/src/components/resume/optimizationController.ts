import type { OptimizeResumeRequest, OptimizeResumeResponse } from '@/types/api.d';
import { captureAccountScope, onAccountReset } from '../../api/accountScope.ts';
import { applyTextEdits, buildTextEdits, undoText } from './textReview.ts';
import type { TextEdit } from './textReview.ts';

export interface ReviewedEdit extends TextEdit {
  status: 'pending' | 'accepted' | 'rejected';
  selected: boolean;
}
export interface OptimizationReview {
  id: number;
  source: string;
  targetRole: string;
  response: OptimizeResumeResponse;
  edits: ReviewedEdit[];
}
export interface OptimizationState {
  loading: boolean;
  review: OptimizationReview | null;
  undoCount: number;
  error: string;
  notice: string;
}
export const emptyOptimizationState: OptimizationState = {
  loading: false, review: null, undoCount: 0, error: '', notice: '',
};
export function expectedReviewText(review: OptimizationReview) {
  return applyTextEdits(review.source, review.edits.filter(edit => edit.status === 'accepted'));
}

interface Options {
  read: () => string;
  write: (text: string) => void;
  targetRole: () => string;
  validate?: (text: string) => string | null;
  optimize: (data: OptimizeResumeRequest, signal: AbortSignal) => Promise<OptimizeResumeResponse>;
  changed: (state: OptimizationState) => void;
}

/** One controller per stable form field. No model response writes form values. */
export function createOptimizationController(options: Options) {
  let state = emptyOptimizationState;
  let disposed = false, requestId = 0;
  let request: AbortController | undefined;
  const scope = captureAccountScope();
  const history: Array<{ before: string; after: string; reviewId: number; editIds: number[] }> = [];
  const active = () => !disposed && scope.isCurrent();
  const update = (patch: Partial<OptimizationState>) => {
    if (!active()) return;
    state = { ...state, ...patch, undoCount: history.length };
    options.changed(state);
  };
  const invalidateRequest = () => { requestId++; request?.abort(); request = undefined; };
  const unsubscribe = onAccountReset(() => { disposed = true; invalidateRequest(); });

  return {
    getSnapshot: () => state,
    async generate() {
      if (!active()) return;
      const source = options.read(), targetRole = options.targetRole();
      if (!source.trim() || !targetRole.trim()) {
        update({ error: '请先填写待优化文字和目标岗位。' }); return;
      }
      if (source.length > 8000 || targetRole.length > 120) {
        update({ error: '待优化文字最多 8000 字，目标岗位最多 120 字，请缩短后重试。' }); return;
      }
      invalidateRequest();
      const id = requestId;
      request = new AbortController();
      update({ loading: true, error: '', notice: '' });
      try {
        const response = await options.optimize({ rawText: source, targetRole }, request.signal);
        if (!active() || id !== requestId) return;
        if (typeof response.optimizedText !== 'string' || !response.optimizedText.trim() || response.optimizedText.length > 12000) {
          throw new Error('优化结果格式不正确，请重试。');
        }
        const edits = buildTextEdits(source, response.optimizedText)
          .map(edit => ({ ...edit, status: 'pending' as const, selected: false }));
        update({ loading: false, review: { id, source, targetRole, response, edits },
          notice: edits.length ? '建议已生成。请选择要采用的修改，原文尚未改动。' : '优化结果与原文相同，无需采用。' });
      } catch (error) {
        if (active() && id === requestId) update({ loading: false, error: error instanceof Error ? error.message : 'AI 优化失败，请重试。' });
      }
    },
    select(id: number, selected: boolean) {
      if (!active() || !state.review || state.loading) return;
      update({ review: { ...state.review, edits: state.review.edits.map(edit =>
        edit.id === id && edit.status === 'pending' ? { ...edit, selected } : edit) } });
    },
    reject(ids?: number[]) {
      if (!active()) return;
      // Rejecting all also cancels an in-flight generation and fences its late response.
      if (!ids) invalidateRequest();
      if (!state.review) { update({ loading: false, error: '', notice: '已取消生成，原文保持不变。' }); return; }
      update({ loading: ids ? state.loading : false, error: '', notice: '已拒绝建议，当前文字保持不变。',
        review: { ...state.review, edits: state.review.edits.map(edit =>
          edit.status === 'pending' && (!ids || ids.includes(edit.id)) ? { ...edit, status: 'rejected', selected: false } : edit) } });
    },
    apply() {
      if (!active() || state.loading || !state.review) return;
      const review = state.review;
      const ids = review.edits.filter(edit => edit.status === 'pending' && edit.selected).map(edit => edit.id);
      if (!ids.length) return;
      const before = options.read();
      if (before !== expectedReviewText(review) || options.targetRole() !== review.targetRole) {
        update({ error: '文字或目标岗位已修改，请基于当前内容重新生成建议。你的输入已保留。' }); return;
      }
      const edits = review.edits.map(edit => ids.includes(edit.id) ? { ...edit, status: 'accepted' as const, selected: false } : edit);
      const after = expectedReviewText({ ...review, edits });
      const error = options.validate?.(after);
      if (error) { update({ error }); return; }
      options.write(after);
      history.push({ before, after, reviewId: review.id, editIds: ids });
      update({ review: { ...review, edits }, error: '', notice: `已采用 ${ids.length} 处修改，可撤销；点击页面“保存”后生效。` });
    },
    undo() {
      if (!active() || state.loading) return;
      const last = history[history.length - 1];
      if (!last) return;
      const result = undoText(last.before, last.after, options.read());
      if (result === null) {
        update({ error: '你已编辑采用的片段，无法安全自动撤销。当前文字已保留，请对照原文手动调整。' }); return;
      }
      const error = options.validate?.(result);
      if (error) { update({ error }); return; }
      options.write(result);
      history.pop();
      const review = state.review?.id === last.reviewId ? { ...state.review, edits: state.review.edits.map(edit =>
        last.editIds.includes(edit.id) ? { ...edit, status: 'pending' as const, selected: false } : edit) } : null;
      update({ review, error: '', notice: '已撤销上次采用，保留了修改片段之外的新输入；请保存简历。' });
    },
    dispose() { disposed = true; invalidateRequest(); unsubscribe(); },
  };
}
