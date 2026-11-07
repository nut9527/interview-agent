import type { DraftOptimizationEntry, DraftOptimizationRequest, DraftOptimizationResponse } from '@/types/api.d';
import type { EditorValues } from '@/pages/Resumes/Editor/editorData';
import { captureAccountScope, onAccountReset } from '../../api/accountScope.ts';
import { undoText } from './textReview.ts';

interface DraftField {
  entry: DraftOptimizationEntry;
  label: string;
  group: 'summary' | 'skills' | 'projectExperiences' | 'internshipExperiences' | 'projects';
  write: (values: EditorValues, text: string) => void;
}

function draftFields(values: EditorValues): DraftField[] {
  const fields: DraftField[] = [];
  const add = (entry: DraftOptimizationEntry, label: string, group: DraftField['group'], write: DraftField['write']) => {
    if (entry.text.trim()) fields.push({ entry, label, group, write });
  };
  add({ kind: 'summary', text: values.summary }, '个人简介', 'summary', (v, text) => { v.summary = text; });
  values.skills.forEach((text, i) => add({ kind: 'skill', text }, `技能 ${i + 1}`, 'skills',
    (v, next) => { v.skills[i] = next; }));
  const addLines = (group: 'projectExperiences' | 'internshipExperiences', kind: 'projectExperience' | 'internshipExperience', label: string) => {
    values[group].split('\n').forEach((text, i) => add({ kind, text }, `${label} · 第 ${i + 1} 行`, group, (v, next) => {
      const lines = v[group].split('\n');
      lines[i] = next;
      v[group] = lines.join('\n');
    }));
  };
  addLines('projectExperiences', 'projectExperience', '项目经历');
  values.details.projects.forEach((project, i) => {
    const projectName = project.name.trim() || undefined;
    const label = projectName || `项目 ${i + 1}`;
    add({ kind: 'projectBackground', text: project.background ?? '', projectName }, `${label} · 项目背景`, 'projects',
      (v, text) => { v.details.projects[i].background = text; });
    project.contributions.forEach((text, j) =>
      add({ kind: 'projectContribution', text, projectName }, `${label} · 贡献 ${j + 1}`, 'projects',
        (v, next) => { v.details.projects[i].contributions[j] = next; }));
  });
  addLines('internshipExperiences', 'internshipExperience', '实习 / 工作经历');
  return fields;
}

const limits: Record<DraftOptimizationEntry['kind'], number> = {
  summary: 4000, skill: 80, projectExperience: 3000, projectBackground: 2000, projectContribution: 3000, internshipExperience: 3000,
};

function validateInput(fields: DraftField[], role: string) {
  if (!fields.length) return '请先填写个人简介、技能或经历，再生成建议。';
  if (role.length > 120) return '目标岗位最多 120 字，请缩短后重试。';
  let total = role.length;
  for (const { entry, label } of fields) {
    if (entry.text.length > limits[entry.kind]) return `${label}最多 ${limits[entry.kind]} 字，请缩短后重试。`;
    if ((entry.projectName?.length ?? 0) > 120) return '项目名称最多 120 字，请缩短后重试。';
    total += entry.text.length + (entry.projectName?.length ?? 0);
  }
  if (fields.length > 100 || total > 16000) return '一次优化最多 100 项、合计 16000 字，请精简后重试。';
  return '';
}

export interface SuggestedEdit {
  index: number;
  label: string;
  before: string;
  after: string;
  selected: boolean;
  accepted: boolean;
}

function reviewChanges(response: DraftOptimizationResponse, fields: DraftField[]): SuggestedEdit[] {
  if (!Array.isArray(response?.changes) || response.changes.length > fields.length) throw new Error('优化结果格式有误，请重新生成。');
  const seen = new Set<number>();
  const edits: SuggestedEdit[] = [];
  for (const change of response.changes) {
    if (!change || !Number.isInteger(change.index) || !fields[change.index] || seen.has(change.index)) {
      throw new Error('优化结果中的条目无法与原文对应，请重新生成。');
    }
    const { entry, label } = fields[change.index];
    if (typeof change.text !== 'string' || !change.text.trim() || change.text.length > limits[entry.kind]
      || (!['summary', 'projectBackground'].includes(entry.kind) && /[\r\n]/.test(change.text))) {
      throw new Error(`${label}的建议超出字段限制，请重新生成。`);
    }
    seen.add(change.index);
    if (change.text !== entry.text) edits.push({
      index: change.index, label, before: entry.text, after: change.text, selected: false, accepted: false,
    });
  }
  return edits.sort((a, b) => a.index - b.index);
}

// 保留空白行、项目顺序和项目身份，避免把旧建议写入移位后的条目。
function fingerprint(values: EditorValues) {
  return JSON.stringify([values.targetRole, values.summary, values.skills, values.projectExperiences,
    values.internshipExperiences, values.details.projects]);
}

function groupValue(values: EditorValues, group: DraftField['group']) {
  return group === 'projects' ? values.details.projects : values[group];
}

export interface OptimizationState {
  loading: boolean;
  edits: SuggestedEdit[] | null;
  canUndo: boolean;
  error: string;
  notice: string;
}
export const emptyOptimizationState: OptimizationState = { loading: false, edits: null, canUndo: false, error: '', notice: '' };

interface Options {
  read: () => EditorValues;
  write: (values: EditorValues) => void;
  optimize: (request: DraftOptimizationRequest, signal: AbortSignal) => Promise<DraftOptimizationResponse>;
  changed: (state: OptimizationState) => void;
}

export function createOptimizationController(options: Options) {
  let state = emptyOptimizationState;
  let request: AbortController | undefined;
  let disposed = false;
  let baseline = '';
  let fields: DraftField[] = [];
  let adoption: { values: EditorValues; fields: DraftField[]; edits: SuggestedEdit[] } | null = null;
  const scope = captureAccountScope();
  const active = () => !disposed && scope.isCurrent();
  const update = (patch: Partial<OptimizationState>) => {
    if (active()) {
      state = { ...state, ...patch, canUndo: !!adoption };
      options.changed(state);
    }
  };
  const abort = () => { request?.abort(); request = undefined; };
  const unsubscribe = onAccountReset(abort);
  const stale = () => state.edits !== null && fingerprint(options.read()) !== baseline;

  return {
    getSnapshot: () => state,
    stale,
    async generate() {
      if (!active()) return;
      abort();
      const values = options.read();
      const snapshot = fingerprint(values);
      const entries = draftFields(values);
      const targetRole = values.targetRole.trim();
      const error = validateInput(entries, targetRole);
      if (error) { update({ loading: false, edits: null, error, notice: '' }); return; }
      const pending = new AbortController();
      request = pending;
      update({ loading: true, edits: null, error: '', notice: '' });
      try {
        const response = await options.optimize({ targetRole, entries: entries.map(field => field.entry) }, pending.signal);
        if (!active() || request !== pending) return;
        const edits = reviewChanges(response, entries);
        fields = entries;
        baseline = snapshot;
        update({ loading: false, edits, notice: edits.length ? '' : '当前表述无需修改。' });
      } catch (cause) {
        if (request === pending) update({ loading: false, error: cause instanceof Error ? cause.message : '生成失败，请重试。' });
      } finally {
        if (request === pending) request = undefined;
      }
    },
    cancel() { abort(); update({ loading: false }); },
    select(index: number, selected: boolean) {
      if (!state.edits || state.loading) return;
      update({ edits: state.edits.map(edit => edit.index === index && !edit.accepted ? { ...edit, selected } : edit) });
    },
    apply() {
      if (!active() || !state.edits || state.loading) return;
      const chosen = state.edits.filter(edit => edit.selected && !edit.accepted);
      if (!chosen.length) return;
      if (stale()) { update({ error: '简历内容已修改，请重新生成建议。' }); return; }
      const values = structuredClone(options.read());
      for (const edit of chosen) fields[edit.index].write(values, edit.after);
      options.write(values);
      adoption = { values: structuredClone(values), fields, edits: chosen };
      baseline = fingerprint(values);
      update({ edits: state.edits.map(edit => edit.selected ? { ...edit, selected: false, accepted: true } : edit),
        error: '', notice: `已将 ${chosen.length} 项修改写入草稿，请在编辑页保存。` });
    },
    undo() {
      if (!active() || !adoption || state.loading) return;
      const currentValues = options.read();
      const values = structuredClone(currentValues);
      for (const edit of adoption.edits) {
        const field = adoption.fields[edit.index];
        let restored: string | null = edit.before;
        if (field.group === 'summary') {
          restored = undoText(edit.before, edit.after, currentValues.summary);
        } else if (JSON.stringify(groupValue(currentValues, field.group)) !== JSON.stringify(groupValue(adoption.values, field.group))) {
          restored = null;
        }
        if (restored === null) { update({ error: '采用后该段内容已更改，无法安全撤销。请手动核对。' }); return; }
        field.write(values, restored);
      }
      const currentReview = fields === adoption.fields && !stale();
      const indices = new Set(adoption.edits.map(edit => edit.index));
      options.write(values);
      adoption = null;
      baseline = fingerprint(values);
      update({ edits: currentReview && state.edits ? state.edits.map(edit =>
        indices.has(edit.index) ? { ...edit, accepted: false, selected: false } : edit) : null,
      error: '', notice: '已撤销上次采用的修改。' });
    },
    dispose() { disposed = true; abort(); unsubscribe(); },
  };
}
