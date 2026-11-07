import { Button, Tag, Typography, Badge, Card } from 'antd';
import dayjs from 'dayjs';

import type { ResumeComparison, ResumeFieldDiff, ResumeProfile } from '@/types/api.d';

const { Text } = Typography;

const TEMPLATE_LABELS: Record<string, string> = {
  classic: '经典模板',
  modern: '现代模板',
  compact: '紧凑模板',
};

type FieldType = 'text' | 'tags' | 'cards';

interface FieldDef {
  key: string;
  label: string;
  type: FieldType;
}

/** 两栏共享字段顺序，保证新旧版本按同一顺序对照。 */
const FIELD_DEFS: FieldDef[] = [
  { key: 'ownerName', label: '姓名', type: 'text' },
  { key: 'targetRole', label: '目标岗位', type: 'text' },
  { key: 'summary', label: '个人简介', type: 'text' },
  { key: 'skills', label: '技能标签', type: 'tags' },
  { key: 'educationExperiences', label: '教育经历', type: 'cards' },
  { key: 'projectExperiences', label: '项目经历', type: 'cards' },
  { key: 'internshipExperiences', label: '实习经历', type: 'cards' },
  { key: 'certificates', label: '证书', type: 'tags' },
  { key: 'details', label: '结构化项目与实习安排', type: 'cards' },
  { key: 'templateKey', label: '模板', type: 'text' },
];

function formatDate(dateStr: string | null): string {
  if (!dateStr) return '';
  return dayjs(dateStr).format('YYYY-MM-DD HH:mm');
}

function fmtTemplate(value: string): string {
  return TEMPLATE_LABELS[value] || value;
}

function scrollToField(fieldKey: string) {
  document.getElementById(`field-${fieldKey}`)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
}


interface VersionCompareViewProps {
  comparison: ResumeComparison | null;
  onBack: () => void;
}

function VersionCompareView({ comparison, onBack }: VersionCompareViewProps) {
  if (!comparison?.left || !comparison?.right) {
    return (
      <div className="flex flex-col items-center justify-center py-20 text-gray-400">
        <Text type="secondary">请选择两个版本进行对比</Text>
        <Button type="text" onClick={onBack} className="mt-4">
          返回版本列表
        </Button>
      </div>
    );
  }

  const { left, right, changedFieldCount, fields } = comparison;

  const fieldMap = new Map<string, ResumeFieldDiff>();
  fields.forEach((f) => fieldMap.set(f.field, f));

  const changedFields = fields.filter((f) => f.changed);

  const leftLabel =
    left.changeType === 'CURRENT' || left.id === null ? '当前版本' : `v${left.versionNumber}`;
  const rightLabel =
    right.changeType === 'CURRENT' || right.id === null ? '当前版本' : `v${right.versionNumber}`;


  function renderResumeContent(snapshot: ResumeProfile, side: 'left' | 'right') {
    return FIELD_DEFS.map((def) => {
      // 结构化 details 使用服务端生成的可读字段值，避免把嵌套对象当作经历字符串渲染。
      const raw = def.key === 'details' ? (side === 'left' ? fieldMap.get('details')?.leftValues : fieldMap.get('details')?.rightValues) : (snapshot as unknown as Record<string, unknown>)[def.key];
      const diff = fieldMap.get(def.key);
      const hasChange = diff?.changed ?? false;

      if (def.type === 'text') {
        let display: string;
        if (def.key === 'templateKey') {
          display = fmtTemplate(raw as string);
        } else if (!raw) {
          display = '—';
        } else {
          display = raw as string;
        }

        const isMultiLine = def.key === 'summary';
        const cls = hasChange
          ? side === 'left'
            ? 'bg-red-50/80 border-l-4 border-red-500 pl-3 py-1 rounded-r'
            : 'bg-green-50/80 border-l-4 border-green-500 pl-3 py-1 rounded-r'
          : 'pl-3 py-1';

        return (
          <div key={def.key} id={side === 'left' ? `field-${def.key}` : undefined} className="mb-4">
            <div className="text-xs font-semibold text-gray-400 uppercase tracking-wider mb-1.5">
              {def.label}
            </div>
            <div className={cls}>
              <Text className={isMultiLine ? 'whitespace-pre-wrap text-sm' : def.key === 'ownerName' ? 'text-lg font-semibold' : def.key === 'targetRole' ? 'text-sm text-gray-600' : 'text-sm'}>
                {display}
              </Text>
            </div>
          </div>
        );
      }

      if (def.type === 'tags') {
        const values = (raw as string[]) ?? [];
        const removedItems: string[] = diff?.removed ?? [];
        const addedItems: string[] = diff?.added ?? [];

        return (
          <div key={def.key} id={side === 'left' ? `field-${def.key}` : undefined} className="mb-4">
            <div className="text-xs font-semibold text-gray-400 uppercase tracking-wider mb-1.5">
              {def.label}
            </div>
            <div className="pl-3 border-l-2 border-gray-200">
              {values.length === 0 ? (
                <Text type="secondary" className="text-sm">—</Text>
              ) : (
                <div className="flex flex-wrap gap-1">
                  {values.map((item) => {
                    const isRemoved = side === 'left' && removedItems.includes(item);
                    const isAdded = side === 'right' && addedItems.includes(item);

                    if (isRemoved) {
                      return (
                        <Tag key={item} color="red" className="line-through opacity-70">
                          {item}
                        </Tag>
                      );
                    }
                    if (isAdded) {
                      return (
                        <Tag key={item} color="green" className="font-semibold">
                          {item}
                        </Tag>
                      );
                    }
                    return <Tag key={item}>{item}</Tag>;
                  })}
                </div>
              )}
            </div>
          </div>
        );
      }

      if (def.type === 'cards') {
        const values = (raw as string[]) ?? [];

        return (
          <div key={def.key} id={side === 'left' ? `field-${def.key}` : undefined} className="mb-4">
            <div className="text-xs font-semibold text-gray-400 uppercase tracking-wider mb-1.5">
              {def.label}
            </div>
            <div className="pl-3 border-l-2 border-gray-200">
              {values.length === 0 ? (
                <Text type="secondary" className="text-sm">—</Text>
              ) : (
                <div className="space-y-2">
                  {values.map((item, idx) => (
                    <Card
                      key={idx}
                      size="small"
                      className={
                        hasChange
                          ? side === 'left'
                            ? 'border-l-4 border-red-300 bg-red-50/30'
                            : 'border-l-4 border-green-300 bg-green-50/30'
                          : ''
                      }
                    >
                      <Text className="text-sm whitespace-pre-wrap">{item}</Text>
                    </Card>
                  ))}
                </div>
              )}
            </div>
          </div>
        );
      }

      return null;
    });
  }


  return (
    <div className="flex flex-col h-full">
      <div className="flex-shrink-0 px-4 py-3 border-b bg-white">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <span className="font-medium">
              对比 {leftLabel} ↔ {rightLabel}
            </span>
            <Badge count={`${changedFieldCount} 处变更`} color="blue" />
          </div>
          <Button type="text" onClick={onBack}>
            ← 返回版本列表
          </Button>
        </div>
        {changedFields.length > 0 && (
          <div className="flex gap-1 mt-2 flex-wrap">
            {changedFields.map((f) => (
              <Tag
                key={f.field}
                color="blue"
                className="cursor-pointer"
                onClick={() => scrollToField(f.field)}
              >
                {f.label}
              </Tag>
            ))}
          </div>
        )}
      </div>

      {/* 两栏共用滚动容器，字段跳转时保留新旧版本的对照位置。 */}
      <div className="flex-1 overflow-y-auto">
        <div className="grid grid-cols-2 gap-6 p-6">
          <div className="bg-gray-50/80 rounded-xl border border-gray-200 p-5 shadow-sm">
            <div className="text-sm font-medium text-gray-500 mb-4 flex items-center gap-2">
              <span className="inline-block w-2 h-2 rounded-full bg-gray-400" />
              📄 旧版本 {leftLabel}
              <span className="text-xs text-gray-400 ml-auto">{formatDate(left.createdAt)}</span>
            </div>
            {renderResumeContent(left.snapshot, 'left')}
          </div>

          <div className="bg-blue-50/40 rounded-xl border border-blue-200 p-5 shadow-sm">
            <div className="text-sm font-medium text-blue-600 mb-4 flex items-center gap-2">
              <span className="inline-block w-2 h-2 rounded-full bg-blue-500" />
              📄 新版本 {rightLabel}
              <span className="text-xs text-blue-400 ml-auto">{formatDate(right.createdAt)}</span>
            </div>
            {renderResumeContent(right.snapshot, 'right')}
          </div>
        </div>
      </div>
    </div>
  );
}

export default VersionCompareView;
