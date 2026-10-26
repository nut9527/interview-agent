import { useState } from 'react';
import { Alert, Modal, Radio, Typography } from 'antd';
import { differences, fieldValue, type Choices, type EditorValues } from '../editorData';
import styles from './ConflictReview.module.css';

function display(value: unknown): string {
  if (typeof value === 'string') return value || '（空）';
  if (Array.isArray(value)) return value.map(display).join('\n\n') || '（空）';
  const labels: Record<string, string> = { name: '项目名称', role: '角色', period: '时间', background: '背景',
    contributions: '贡献', techStack: '技术栈', school: '学校', major: '专业', degree: '学历',
    startDate: '入学年月', endDate: '毕业年月', label: '名称', url: '地址' };
  return value && typeof value === 'object'
    ? Object.entries(value).map(([k,v]) => `${labels[k] ?? k}：${display(v)}`).join('\n') : '（空）';
}

export default function ConflictReview({ base, local, server, revision, onResolve, onClose }: {
  base: EditorValues; local: EditorValues; server: EditorValues; revision: number;
  onResolve: (choices: Choices) => void; onClose: () => void;
}) {
  const [choices, setChoices] = useState<Choices>({});
  const diffs = differences(base, local, server);
  const unresolved = diffs.filter(d => !choices[d.path] && !d.suggested).length;
  return <Modal open title={`修改冲突 · 服务器修订 ${revision}`} width={1000}
    onCancel={onClose} onOk={() => onResolve(choices)} okText="采用选择，返回编辑" cancelText="稍后处理"
    okButtonProps={{ disabled: unresolved > 0 }} maskClosable={false}>
    <Alert type="warning" showIcon message="服务器已有新修改，你的输入已保留。选择后仍需点击保存。"
      description="仅一方修改的字段已预选该方；双方都修改的字段必须手动选择。教育与项目按完整列表选择，避免错配条目。" />
    {diffs.length === 0 && <p>内容已经一致，可采用服务器修订号继续编辑。</p>}
    {diffs.map(diff => <section key={diff.path} className={styles.field}>
      <Typography.Title level={5}>{diff.label}</Typography.Title>
      <details><summary>查看开始编辑时的内容</summary><pre className={styles.text}>{display(fieldValue(base, diff.path))}</pre></details>
      <Radio.Group className={styles.columns} value={choices[diff.path] ?? diff.suggested}
        onChange={e => setChoices(prev => ({ ...prev, [diff.path]: e.target.value as 'local' | 'server' }))}>
        <div className={styles.option}><Radio value="local">保留我的内容</Radio><pre className={styles.text}>{display(diff.local)}</pre></div>
        <div className={styles.option}><Radio value="server">采用服务器内容</Radio><pre className={styles.text}>{display(diff.server)}</pre></div>
      </Radio.Group>
    </section>)}
    <p role="status">{unresolved ? `还有 ${unresolved} 项需要选择` : '所有差异已有选择'}</p>
  </Modal>;
}
