import { Alert, Tag } from 'antd';
import { Link } from 'react-router-dom';
import type { KnowledgeAnswer, KnowledgeAnswerSource } from '@/types/knowledge';
import styles from './index.module.css';
import AnswerFeedback from './AnswerFeedback';

export default function AnswerView({ answer, sources }: { answer: KnowledgeAnswer; sources?: KnowledgeAnswerSource[] }) {
  return <div className={styles.answer} aria-live="polite">
    <Alert type={answer.sufficient ? 'info' : 'warning'} message={answer.message} />
    {answer.executionMode === 'mock' && <Tag>规则演示</Tag>}
    {answer.claims.map((claim, i) => <article key={i}><p>{claim.text}</p>{claim.citations.map((source, j) => {
      const available = !sources || sources.some(d => d.documentId === source.documentId && d.version === source.version && d.available);
      const label = `${source.title} · v${source.version} · 第 ${source.startLine}–${source.endLine} 行`;
      return <blockquote key={j}>{available
        ? <Link to={'/knowledge?' + new URLSearchParams({ document: source.documentId, version: String(source.version), chunk: source.chunkId })}>{label}</Link>
        : <span>{label} <Tag color="warning">来源不可用（资料已删除）</Tag></span>}<p>{source.quote}</p></blockquote>;
    })}</article>)}
    {answer.historyId && <AnswerFeedback key={answer.historyId} id={answer.historyId} />}
  </div>;
}
