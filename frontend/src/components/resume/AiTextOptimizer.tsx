import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Checkbox, Form, Space, Tag, Typography } from 'antd';
import { RocketOutlined, UndoOutlined } from '@ant-design/icons';
import { aiService } from '@/api/modules/ai';
import { createOptimizationController, emptyOptimizationState, expectedReviewText } from './optimizationController';
import type { OptimizationState, ReviewedEdit } from './optimizationController';
import styles from './AiTextOptimizer.module.css';

interface Props {
  name: Array<string | number>;
  label: string;
  lines?: boolean;
}

function EditText({ edit, source, proposed }: { edit: ReviewedEdit; source: string; proposed?: boolean }) {
  const prefix = source.slice(Math.max(0, edit.start - 28), edit.start);
  const suffix = source.slice(edit.end, edit.end + 28);
  const text = proposed ? edit.after : edit.before;
  return <div className={styles.excerpt}>
    {edit.start > 28 && '…'}{prefix}
    {proposed ? <ins>{text || '〔删除此片段〕'}</ins> : <del>{text || '〔此处新增〕'}</del>}
    {suffix}{edit.end + 28 < source.length && '…'}
  </div>;
}

/** Mounted under a stable Form.List key so removing another project cannot retarget a request. */
export default function AiTextOptimizer({ name, label, lines = false }: Props) {
  const form = Form.useFormInstance();
  const watched = Form.useWatch(name, { form, preserve: true }) as string | string[] | undefined;
  const role = Form.useWatch('targetRole', { form, preserve: true }) as string | undefined;
  const field = useRef({ form, name, lines });
  field.current = { form, name, lines };
  const controller = useRef<ReturnType<typeof createOptimizationController>>();
  const [state, setState] = useState<OptimizationState>(emptyOptimizationState);
  const [expanded, setExpanded] = useState(true);

  useEffect(() => {
    const instance = createOptimizationController({
      read: () => {
        const value: unknown = field.current.form.getFieldValue(field.current.name);
        return field.current.lines && Array.isArray(value) ? value.join('\n') : typeof value === 'string' ? value : '';
      },
      write: text => field.current.form.setFieldValue(field.current.name, field.current.lines ? text.split('\n') : text),
      targetRole: () => (field.current.form.getFieldValue('targetRole') as string | undefined) ?? '',
      validate: text => field.current.lines && (text.split('\n').length > 30
        || text.split('\n').some(line => !line.trim() || line.length > 3000))
        ? '采用后的项目贡献须为 1–30 行，每行 1–3000 字且不能为空；请选择其他修改或手动调整。' : null,
      optimize: aiService.optimize,
      changed: setState,
    });
    controller.current = instance;
    setState(emptyOptimizationState);
    return () => { instance.dispose(); };
  }, [form]);

  const currentText = Array.isArray(watched) ? watched.join('\n') : watched ?? '';
  const review = state.review;
  const stale = review && (currentText !== expectedReviewText(review) || (role ?? '') !== review.targetRole);
  const selected = review?.edits.filter(edit => edit.status === 'pending' && edit.selected).length ?? 0;
  const pending = review?.edits.filter(edit => edit.status === 'pending').length ?? 0;

  return <section aria-label={`${label} AI 优化`} className={styles.optimizer}>
    <Space wrap>
      <Button size="small" icon={<RocketOutlined aria-hidden />} loading={state.loading}
        onClick={() => { setExpanded(true); void controller.current?.generate(); }}>
        {review ? '基于当前内容重新生成' : `AI 优化${label}`}
      </Button>
      {state.loading && <Button size="small" onClick={() => controller.current?.reject()}>取消生成</Button>}
      {state.undoCount > 0 && <Button size="small" icon={<UndoOutlined aria-hidden />} disabled={state.loading}
        onClick={() => controller.current?.undo()}>撤销上次采用</Button>}
      {review && <Button size="small" type="text" aria-expanded={expanded}
        onClick={() => setExpanded(value => !value)}>{expanded ? '收起对比' : '展开对比'}</Button>}
    </Space>
    {!review && !state.loading && <div className={styles.hint}>先比较，再选择采用。只优化实际经历，不补造事实。</div>}
    {state.loading && <p role="status" className={styles.hint}>正在生成建议，你可以继续编辑；返回后会核对当前文字。</p>}
    {state.error && <Alert className={styles.feedback} type="error" showIcon message={state.error} />}
    {state.notice && <p role="status" className={styles.hint}>{state.notice}</p>}
    {review && expanded && <div className={styles.review}>
      <div className={styles.heading}>
        <Typography.Text strong>原文与建议 · {label}</Typography.Text>
        <Tag>{review.edits.length} 处修改</Tag>
      </div>
      <p className={styles.hint}>{review.response.optimizationSummary}</p>
      {stale && <Alert type="warning" showIcon className={styles.feedback}
        message="文字或目标岗位已修改，旧建议不能采用。请基于当前内容重新生成；你的输入已保留。" />}
      <details className={styles.fullText}>
        <summary>查看完整原文与优化稿</summary>
        <div className={styles.comparison}>
          <div><strong>生成时的原文</strong><div className={styles.excerpt}>{review.source}</div></div>
          <div><strong>完整优化稿（尚未全部采用）</strong><div className={styles.excerpt}>{review.response.optimizedText}</div></div>
        </div>
      </details>
      <ol className={styles.edits}>
        {review.edits.map(edit => <li key={edit.id} className={styles.edit}>
          <div className={styles.heading}>
            <Checkbox checked={edit.selected} disabled={state.loading || edit.status !== 'pending'}
              onChange={event => controller.current?.select(edit.id, event.target.checked)}>
              选择修改 {edit.id + 1}
            </Checkbox>
            {edit.status === 'pending' ? <Button size="small" type="text" disabled={state.loading}
              onClick={() => controller.current?.reject([edit.id])}>拒绝此条</Button>
              : <Tag color={edit.status === 'accepted' ? 'green' : 'default'}>{edit.status === 'accepted' ? '已采用' : '已拒绝'}</Tag>}
          </div>
          <div className={styles.comparison}>
            <div><span className={styles.caption}>原文 · 划线为待替换内容</span><EditText edit={edit} source={review.source} /></div>
            <div><span className={styles.caption}>建议 · 下划线为新增内容</span><EditText edit={edit} source={review.source} proposed /></div>
          </div>
        </li>)}
      </ol>
      {pending > 0 && <Space wrap className={styles.actions}>
        <Button type="primary" size="small" disabled={state.loading || Boolean(stale) || !selected}
          onClick={() => controller.current?.apply()}>采用所选（{selected}）</Button>
        <Button size="small" disabled={state.loading} onClick={() => controller.current?.reject()}>拒绝其余建议</Button>
      </Space>}
      <details className={styles.fullText}>
        <summary>修改理由与后续建议</summary>
        <ul>{review.response.suggestions.map((suggestion, i) => <li key={i}>{suggestion}</li>)}</ul>
        {review.response.improvements.map((item, i) => <p key={i}><strong>{item.category}：</strong>{item.issue}<br />
          {item.action}<br /><span className={styles.hint}>参考表达：{item.example}</span></p>)}
        {review.response.targetKeywords.length > 0 && <p>岗位关键词：{review.response.targetKeywords.join('、')}</p>}
      </details>
    </div>}
  </section>;
}
