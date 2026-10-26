import { onAccountReset } from '@/api/accountScope';
import { formValues, normalizeValues, type EditorValues } from './editorData';

const PREFIX = 'zhizhida:resume-draft:v1:';
export const DRAFT_TTL = 24 * 60 * 60 * 1000;
export type DraftKey = number | 'new' | 'import';
export interface ResumeDraft {
  schema: 1;
  userId: number;
  key: DraftKey;
  updatedAt: number;
  revision?: number;
  base: EditorValues;
  values: EditorValues;
  imported: boolean;
}
const keyFor = (userId: number, key: DraftKey) => `${PREFIX}${userId}:${key}`;

// Validate storage as untrusted input; unfinished forms are allowed, malformed shapes are not.
function validValues(value: unknown): value is EditorValues {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
  const v = value as Record<string, unknown>;
  const strings = (a: unknown) => Array.isArray(a) && a.every(x => typeof x === 'string');
  if (!['ownerName', 'targetRole', 'summary', 'templateKey', 'educationExperiences', 'projectExperiences', 'internshipExperiences']
    .every(k => typeof v[k] === 'string') || !strings(v.skills) || !strings(v.certificates)) return false;
  // The recursive whitelist comes from the normalized form shape (including dynamic rows).
  try {
    const normalized = normalizeValues(value as EditorValues);
    const walk = (item: unknown): boolean => typeof item === 'string'
      || Array.isArray(item) && item.every(walk)
      || !!item && typeof item === 'object' && Object.values(item).every(walk);
    return walk(normalized) && JSON.stringify(normalized) === JSON.stringify(value);
  } catch { return false; }
}

export function readDraft(userId: number, key: DraftKey, now = Date.now()): { draft: ResumeDraft | null; error?: string } {
  try {
    const raw = sessionStorage.getItem(keyFor(userId, key));
    if (!raw) return { draft: null };
    let value: ResumeDraft;
    try { value = JSON.parse(raw) as ResumeDraft; }
    catch {
      sessionStorage.removeItem(keyFor(userId, key));
      return { draft: null, error: '草稿已损坏，已忽略。' };
    }
    if (!value || raw.length > 1_000_000 || value.schema !== 1 || value.userId !== userId || value.key !== key
      || !Number.isFinite(value.updatedAt) || value.updatedAt > now || now - value.updatedAt > DRAFT_TTL
      || (typeof key === 'number' && (!Number.isSafeInteger(value.revision) || value.revision! < 0))
      || typeof value.imported !== 'boolean' || !validValues(value.values) || !validValues(value.base)) {
      sessionStorage.removeItem(keyFor(userId, key));
      return { draft: null, error: '草稿已过期或损坏，已忽略。' };
    }
    return { draft: value };
  } catch { return { draft: null, error: '无法读取本地草稿，请检查浏览器存储权限。' }; }
}

export function writeDraft(draft: ResumeDraft): boolean {
  try {
    const raw = JSON.stringify(draft);
    if (raw.length > 1_000_000) return false;
    sessionStorage.setItem(keyFor(draft.userId, draft.key), raw);
    return true;
  } catch { return false; }
}
export function removeDraft(userId: number, key: DraftKey): boolean {
  try { sessionStorage.removeItem(keyFor(userId, key)); return true; } catch { return false; }
}
export function stageImport(userId: number, values: EditorValues) {
  pruneExpiredDrafts();
  return writeDraft({ schema: 1, userId, key: 'import', updatedAt: Date.now(), base: formValues(), values, imported: true });
}

export function pruneExpiredDrafts(now = Date.now()) {
  try {
    for (let i = sessionStorage.length - 1; i >= 0; i--) {
      const key = sessionStorage.key(i);
      if (!key?.startsWith(PREFIX)) continue;
      let updatedAt: unknown;
      try { updatedAt = (JSON.parse(sessionStorage.getItem(key) ?? 'null') as ResumeDraft | null)?.updatedAt; } catch { /* Remove corrupt records below. */ }
      if (typeof updatedAt !== 'number' || !Number.isFinite(updatedAt) || updatedAt > now || now - updatedAt > DRAFT_TTL) sessionStorage.removeItem(key);
    }
  } catch { /* readDraft/writeDraft report availability to the editor. */ }
}

export function hasResumeDrafts(userId: number | undefined): boolean {
  if (userId === undefined) return false;
  pruneExpiredDrafts();
  try {
    for (let i = 0; i < sessionStorage.length; i++) {
      if (sessionStorage.key(i)?.startsWith(`${PREFIX}${userId}:`)) return true;
    }
  } catch { /* The active editor also tracks unsaved memory. */ }
  return false;
}

onAccountReset(() => {
  try {
    for (let i = sessionStorage.length - 1; i >= 0; i--) {
      const key = sessionStorage.key(i);
      if (key?.startsWith(PREFIX)) sessionStorage.removeItem(key);
    }
  } catch { /* Editor reports storage failures; account memory is cleared independently. */ }
});
