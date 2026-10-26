import type { ResumeProfile, ResumeProfileRequest } from '@/types/api.d';
import { captureAccountScope } from '@/api/accountScope';
import { ApiError, isRequestCanceled } from '@/api/ApiError';
import { equal, formValues, normalizeValues, mergeChoices, toRequest, type Choices, type EditorValues } from './editorData';
import { readDraft, writeDraft, removeDraft, pruneExpiredDrafts, type DraftKey, type ResumeDraft } from './resumeDraft';

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
  recovery: ResumeDraft | null;
  conflict: ResumeProfile | null;
  error: string;
  storageError: string;
  savedAt: number | null;
}
export const emptyEditorState = (): EditorState => ({ id: null, base: formValues(), values: formValues(),
  ready: false, loading: false, saving: false, dirty: false, imported: false, recovery: null, conflict: null,
  error: '', storageError: '', savedAt: null });

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
    const ok = state.dirty ? writeDraft({ schema: 1, userId: deps.userId, key,
      revision: state.revision, updatedAt: Date.now(), base: state.base, values: state.values, imported: state.imported })
      : removeDraft(deps.userId, key);
    const storageError = ok ? '' : '本地草稿未能保存或清除，请保持页面打开并尽快保存到服务器。';
    if (state.storageError !== storageError) emit({ storageError });
  };
  const replace = (values: EditorValues, patch: Partial<EditorState>) => {
    emit({ ...patch, values, dirty: !equal(values, patch.base ?? state.base) });
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
      if (current() && sequence === readSequence && !isRequestCanceled(error)) emit({ error: '无法读取最新版，当前输入已保留。请重试核对。' });
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
        // Equal content means a previous save completed, even if its response was lost.
        const recovery = draft && !equal(draft.values, base) ? draft : null;
        emit({ id: saved?.id ?? null, revision: saved?.revision ?? (saved ? 0 : undefined),
          base, ready: true, recovery, imported: key === 'import', storageError: error ?? '' });
        if (deps.freshImport && recovery && key === 'import') {
          replace(recovery.values, { recovery: null, imported: true });
        } else replace(base, {});
        if (error) emit({ storageError: error });
      } catch (error) {
        if (current() && sequence === readSequence && !isRequestCanceled(error)) emit({ error: error instanceof Error ? error.message : '加载失败，请重试' });
      } finally {
        if (current() && sequence === readSequence) emit({ loading: false });
      }
    },
    edit(values: EditorValues) {
      if (!current() || !state.ready || state.recovery) return;
      const normalized = normalizeValues(values);
      if (equal(normalized, state.values)) return;
      emit({ values: structuredClone(normalized), dirty: !equal(normalized, state.base) });
      persist();
    },
    recover() {
      const draft = state.recovery;
      if (!current() || !draft) return;
      const server = state.id ? { ...toRequest(state.base), id: state.id, revision: state.revision } as ResumeProfile : null;
      replace(draft.values, { recovery: null, base: draft.base, revision: draft.revision, imported: draft.imported,
        conflict: server && server.revision !== draft.revision ? server : null });
    },
    discardRecovery() {
      if (!current()) return;
      emit({ recovery: null });
      persist();
    },
    checkConflict,
    resolve(choices: Choices) {
      if (!current() || !state.conflict || state.saving) return;
      const base = formValues(state.conflict);
      const merged = mergeChoices(state.base, state.values, base, choices);
      replace(merged, { base, revision: state.conflict.revision ?? 0, conflict: null, error: '' });
    },
    async save() {
      if (!current() || !state.ready || state.saving || state.loading || state.recovery || state.conflict) return;
      // Lock synchronously, before any await, to prevent double submits.
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
        // The baseline is the submitted form, so later typing stays dirty, including whitespace.
        emit({ id: saved.id, revision: saved.revision ?? 0, base: submitted,
          dirty: !equal(state.values, submitted), imported: false, savedAt: Date.now() });
        if (oldKey !== key) removeDraft(deps.userId, oldKey);
        persist();
        if (created) deps.created(saved.id);
      } catch (error) {
        if (!current() || isRequestCanceled(error)) return;
        if (error instanceof ApiError && error.status === 409 && state.id) await checkConflict();
        else emit({ error: `${error instanceof Error ? error.message : '保存失败'}。输入已保留。${state.id ? '' : '若网络中断，请先检查简历列表是否已创建，避免重复保存。'}` });
      } finally {
        if (current()) emit({ saving: false });
      }
    },
    // Restore responses also carry a revision. Never refetch and overwrite intervening edits.
    restored(saved: ResumeProfile) {
      if (!current()) return;
      replace(formValues(saved), { id: saved.id, base: formValues(saved), revision: saved.revision ?? 0,
        conflict: null, error: '', savedAt: Date.now() });
    },
    dispose() { disposed = true; readSequence++; requests.abort(); },
  };
}
