import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Checkbox, Empty, Input, Modal, Pagination, Select, Space, Spin, Tag } from 'antd';
import AddSourceTask from '@/pages/Interview/AddSourceTask';
import { FileTextOutlined, PlusOutlined, UploadOutlined } from '@ant-design/icons';
import { Link, useSearchParams } from 'react-router-dom';
import { knowledgeService } from '@/api/modules/knowledge';
import { captureAccountScope } from '@/api/accountScope';
import { isRequestCanceled } from '@/api/ApiError';
import type { KnowledgeDocument, KnowledgeImport, KnowledgeHit, KnowledgeSummary, KnowledgeOrganization, SelfTestSelection } from '@/types/knowledge';
import styles from './index.module.css';
import AskPanel from './AskPanel';
import DocumentEditor from './DocumentEditor';
import VersionHistory from './VersionHistory';
import ImportQueue from './ImportQueue';
import ImportReview from './ImportReview';
import GenerateSelfTests from './GenerateSelfTests';
import OrganizationEditor from './OrganizationEditor';
import DocumentReader from './DocumentReader';

export default function KnowledgePage() {
  const [params, setParams] = useSearchParams();
  const id = params.get('document'), chunkId = params.get('chunk');
  const version = params.get('version');
  const [toolsOpen, setToolsOpen] = useState(params.has('parent'));
  useEffect(() => { if (params.has('parent')) setToolsOpen(true); }, [params]);
  const [editing, setEditing] = useState<KnowledgeDocument>(), [history, setHistory] = useState(false);
  const [rows, setRows] = useState<KnowledgeSummary[]>([]), [page, setPage] = useState(1);
  const [organization, setOrganization] = useState<KnowledgeOrganization[]>([]), [category, setCategory] = useState<string>(), [tags, setTags] = useState<string[]>([]);
  const [selected, setSelected] = useState<string[]>([]), [organizing, setOrganizing] = useState<string[]>(), [generating, setGenerating] = useState<SelfTestSelection[]>();
  const filtered = toolsOpen ? rows.filter(row => { const meta = organization.find(o => o.documentId === row.id); return (!category || meta?.category === category) && tags.every(tag => meta?.tags.includes(tag)); }) : rows;
  const total = filtered.length, visible = filtered.slice((page - 1) * 20, page * 20);
  useEffect(() => { setPage(previous => Math.min(previous, Math.max(1, Math.ceil(total / 20)))); }, [total]);
  const [doc, setDoc] = useState<KnowledgeDocument>();
  const [hits, setHits] = useState<KnowledgeHit[]>(), [query, setQuery] = useState(params.get('q') || '');
  const [search, setSearch] = useState(params.get('q') || '');
  const [loading, setLoading] = useState(false), [reading, setReading] = useState(false), [searching, setSearching] = useState(false);
  const [error, setError] = useState(''), [busy, setBusy] = useState(false), [reload, setReload] = useState(0);
  const [dialog, setDialog] = useState<'paste' | 'rename' | 'delete' | null>(null);
  const [title, setTitle] = useState(''), [content, setContent] = useState('');
  const [draft, setDraft] = useState<KnowledgeImport>();
  const [newJob, setNewJob] = useState<string>(), [reviewJob, setReviewJob] = useState<string>();
  const parsing = useRef<AbortController>();
  const file = useRef<HTMLInputElement>(null), action = useRef(false);
  useEffect(() => () => parsing.current?.abort(), []);
  useEffect(() => { const value = params.get('q') || ''; setSearch(value); setQuery(value); }, [params]);
  const fail = (cause: unknown) => { if (!isRequestCanceled(cause)) setError(cause instanceof Error ? cause.message : '操作失败，请重试'); };
  useEffect(() => {
    const request = new AbortController(), scope = captureAccountScope(); setLoading(true);
    void knowledgeService.choices(request.signal).then(result => { if (scope.isCurrent() && !request.signal.aborted) { setRows(result.items); setSelected(previous => previous.filter(value => result.items.some(row => row.id === value))); setPage(previous => Math.min(previous, Math.max(1, Math.ceil(result.items.length / 20)))); } })
      .catch(cause => { if (scope.isCurrent() && !request.signal.aborted) fail(cause); })
      .finally(() => { if (scope.isCurrent() && !request.signal.aborted) setLoading(false); });
    return () => request.abort();
  }, [reload]);
  useEffect(() => {
    if (!toolsOpen) return;
    const request = new AbortController(), scope = captureAccountScope();
    void knowledgeService.organization(request.signal).then(result => { if (scope.isCurrent() && !request.signal.aborted) setOrganization(result); })
      .catch(cause => { if (scope.isCurrent() && !request.signal.aborted) fail(cause); });
    return () => request.abort();
  }, [toolsOpen, reload]);
  useEffect(() => {
    setDoc(undefined); if (!id) { setReading(false); return; }
    const request = new AbortController(), scope = captureAccountScope(); setReading(true);
    const fetch = version ? knowledgeService.version(id, Number(version), request.signal) : knowledgeService.find(id, request.signal);
    void fetch.then(result => { if (scope.isCurrent() && !request.signal.aborted) setDoc(result); })
      .catch(cause => { if (scope.isCurrent() && !request.signal.aborted) fail(cause); })
      .finally(() => { if (scope.isCurrent() && !request.signal.aborted) setReading(false); });
    return () => request.abort();
  }, [id, version, reload]);
  useEffect(() => {
    setHits(undefined); if (!search.trim()) { setSearching(false); return; }
    const request = new AbortController(), scope = captureAccountScope(); setSearching(true);
    void knowledgeService.search(search, request.signal).then(result => { if (scope.isCurrent() && !request.signal.aborted) setHits(result); })
      .catch(cause => { if (scope.isCurrent() && !request.signal.aborted) fail(cause); })
      .finally(() => { if (scope.isCurrent() && !request.signal.aborted) setSearching(false); });
    return () => request.abort();
  }, [search, reload]);
  const run = async (operation: () => Promise<void>) => {
    if (action.current) return; action.current = true; setBusy(true); setError(''); const scope = captureAccountScope();
    try { await operation(); if (scope.isCurrent()) { setDialog(null); setReload(n => n + 1); } }
    catch (cause) { if (scope.isCurrent()) fail(cause); }
    finally { action.current = false; if (scope.isCurrent()) setBusy(false); }
  };
  const openDocument = (value: KnowledgeDocument) => { setParams({ document: value.id, ...(search ? { q: search } : {}) }); };
  const saved = (value: KnowledgeDocument) => { setEditing(undefined); setHistory(false); openDocument(value); setReload(n => n + 1); };
  const preview = async (selected: File) => {
    if (action.current) return;
    action.current = true; setBusy(true); setError(''); const scope = captureAccountScope();
    const request = new AbortController(); parsing.current = request;
    try {
      const result = await knowledgeService.startImport(selected, request.signal);
      if (scope.isCurrent() && !request.signal.aborted) setNewJob(result.id);
    } catch (cause) { if (scope.isCurrent() && !request.signal.aborted) fail(cause); }
    finally { action.current = false; parsing.current = undefined; if (scope.isCurrent() && !request.signal.aborted) setBusy(false); }
  };
  const save = () => run(async () => {
    const scope = captureAccountScope();
    if (dialog === 'paste') { const result = await knowledgeService.create(title, content); if (scope.isCurrent()) { openDocument(result); setTitle(''); setContent(''); } }
    if (dialog === 'rename' && doc) await knowledgeService.rename(doc.id, title);
    if (dialog === 'delete' && doc) { await knowledgeService.delete(doc.id); if (scope.isCurrent()) { setDoc(undefined); setParams(search ? { q: search } : {}); setPage(1); } }
  });
  return <main className={styles.page}>
    <header className={styles.heading}><div><h1>从资料生成面试练习题</h1><p>上传并核对原文，生成候选题，确认后保存到「我的题目」。</p></div>
      <Space wrap><Button type="primary" icon={<UploadOutlined aria-hidden="true" />} loading={busy && !!parsing.current} disabled={busy} onClick={() => file.current?.click()}>上传资料</Button><Button icon={<PlusOutlined aria-hidden="true" />} disabled={busy} onClick={() => { setDialog('paste'); setTitle(''); setContent(''); }}>粘贴笔记</Button></Space>
      <input ref={file} type="file" hidden accept=".pdf,.docx,.txt,.md,.markdown" aria-label="上传资料文件" onChange={event => {
        const selected = event.target.files?.[0]; event.target.value = ''; if (!selected) return;
        void preview(selected);
      }} />
    </header>
    <ImportQueue key={reload} newest={newJob} onReview={(jobId, value) => { setReviewJob(jobId); setDraft(value); }} />
    {error && <Alert type="error" showIcon message={error} action={<Button onClick={() => { setError(''); setReload(n => n + 1); }}>重试</Button>} />}
    <div className={styles.searchBar}>
      <Input.Search aria-label="搜索资料" placeholder="输入关键词，查找笔记中的内容" maxLength={300} value={query} onChange={e => setQuery(e.target.value)} enterButton="搜索" loading={searching} onSearch={value => { setSearch(value.trim()); setParams({ ...(id ? { document: id } : {}), ...(version ? { version } : {}), ...(value.trim() ? { q: value.trim() } : {}) }); }} allowClear />
      <details className={styles.retention}><summary>支持格式与导入限制</summary><p>支持文字型 PDF（≤ 100 MB，≤ 1000 页）、DOCX（≤ 30 MB）与 UTF-8 TXT / Markdown（≤ 400 KB）。正文最多 200 万字，DOCX 不按页数限制。后台解析后核对并确认入库。</p></details>
    </div>
    {search && <section className={styles.results} aria-label="搜索结果"><div className={styles.sectionHeading}><h2>搜索结果</h2><Button type="text" onClick={() => { setSearch(''); setQuery(''); setParams({ ...(id ? { document: id } : {}), ...(version ? { version } : {}), ...(chunkId ? { chunk: chunkId } : {}) }); }}>清除搜索</Button></div>
      {searching ? <Spin /> : hits?.length ? hits.map(hit => <Link className={styles.hit} key={hit.chunk.id} to={'/knowledge?' + new URLSearchParams({ document: hit.documentId, version: String(hit.version), chunk: hit.chunk.id, q: search })}><strong>{hit.title} · {hit.chunk.heading || '正文'}</strong><small>{hit.sourceName} · v{hit.version} · 第 {hit.chunk.startLine}–{hit.chunk.endLine} 行</small><p>{hit.chunk.content}</p></Link>) : <Empty description="没有找到相关内容，试试更具体的关键词" image={Empty.PRESENTED_IMAGE_SIMPLE} />}
    </section>}
    <section className={styles.batchTools} aria-label="资料出题">
      <Space wrap><strong>已选 {selected.length} / 5 份资料</strong>
        <Button type="primary" disabled={!selected.length || selected.length > 5 || loading} onClick={() => setGenerating(rows.filter(row => selected.includes(row.id)).map(row => ({ documentId: row.id, version: row.version, chunkIds: [] })))}>从所选资料生成题目</Button>
        <Button type="text" disabled={!selected.length} onClick={() => setSelected([])}>清空选择</Button>
      </Space>
      <Link to="/knowledge/practice?tab=drafts">查看待确认题目 <span aria-hidden="true">→</span></Link>
    </section>
    <details className={styles.secondaryTools} open={toolsOpen} onToggle={event => setToolsOpen(event.currentTarget.open)}>
      <summary>更多工具：资料问答与分类</summary>
      <Space wrap><Link to="/knowledge/history"><Button>问答历史</Button></Link><Link to="/knowledge/feedback"><Button>回答反馈样例</Button></Link></Space>
      <AskPanel documents={rows} current={id || undefined} selectedIds={selected} />
    <section className={styles.results} aria-label="资料分类与批量选择"><div className={styles.filterBar}>
      <Select aria-label="筛选资料分类" placeholder="全部分类" allowClear value={category} onChange={value => { setCategory(value); setPage(1); }} options={[...new Set(organization.map(o => o.category).filter(Boolean))].map(value => ({ value, label: value }))} />
      <Select mode="multiple" aria-label="筛选资料标签" placeholder="按标签筛选（同时满足）" allowClear value={tags} onChange={value => { setTags(value); setPage(1); }} options={[...new Set(organization.flatMap(o => o.tags))].map(value => ({ value, label: value }))} />
    </div><Space wrap><Button disabled={!selected.length} onClick={() => setOrganizing(selected)}>为所选资料设置分类 / 标签</Button>
      {doc && !version && <AddSourceTask key={`${doc.id}:${doc.version}`} source={{ kind: 'KNOWLEDGE', id: doc.id, version: doc.version }} />}
    </Space></section>
    </details>
    <div className={styles.workspace}>
      <aside className={styles.library}><div className={styles.sectionHeading}><h2>我的资料 <small>{total}</small></h2><Button type="text" onClick={() => setReload(n => n + 1)}>刷新</Button></div>
        {loading ? <Spin /> : !visible.length ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={rows.length ? '没有符合分类与标签的资料' : '上传第一份学习笔记'} /> : visible.map(row => <div className={styles.documentRow} key={row.id}><Checkbox disabled={!selected.includes(row.id) && selected.length >= 5} aria-label={'选择资料：' + row.title} checked={selected.includes(row.id)} onChange={event => setSelected(event.target.checked ? [...selected, row.id] : selected.filter(value => value !== row.id))} /><Link aria-current={row.id === id ? 'page' : undefined} className={styles.document} to={'/knowledge?' + new URLSearchParams({ document: row.id })}><FileTextOutlined aria-hidden="true" /><div><strong>{row.title}</strong><small>{row.sourceName} · v{row.version}</small><small>{[organization.find(o => o.documentId === row.id)?.category, ...organization.find(o => o.documentId === row.id)?.tags || []].filter(Boolean).join(' · ')}</small></div></Link></div>)}
        {total > 20 && <Pagination simple current={page} total={total} pageSize={20} onChange={setPage} />}
      </aside>
      <section className={styles.reader} aria-label="资料原文">
        {reading ? <Spin /> : doc ? <><div className={styles.sectionHeading}><div><Tag>原文 · v{doc.version}</Tag><h2>{doc.title}</h2><small>来源：{doc.sourceName} · {doc.content.length} 字</small></div><Space wrap><Button type="primary" onClick={() => setGenerating([{ documentId: doc.id, version: doc.version, chunkIds: [] }])}>从这份资料出题</Button>{toolsOpen && <Button onClick={() => setOrganizing([doc.id])}>分类与标签</Button>}<Button onClick={() => setHistory(true)}>版本历史</Button>{!version && <><Button onClick={() => setEditing(doc)}>编辑正文</Button><Button onClick={() => { setTitle(doc.title); setDialog('rename'); }}>重命名</Button><Button danger onClick={() => setDialog('delete')}>删除资料</Button></>}</Space></div>
          {version && <Alert type="info" showIcon message={`正在核对归档正文 v${doc.version}；搜索和新训练使用当前版本。`} action={<Button onClick={() => openDocument(doc)}>查看当前版本</Button>} />}
          {!version && <Link to={'/interview?' + new URLSearchParams({ knowledgeDocumentId: doc.id })}><Button>使用这份资料进入专项训练</Button></Link>}
          <DocumentReader key={`${doc.id}:${doc.version}`} document={doc} chunkId={chunkId} onGenerate={chunkIds => setGenerating([{ documentId: doc.id, version: doc.version, chunkIds }])} />
        </> : <div className={styles.readerEmpty}><FileTextOutlined aria-hidden="true" /><h2>资料预览</h2><p>选择资料或搜索结果，在这里核对原文。</p></div>}
      </section>
    </div>
    {editing && <DocumentEditor document={editing} onClose={() => setEditing(undefined)} onSaved={saved} />}
    {generating && <GenerateSelfTests sources={generating} onClose={() => setGenerating(undefined)} />}
    {organizing && <OrganizationEditor ids={organizing} initial={organizing.length === 1 ? organization.find(o => o.documentId === organizing[0]) : undefined} onClose={() => setOrganizing(undefined)} onSaved={() => { setOrganizing(undefined); setReload(n => n + 1); }} />}
    {draft && reviewJob && <ImportReview draft={draft} jobId={reviewJob} onClose={() => { setDraft(undefined); setReviewJob(undefined); setNewJob(undefined); setReload(n => n + 1); }} onSaved={value => { setDraft(undefined); setPage(1); saved(value); }} />}
    {history && id && <VersionHistory key={id} id={id} onClose={() => setHistory(false)} onRestored={saved} />}
    <Modal title={dialog === 'paste' ? '粘贴学习笔记' : dialog === 'rename' ? '重命名资料' : '删除这份资料？'} open={!!dialog} onCancel={() => { if (!busy) setDialog(null); }} onOk={() => void save()} confirmLoading={busy} okText={dialog === 'delete' ? '确认删除' : '保存'} cancelText="取消" okButtonProps={{ danger: dialog === 'delete', disabled: dialog !== 'delete' && (!title.trim() || (dialog === 'paste' && !content.trim())) }}>
      {dialog === 'delete' ? <><p>将删除“{doc?.title}”及其全部版本与检索片段。</p><p>相关面试将撤销资料使用资格，停止后续生成与提交；历史记录隐藏该资料原文，相关评价不再用于新训练。本人回答保留，可更换材料新建训练。问答历史及引用摘录仍保留，来源显示不可用；如需清除摘录，请删除对应问答记录。训练归档的物理副本随训练记录保留，删除训练记录可清除。</p></> : <><label>资料标题<Input aria-label="资料标题" maxLength={160} value={title} onChange={e => setTitle(e.target.value)} /></label>{dialog === 'paste' && <label>笔记正文<Input.TextArea aria-label="笔记正文" rows={12} maxLength={2000000} value={content} onChange={e => setContent(e.target.value)} placeholder="支持纯文本与 Markdown；标题和段落位置会保留。" /></label>}</>}
    </Modal>
  </main>;
}
