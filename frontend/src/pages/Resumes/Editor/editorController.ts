import type { ResumeProfile, ResumeProfileRequest } from '@/types/api.d';
import { captureAccountScope } from '../../../api/accountScope.ts';
import { ApiError, isRequestCanceled } from '../../../api/ApiError.ts';
import type { ResumeImportReview } from '@/api/modules/resume';
import {
  equal,
  formValues,
  normalizeValues,
  mergeChoices,
  toRequest,
  type Choices,
  type EditorValues,
} from './editorData.ts';
import {
  readDraft,
  writeDraft,
  removeDraft,
  pruneExpiredDrafts,
  type DraftKey,
  type ResumeDraft,
} from './resumeDraft.ts';

export interface EditorState {
  id: number | null;
  revision?: number;
  base: EditorValues;
  values: EditorValues;
  ready: boolean;
  loading: boolean;
  saving: boolean;
  dirty: boolean;
  imported: boolean;
  importReview: ResumeImportReview | null;
  recovery: ResumeDraft | null;
  conflict: ResumeProfile | null;
  error: string;
  storageError: string;
  savedAt: number | null;
}
export const emptyEditorState = (): EditorState => ({
  id: null,
  base: formValues(),
  values: formValues(),
  ready: false,
  loading: false,
  saving: false,
  dirty: false,
  imported: false,
  importReview: null,
  recovery: null,
  conflict: null,
  error: '',
  storageError: '',
  savedAt: null,
});

interface Dependencies {
  userId: number;
  key: DraftKey;
  freshImport?: boolean;
  get: (id: number, signal: AbortSignal) => Promise<ResumeProfile>;
  create: (data: ResumeProfileRequest, signal: AbortSignal) => Promise<ResumeProfile>;
  update: (id: number, data: ResumeProfileRequest, signal: AbortSignal) => Promise<ResumeProfile>;
  changed: (state: EditorState) => void;
  fill: (values: EditorValues) => void;
  created: (id: number) => void;
}

/** 一个编辑实例绑定一个账号作用域；保存使用读取时的 revision，冲突时保留输入并交由用户审阅。 */
export function createEditorController(deps: Dependencies) {
  let state = emptyEditorState();
  let key = deps.key;
  let disposed = false;
  let readSequence = 0;
  const scope = captureAccountScope();
  const requests = new AbortController();
  const current = () => !disposed && scope.isCurrent();
  const emit = (patch: Partial<EditorState>) => {
    if (!current()) return;
    state = { ...state, ...patch };
    deps.changed(state);
  };
  const persist = () => {
    if (!current() || !state.ready || state.recovery) return;
    const ok = state.dirty
      ? writeDraft({
          schema: 1,
          userId: deps.userId,
          key,
          revision: state.revision,
          updatedAt: Date.now(),
          base: state.base,
          values: state.values,
          imported: state.imported,
          importReview: state.importReview ?? undefined,
        })
      : removeDraft(deps.userId, key);
    const storageError = ok ? '' : '本地草稿未能保存或清除，请保持页面打开并尽快保存到服务器。';
    if (state.storageError !== storageError) emit({ storageError });
  };
  const replace = (values: EditorValues, patch: Partial<EditorState>) => {
    const review = patch.importReview === undefined ? state.importReview : patch.importReview;
    emit({ ...patch, values, dirty: !equal(values, patch.base ?? state.base) || !!review });
    deps.fill(structuredClone(values));
    persist();
  };
  const checkConflict = async () => {
    if (!state.id || !current()) return;
    const sequence = ++readSequence;
    emit({ loading: true });
    try {
      const latest = await deps.get(state.id, requests.signal);
      if (current() && sequence === readSequence) emit({ conflict: latest, error: '' });
    } catch (error) {
      if (current() && sequence === readSequence && !isRequestCanceled(error))
        emit({ error: '无法读取最新版，当前输入已保留。请重试核对。' });
    } finally {
      if (current() && sequence === readSequence) emit({ loading: false });
    }
  };
  return {
    isCurrent: current,
    getState: () => state,
    async load() {
      const sequence = ++readSequence;
      emit({ loading: true, error: '' });
      try {
        const saved = typeof key === 'number' ? await deps.get(key, requests.signal) : null;
        if (!current() || sequence !== readSequence) return;
        const base = formValues(saved ?? {});
        const { draft, error } = readDraft(deps.userId, key);
        pruneExpiredDrafts();
        // 无待确认的导入信息时，内容相同表示先前保存已完成，即使保存响应丢失。
        const recovery = draft && (!equal(draft.values, base) || draft.importReview) ? draft : null;
        emit({
          id: saved?.id ?? null,
          revision: saved?.revision ?? (saved ? 0 : undefined),
          base,
          ready: true,
          recovery,
          imported: key === 'import',
          importReview: null,
          storageError: error ?? '',
        });
        if (deps.freshImport && recovery && key === 'import') {
          replace(recovery.values, { recovery: null, imported: true, importReview: recovery.importReview ?? null });
        } else replace(base, {});
        if (error) emit({ storageError: error });
      } catch (error) {
        if (current() && sequence === readSequence && !isRequestCanceled(error))
          emit({ error: error instanceof Error ? error.message : '加载失败，请重试' });
      } finally {
        if (current() && sequence === readSequence) emit({ loading: false });
      }
    },
    edit(values: EditorValues) {
      if (!current() || !state.ready || state.recovery) return;
      const normalized = normalizeValues(values);
      if (equal(normalized, state.values)) return;
      emit({ values: structuredClone(normalized), dirty: !equal(normalized, state.base) || !!state.importReview });
      persist();
    },
    recover() {
      const draft = state.recovery;
      if (!current() || !draft) return;
      const server = state.id
        ? ({ ...toRequest(state.base), id: state.id, revision: state.revision } as ResumeProfile)
        : null;
      replace(draft.values, {
        recovery: null,
        base: draft.base,
        revision: draft.revision,
        imported: draft.imported,
        importReview: draft.importReview ?? null,
        conflict: server && server.revision !== draft.revision ? server : null,
      });
    },
    discardRecovery() {
      if (!current()) return;
      emit({ recovery: null });
      persist();
    },
    checkConflict,
    resolve(choices: Choices) {
      if (!current() || !state.conflict || state.saving) return;
      // 选择仅更新本地基线；再次保存仍携带最新版 revision，后续并发修改会再次触发冲突。
      const base = formValues(state.conflict);
      const merged = mergeChoices(state.base, state.values, base, choices);
      replace(merged, { base, revision: state.conflict.revision ?? 0, conflict: null, error: '' });
    },
    async save() {
      if (!current() || !state.ready || state.saving || state.loading || state.recovery || state.conflict) return;
      // 在任何 await 之前同步加锁，防止重复提交。
      emit({ saving: true, error: '' });
      const submitted = structuredClone(state.values);
      try {
        const saved = state.id
          ? await deps.update(state.id, { ...toRequest(submitted), revision: state.revision }, requests.signal)
          : await deps.create(toRequest(submitted), requests.signal);
        if (!current()) return;
        const oldKey = key;
        const created = !state.id;
        key = saved.id;
        // 基线使用实际提交的表单，后续输入仍保留为未保存改动，包括空白。
        emit({
          id: saved.id,
          revision: saved.revision ?? 0,
          base: submitted,
          dirty: !equal(state.values, submitted),
          imported: false,
          importReview: null,
          savedAt: Date.now(),
        });
        if (oldKey !== key) removeDraft(deps.userId, oldKey);
        persist();
        if (created) deps.created(saved.id);
      } catch (error) {
        if (!current() || isRequestCanceled(error)) return;
        if (error instanceof ApiError && error.status === 409 && state.id) await checkConflict();
        else
          emit({
            error: `${error instanceof Error ? error.message : '保存失败'}。输入已保留。${state.id ? '' : '若网络中断，请先检查简历列表是否已创建，避免重复保存。'}`,
          });
      } finally {
        if (current()) emit({ saving: false });
      }
    },
    // 直接采用恢复响应中的快照和 revision，避免额外读取造成版本与内容不一致。
    restored(saved: ResumeProfile) {
      if (!current()) return;
      replace(formValues(saved), {
        id: saved.id,
        base: formValues(saved),
        revision: saved.revision ?? 0,
        conflict: null,
        error: '',
        savedAt: Date.now(),
      });
    },
    dispose() {
      disposed = true;
      readSequence++;
      requests.abort();
    },
  };
}
