import { useState, useEffect, useRef } from 'react';
import { Form, Button, Skeleton, Modal, message, Typography, Alert, Space, Popconfirm } from 'antd';
import { ArrowLeftOutlined, EyeOutlined, SaveOutlined, HistoryOutlined } from '@ant-design/icons';
import { useBlocker, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { resumeService } from '@/api/modules/resume';
import { captureAccountScope } from '@/api/accountScope';
import { useAuthStore } from '@/stores/authStore';
import { useResumeStore } from '@/stores/resumeStore';
import VersionHistoryDrawer from './components/VersionHistoryDrawer';
import ResumeEditorFields, { resumeSections } from '@/components/resume/ResumeEditorFields';
import ResumeOptimizer from '@/components/resume/AiTextOptimizer';
import styles from './index.module.css';
import ConflictReview from './components/ConflictReview';
import { createEditorController, emptyEditorState } from './editorController';
import { formValues, normalizeValues } from './editorData';
import type { DraftKey } from './resumeDraft';
import ResumePreviewModal from '@/components/resume/ResumePreviewModal';
import { toRequest } from './editorData';
import type { ResumeProfile } from '@/types/api.d';
import { useResumeEditorStatus } from '@/stores/resumeEditorStatus';
import ResumeImportReview from '@/components/resume/ResumeImportReview';
import { importWarningTarget } from '@/components/resume/importReview';

function ResumeEditor({ resumeId, draftKey, freshImport, onCreated }: {
  resumeId: number | null; draftKey: DraftKey; freshImport: boolean; onCreated: (id: number) => void;
}) {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const [form] = Form.useForm();
  const watched = Form.useWatch([], { form, preserve: true });
  const [state, setState] = useState(emptyEditorState);
  const [previewOpen, setPreviewOpen] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [reviewOpen, setReviewOpen] = useState(false);
  const [formEpoch, setFormEpoch] = useState(0);
  const controller = useRef<ReturnType<typeof createEditorController>>();
  const allowTransition = useRef(false);
  useEffect(() => { allowTransition.current = false; }, [params]);
  const callbacks = useRef({ onCreated, navigate });
  callbacks.current = { onCreated, navigate };
  const userId = useAuthStore(s => s.user?.id);
  // 创建后更新 URL，同时保留当前编辑器和控制器实例。
  const initial = useRef({ draftKey, freshImport });
  useEffect(() => {
    if (userId === undefined) return;
    const instance = createEditorController({ userId, key: initial.current.draftKey, freshImport: initial.current.freshImport,
      get: resumeService.getOne, create: resumeService.create, update: resumeService.update,
      changed: next => { setState(next); useResumeEditorStatus.setState({ unsaved: next.dirty || next.saving || !!next.recovery }); },
      fill: values => { form.resetFields(); form.setFieldsValue(values); setFormEpoch(epoch => epoch + 1); },
      created: id => {
        allowTransition.current = true;
        callbacks.current.onCreated(id);
        callbacks.current.navigate(`/resumes/edit?resumeId=${id}`, { replace: true });
        void useResumeStore.getState().fetchResumes().catch(() => undefined);
      },
    });
    controller.current = instance;
    void instance.load();
    return () => { instance.dispose(); useResumeEditorStatus.setState({ unsaved: false }); };
  }, [form, userId]);
  useEffect(() => { allowTransition.current = false; }, [resumeId]);
  useEffect(() => { controller.current?.edit(normalizeValues(form.getFieldsValue(true))); }, [watched, form]);
  useEffect(() => { if (state.conflict) setReviewOpen(true); }, [state.conflict]);

  const blocker = useBlocker(({ currentLocation, nextLocation }) => !allowTransition.current
    && (state.dirty || state.saving) && (currentLocation.pathname !== nextLocation.pathname || currentLocation.search !== nextLocation.search));
  useEffect(() => {
    if (!state.dirty && !state.saving) return;
    const warn = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = ''; };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [state.dirty, state.saving]);

  const handleSubmit = async () => {
    const instance = controller.current;
    if (!instance || instance.getState().saving) return;
    const scope = captureAccountScope();
    try {
      await form.validateFields();
      if (!scope.isCurrent() || controller.current !== instance || !instance.isCurrent()) return;
      instance.edit(normalizeValues(form.getFieldsValue(true)));
      await instance.save();
    } catch (error) {
      if (scope.isCurrent() && error instanceof Error) message.error(error.message);
      // 字段校验错误由 Ant Design 在表单内展示。
    }
  };
  const locked = !state.ready || !!state.recovery || drawerOpen || reviewOpen;
  const status = state.saving ? '正在保存提交时的内容…'
    : state.conflict ? '保存冲突 · 等待比较与选择'
    : state.error ? '保存或读取失败 · 请核对后重试'
    : state.dirty ? '有未保存的修改' : state.ready ? state.id ? '当前内容已与服务器同步' : '等待填写并保存' : '尚未载入';

  return <div className={styles.page}>
    <Button className={styles.back} type="text" icon={<ArrowLeftOutlined aria-hidden="true" />} onClick={() => navigate('/resumes')}>返回</Button>
    <header className={styles.header}>
      <h1>{state.imported ? '检查导入内容' : state.id ? '编辑简历' : '新建简历'}</h1>
      <div className={styles.actions}>
        {state.id && <Button icon={<HistoryOutlined aria-hidden="true" />} disabled={state.dirty || state.saving || locked || state.loading || !!state.conflict}
          title={state.dirty ? '请先保存当前修改，再恢复历史版本' : undefined} onClick={() => setDrawerOpen(true)}>版本历史</Button>}
        <ResumeOptimizer key={formEpoch} form={form} disabled={locked || state.loading || state.saving || !!state.conflict} />
        <Button icon={<EyeOutlined aria-hidden="true" />} disabled={!state.ready} onClick={() => setPreviewOpen(true)}>预览当前内容</Button>
        <Button type="primary" icon={<SaveOutlined aria-hidden="true" />} loading={state.saving} disabled={locked || state.loading || !!state.conflict}
          onClick={handleSubmit}>{state.imported ? '确认并保存导入' : '保存'}</Button>
      </div>
    </header>
    {state.imported && <ResumeImportReview review={state.importReview} onLocate={field => {
      const target = document.getElementById(importWarningTarget(field).section);
      target?.focus({ preventScroll: true });
      target?.scrollIntoView({ block: 'start' });
    }} />}
    <div role="status" aria-live="polite" className={styles.status}>
      <Typography.Text strong>{status}</Typography.Text>
      <Typography.Text type="secondary">{state.savedAt ? ` · 最近保存 ${new Date(state.savedAt).toLocaleTimeString()}` : ''}
        {state.id ? ` · 修订 ${state.revision ?? 0}` : ' · 尚未创建'}
        {state.dirty && !state.storageError ? ' · 草稿已暂存于本标签页（24 小时有效）' : ''}</Typography.Text>
    </div>
    {state.storageError && <Alert style={{ marginBottom: 16 }} type="warning" showIcon message={state.storageError} />}
    {state.error && <Alert style={{ marginBottom: 16 }} type="error" showIcon message={state.error}
      action={!state.ready ? <Button onClick={() => void controller.current?.load()}>重试加载</Button>
        : state.id ? <Button loading={state.loading} onClick={() => void controller.current?.checkConflict()}>核对服务器最新版</Button> : undefined} />}
    {state.conflict && <Alert type="warning" style={{ marginBottom: 16 }} message="简历已被其他页面修改。比较并选择后才能保存。"
      action={<Button onClick={() => setReviewOpen(true)}>比较与选择</Button>} />}
    {state.recovery && <Alert type="info" style={{ marginBottom: 16 }} showIcon
      message={`发现 ${new Date(state.recovery.updatedAt).toLocaleString()} 的未保存草稿`}
      description="恢复前已读取服务器内容；若修订号不同，将进入冲突比较。草稿仅限本标签页，退出登录或切换账号会清理。"
      action={<Space wrap><Button type="primary" onClick={() => controller.current?.recover()}>恢复草稿</Button>
        <Popconfirm title="丢弃这份未保存草稿？" onConfirm={() => controller.current?.discardRecovery()}>
          <Button danger>丢弃草稿</Button></Popconfirm></Space>} />}
    <div className={styles.workspace}>
      <nav className={styles.outline} aria-label="简历编辑目录">
        <h2>简历目录</h2>
        {resumeSections.map(section => <button key={section.id} type="button" onClick={() => {
          const target = document.getElementById(section.id);
          target?.focus({ preventScroll: true });
          target?.scrollIntoView({ block: 'start' });
        }}>{section.title}</button>)}
      </nav>
      <Skeleton loading={state.loading && !state.ready} active>
        <Form form={form} className={styles.form} layout="vertical" disabled={locked}
          initialValues={formValues()} onValuesChange={() => controller.current?.edit(normalizeValues(form.getFieldsValue(true)))}>
          <ResumeEditorFields key={formEpoch} imported={state.imported} />
        </Form>
      </Skeleton>
    </div>
    {state.conflict && reviewOpen && <ConflictReview key={JSON.stringify(state.values)} base={state.base} local={state.values} server={formValues(state.conflict)}
      revision={state.conflict.revision ?? 0} onClose={() => setReviewOpen(false)}
      onResolve={choices => { controller.current?.resolve(choices); setReviewOpen(false); }} />}
    <Modal open={blocker.state === 'blocked'} title="还有未保存的修改" okText={state.saving ? '仍然离开' : '保留草稿并离开'}
      cancelText="继续编辑" onCancel={() => blocker.state === 'blocked' && blocker.reset()}
      onOk={() => blocker.state === 'blocked' && blocker.proceed()}>
      <p>{state.saving ? '保存仍在进行，离开后请在列表核对结果。' : '这些修改尚未保存到服务器。'}</p>
      <p>{state.storageError ? '本地草稿存储不可用，离开可能丢失修改。' : '本标签页草稿保留 24 小时；退出登录或切换账号会清理草稿。'}</p>
    </Modal>
    {previewOpen && <ResumePreviewModal open resume={{ ...toRequest(state.values), id: state.id ?? 0,
      templateKey: 'classic', summary: state.values.summary, revision: state.revision } as ResumeProfile}
      onClose={() => setPreviewOpen(false)} />}
    {state.id && <VersionHistoryDrawer open={drawerOpen} resumeId={state.id} revision={state.revision}
      resumeName={state.values.ownerName} onClose={() => setDrawerOpen(false)}
      onRestored={saved => { controller.current?.restored(saved); setDrawerOpen(false); }}
      onConflict={() => { setDrawerOpen(false); void controller.current?.checkConflict(); }} />}
  </div>;
}

export default function ResumeEditorPage() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const location = useLocation();
  useEffect(() => {
    if ((location.state as { parsedImport?: boolean } | null)?.parsedImport) {
      navigate(location.pathname + location.search, { replace: true, state: null });
    }
  }, [location.pathname, location.search, location.state, navigate]);
  const value = params.get('resumeId');
  const resumeId = value && value !== 'new' ? Number(value) : null;
  const generation = useAuthStore(state => state.generation);
  const draftKey = resumeId ?? (params.get('import') === '1' ? 'import' : 'new');
  const [creation, setCreation] = useState<{ id: number; key: string; generation: number } | null>(null);
  const key = creation?.id === resumeId && creation?.generation === generation
    ? creation.key : `${generation}:${draftKey}${resumeId === null ? `:${creation?.id ?? 'initial'}` : ''}`;
  if (resumeId !== null && (!Number.isSafeInteger(resumeId) || resumeId <= 0)) return <Alert type="error" message="无效的简历编号" />;
  return <ResumeEditor key={key} resumeId={resumeId} draftKey={draftKey}
    freshImport={!!(location.state as { parsedImport?: boolean } | null)?.parsedImport}
    onCreated={id => setCreation({ id, key, generation })} />;
}
