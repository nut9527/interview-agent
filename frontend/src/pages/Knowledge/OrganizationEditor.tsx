import { useState } from 'react';
import { Alert, Modal, Select } from 'antd';
import { knowledgeService } from '@/api/modules/knowledge';
import { captureAccountScope } from '@/api/accountScope';
import type { KnowledgeOrganization } from '@/types/knowledge';
import styles from './index.module.css';

export default function OrganizationEditor({ ids, initial, onClose, onSaved }: { ids: string[]; initial?: KnowledgeOrganization; onClose: () => void; onSaved: () => void }) {
  const [category, setCategory] = useState(initial?.category || ''), [tags, setTags] = useState(initial?.tags || []);
  const [busy, setBusy] = useState(false), [error, setError] = useState('');
  const save = async () => {
    if (busy) return;
    const scope = captureAccountScope(); setBusy(true); setError('');
    try { await knowledgeService.organize(ids, category, tags); if (scope.isCurrent()) onSaved(); }
    catch (cause) { if (scope.isCurrent()) setError(cause instanceof Error ? cause.message : '分类保存失败'); }
    finally { if (scope.isCurrent()) setBusy(false); }
  };
  return <Modal title={`设置 ${ids.length} 份资料的分类与标签`} open onCancel={() => { if (!busy) onClose(); }} onOk={() => void save()} confirmLoading={busy} okText="保存分类与标签" cancelText="取消">
    <p>保存会统一替换所选资料的分类与标签，正文版本保持不变。清空即可移除。</p><div className={styles.learningFields}>
      <label>分类<Select aria-label="资料分类" allowClear value={category || undefined} onChange={value => setCategory(value || '')} options={['Java', '数据库', '项目经验', '计算机基础', '其他'].map(value => ({ value, label: value }))} /></label>
      <label>标签（最多 10 个，每个最多 24 字）<Select aria-label="资料标签" mode="tags" value={tags} maxCount={10} tokenSeparators={[',', '，']} onChange={setTags} /></label>
      {error && <Alert type="error" message={error} />}
    </div>
  </Modal>;
}
