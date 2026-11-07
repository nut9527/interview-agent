import { memo, useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { Alert, Button, Checkbox, Form, Modal, Space, Spin, Tag } from 'antd';
import type { FormInstance } from 'antd';
import { RocketOutlined, UndoOutlined } from '@ant-design/icons';
import { aiService } from '@/api/modules/ai';
import { normalizeValues } from '@/pages/Resumes/Editor/editorData';
import { buildTextEdits } from './textReview';
import { createOptimizationController, emptyOptimizationState } from './optimizationController';
import styles from './AiTextOptimizer.module.css';

const Revision = memo(function Revision({ before, after }: { before: string; after: string }) {
  const original: ReactNode[] = [], revised: ReactNode[] = [];
  let offset = 0;
  for (const edit of buildTextEdits(before, after)) {
    const kept = before.slice(offset, edit.start);
    original.push(kept, <del key={edit.id}>{edit.before}</del>);
    revised.push(kept, <ins key={edit.id}>{edit.after}</ins>);
    offset = edit.end;
  }
  return <div className={styles.comparison}>
    <div><span className={styles.caption}>原文</span><p>{original}{before.slice(offset)}</p></div>
    <div><span className={styles.caption}>建议</span><p>{revised}{before.slice(offset)}</p></div>
  </div>;
});

export default function ResumeOptimizer({ form, disabled }: { form: FormInstance; disabled?: boolean }) {
  const [open, setOpen] = useState(false);
  const [state, setState] = useState(emptyOptimizationState);
  const controller = useRef<ReturnType<typeof createOptimizationController>>();
  Form.useWatch([], { form, preserve: true });
  useEffect(() => {
    const instance = createOptimizationController({
      read: () => normalizeValues(form.getFieldsValue(true)),
      write: values => form.setFieldsValue(values),
      changed: setState,
      optimize: aiService.optimizeDraft,
    });
    controller.current = instance;
    return () => instance.dispose();
  }, [form]);
  useEffect(() => {
    if (disabled) {
      controller.current?.cancel();
      setOpen(false);
    }
  }, [disabled]);

  const close = () => { controller.current?.cancel(); setOpen(false); };
  const stale = open && controller.current?.stale();
  const selected = state.edits?.filter(edit => edit.selected).length ?? 0;
  return <>
    <Button icon={<RocketOutlined aria-hidden />} disabled={disabled} onClick={() => {
      setOpen(true);
      if (!state.edits && !state.canUndo && !state.notice) void controller.current?.generate();
    }}>AI 优化简历</Button>
    <Modal title="简历优化建议" open={open} width={820} onCancel={close} footer={
      <div className={styles.footer}>
        <Space wrap>
          {!state.loading && <Button type="text" onClick={() => void controller.current?.generate()}>重新生成</Button>}
          {state.canUndo && <Button type="text" icon={<UndoOutlined aria-hidden />} disabled={state.loading}
            onClick={() => controller.current?.undo()}>撤销上次采用</Button>}
        </Space>
        <Space wrap>
          <Button onClick={close}>{state.loading ? '取消生成' : '关闭'}</Button>
          <Button type="primary" disabled={!selected || !!stale || state.loading || disabled}
            onClick={() => controller.current?.apply()}>采用所选修改</Button>
        </Space>
      </div>
    }>
      <div className={styles.body} aria-busy={state.loading}>
        {state.loading && <div className={styles.progress} role="status"><Spin /><span>正在分析当前简历…</span></div>}
        {state.error && <Alert type="error" showIcon message={state.error} />}
        {stale && <Alert type="warning" showIcon message="简历内容已修改，请重新生成建议。" />}
        {state.notice && <div role="status"><Alert type="info" showIcon message={state.notice} /></div>}
        {!!state.edits?.length && <>
          <p className={styles.instructions}>勾选要采用的表述，核对职责归属和数据后写入草稿。</p>
          <ul className={styles.edits}>{state.edits.map(edit => <li key={edit.index} className={styles.edit}>
            <div className={styles.heading}>
              <Checkbox aria-label={`采用：${edit.label}`} checked={edit.accepted || edit.selected}
                disabled={edit.accepted || !!stale || disabled}
                onChange={event => controller.current?.select(edit.index, event.target.checked)}>{edit.label}</Checkbox>
              {edit.accepted && <Tag color="green">已采用</Tag>}
            </div>
            <Revision before={edit.before} after={edit.after} />
          </li>)}</ul>
        </>}
      </div>
    </Modal>
  </>;
}
