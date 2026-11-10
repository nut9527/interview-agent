import { Alert, Button } from 'antd';
import type { ResumeImportReview as ImportReview } from '@/api/modules/resume';
import { importWarningTarget } from './importReview';

export default function ResumeImportReview({ review, onLocate, message = '请核对导入的联系方式、日期和经历，再确认保存。', className }: {
  review: ImportReview | null;
  onLocate: (field: string) => void;
  message?: string;
  className?: string;
}) {
  const warnings = review?.warnings ?? [];
  const mode = review?.recognitionMode === 'LOCAL' ? '本地规则识别 · ' : '';
  return <Alert type={warnings.length ? 'warning' : 'info'} showIcon className={className} style={{ marginBlock: 16 }}
    message={`${mode}${warnings.length ? `待核对 ${warnings.length} 项` : message}`}
    description={warnings.length ? <ul style={{ margin: '8px 0 0', paddingLeft: 20 }}>
      {warnings.map((warning, index) => <li key={`${warning.field}:${warning.code}:${index}`}>
        <Button type="link" size="small" style={{ paddingInline: 0, height: 'auto', whiteSpace: 'normal' }}
          onClick={() => onLocate(warning.field)}>{importWarningTarget(warning.field).label}</Button>：{warning.message}
      </li>)}
    </ul> : undefined} />;
}
