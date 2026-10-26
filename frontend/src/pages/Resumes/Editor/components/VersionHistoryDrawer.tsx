import { useEffect, useState, useRef } from 'react';
import { Drawer, Timeline, Tag, Button, Modal, message, Spin, Typography, Checkbox, Pagination } from 'antd';
import { RollbackOutlined, DiffOutlined, ArrowLeftOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';

import type { ResumeVersionSummary, ResumeChangeType } from '@/types/api.d';

import { captureAccountScope } from '@/api/accountScope';
import { ApiError } from '@/api/ApiError';
import type { ResumeProfile } from '@/types/api.d';
import { useResumeStore } from '@/stores/resumeStore';
import VersionCompareView from './VersionCompareView';

const { Text } = Typography;

/** 变更类型 → 中文标签映射 */
const CHANGE_TYPE_MAP: Record<ResumeChangeType, string> = {
  CREATED: '创建',
  UPDATED: '更新',
  IMPORTED: '导入',
  TEMPLATE: '模板创建',
  RESTORED: '版本恢复',
  CURRENT: '当前版本',
};

type ViewMode = 'timeline' | 'compare';

interface VersionHistoryDrawerProps {
  open: boolean;
  resumeId: number;
  revision?: number;
  resumeName: string;
  onClose: () => void;
  /** 版本恢复成功后回调，用于父组件刷新简历数据 */
  onRestored: (resume: ResumeProfile) => void;
  onConflict: () => void;
}

function VersionHistoryDrawer({
  open,
  resumeId,
  revision,
  resumeName,
  onClose,
  onRestored,
  onConflict,
}: VersionHistoryDrawerProps) {
  const { versions, versionTotal, fetchVersions, restoreVersion, compareResult, compareVersions } =
    useResumeStore();

  const mounted = useRef(false);
  const restoreLock = useRef(false);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(false);
  const [restoring, setRestoring] = useState(false);
  const [viewMode, setViewMode] = useState<ViewMode>('timeline');
  const [selectedVersions, setSelectedVersions] = useState<ResumeVersionSummary[]>([]);
  const [comparing, setComparing] = useState(false);

  useEffect(() => { setPage(1); }, [resumeId]);

  /** 抽屉打开时加载版本列表 */
  useEffect(() => {
    if (open && resumeId) {
      setLoading(true);
      fetchVersions(resumeId, page)
        .then(() => setLoading(false))
        .catch(() => {
          setLoading(false);
          message.error('加载版本历史失败');
        });
    }
  }, [open, resumeId, fetchVersions, page]);

  /** 抽屉关闭时重置状态 */
  useEffect(() => {
    if (!open) {
      setPage(1);
      setViewMode('timeline');
      setSelectedVersions([]);
    }
  }, [open]);

  /** 选中 2 个版本后自动触发对比 */
  useEffect(() => {
    if (selectedVersions.length === 2 && viewMode === 'compare') {
      const [v1, v2] = [...selectedVersions].sort(
        (a, b) => a.versionNumber - b.versionNumber,
      );
      setComparing(true);
      compareVersions({
        left: { resumeId, versionId: v1.id },
        right: { resumeId, versionId: v2.id },
      })
        .catch(() => {
          message.error('版本对比失败，请稍后重试');
          setSelectedVersions([]);
        })
        .finally(() => setComparing(false));
    }
  }, [selectedVersions, viewMode, resumeId, compareVersions]);

  /** 恢复至指定历史版本 */
  const handleRestore = (version: ResumeVersionSummary) => {
    if (version.changeType === 'CURRENT' || version.id === null) return;

    const scope = captureAccountScope();
    const current = () => mounted.current && scope.isCurrent();
    Modal.confirm({
      title: '确认恢复',
      content: `确定要恢复到 v${version.versionNumber} 版本吗？当前未保存的更改将会丢失。`,
      okText: '确认恢复',
      okType: 'danger',
      cancelText: '取消',
      onOk: async () => {
        if (restoreLock.current || !current()) return;
        restoreLock.current = true;
        setRestoring(true);
        try {
          const saved = await restoreVersion(resumeId, version.id as number, revision);
          if (!current()) return;
          message.success(`已恢复到 v${version.versionNumber} 版本`);
          onRestored(saved);
        } catch (error: unknown) {
          if (!current()) return;
          if (error instanceof ApiError && error.status === 409) onConflict();
          else message.error(error instanceof Error ? error.message : '恢复失败');
        } finally {
          restoreLock.current = false;
          if (current()) setRestoring(false);
        }
      },
    });
  };

  /** 进入对比模式 */
  const handleEnterCompareMode = () => {
    setViewMode('compare');
    setSelectedVersions([]);
    useResumeStore.setState({ compareResult: null });
  };

  /** 返回时间线模式 */
  const handleBackToTimeline = () => {
    setViewMode('timeline');
    setSelectedVersions([]);
    useResumeStore.setState({ compareResult: null });
  };

  /** 版本勾选处理（最多 2 个） */
  const handleVersionSelect = (version: ResumeVersionSummary, checked: boolean) => {
    if (checked) {
      if (selectedVersions.length >= 2) return;
      setSelectedVersions([...selectedVersions, version]);
    } else {
      setSelectedVersions(selectedVersions.filter((v) => v.id !== version.id));
    }
  };

  /** 获取选择提示文案 */
  const getSelectionHint = (): string | null => {
    if (selectedVersions.length === 0) return '请勾选两个版本进行对比';
    if (selectedVersions.length === 1) return '请再选择一个版本';
    return null;
  };

  /** 判断是否为当前版本 */
  const isCurrentVersion = (v: ResumeVersionSummary): boolean =>
    v.changeType === 'CURRENT' || v.id === null;

  // ─── 渲染函数 ───

  /** 渲染对比模式下的版本列表（带 Checkbox） */
  const renderCompareList = () => {
    const hint = getSelectionHint();

    return (
      <div>
        <div
          style={{
            marginBottom: 12,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
          }}
        >
          <Button type="text" icon={<ArrowLeftOutlined />} onClick={handleBackToTimeline}>
            返回版本列表
          </Button>
        </div>

        {hint && (
          <Text type="secondary" style={{ display: 'block', marginBottom: 16, fontSize: 13 }}>
            {hint}
          </Text>
        )}

        {versions.map((version) => {
          const isCurrent = isCurrentVersion(version);
          const isSelected = selectedVersions.some((v) => v.id === version.id);
          const isDisabled = selectedVersions.length >= 2 && !isSelected;

          return (
            <div
              key={version.versionNumber}
              style={{
                display: 'flex',
                alignItems: 'flex-start',
                padding: '12px 0',
                borderBottom: '1px solid #f0f0f0',
                opacity: isDisabled ? 0.45 : 1,
                transition: 'opacity 0.2s',
              }}
            >
              <Checkbox
                checked={isSelected}
                disabled={isDisabled}
                onChange={(e) => handleVersionSelect(version, e.target.checked)}
                style={{ marginRight: 12, marginTop: 2 }}
              />
              <div style={{ flex: 1 }}>
                <div style={{ marginBottom: 4 }}>
                  <Text strong style={{ fontSize: 14 }}>
                    {isCurrent ? '当前版本' : `v${version.versionNumber}`}
                  </Text>
                  <Tag color={isCurrent ? 'green' : 'default'} style={{ marginLeft: 8 }}>
                    {isCurrent ? '当前版本' : CHANGE_TYPE_MAP[version.changeType] || version.changeType}
                  </Tag>
                </div>
                <div style={{ fontSize: 13, color: '#555', marginBottom: 2 }}>
                  {version.changeNote || (isCurrent ? '当前保存的简历内容' : '无变更说明')}
                </div>
                {isCurrent ? (
                  <Text type="secondary" style={{ fontSize: 12 }}>
                    最近保存的版本
                  </Text>
                ) : (
                  version.createdAt && (
                    <Text type="secondary" style={{ fontSize: 12 }}>
                      {dayjs(version.createdAt).format('YYYY-MM-DD HH:mm')}
                    </Text>
                  )
                )}
              </div>
            </div>
          );
        })}
      </div>
    );
  };

  /** 渲染时间线模式下的单个节点 */
  const renderTimelineItem = (version: ResumeVersionSummary) => {
    const isCurrent = isCurrentVersion(version);
    const changeLabel = CHANGE_TYPE_MAP[version.changeType] || version.changeType;

    return (
      <Timeline.Item key={version.versionNumber}>
        {/* 版本号 + 变更类型标签 */}
        <div style={{ marginBottom: 6 }}>
          <Text strong style={{ fontSize: 14 }}>
            {isCurrent ? '当前版本' : `v${version.versionNumber}`}
          </Text>
          <Tag color={isCurrent ? 'green' : 'default'} style={{ marginLeft: 8 }}>
            {isCurrent ? '当前版本' : changeLabel}
          </Tag>
        </div>

        {/* 变更说明 */}
        <div style={{ fontSize: 13, color: '#555', marginBottom: 4 }}>
          {version.changeNote || (isCurrent ? '当前保存的简历内容' : '无变更说明')}
        </div>

        {/* 时间 / 当前版本提示 */}
        {isCurrent ? (
          <Text type="secondary" style={{ fontSize: 12 }}>
            最近保存的版本
          </Text>
        ) : (
          version.createdAt && (
            <Text type="secondary" style={{ fontSize: 12 }}>
              {dayjs(version.createdAt).format('YYYY-MM-DD HH:mm')}
            </Text>
          )
        )}

        {/* 恢复按钮（仅非当前版本显示） */}
        {!isCurrent && (
          <div style={{ marginTop: 8 }}>
            <Button
              type="link"
              size="small"
              icon={<RollbackOutlined />}
              onClick={() => handleRestore(version)}
              loading={restoring}
            >
              恢复到此版本
            </Button>
          </div>
        )}
      </Timeline.Item>
    );
  };

  // ─── 主渲染 ───

  /** 根据模式渲染抽屉内容 */
  const renderContent = () => {
    // 对比模式 + 已有结果 → 展示对比视图
    if (viewMode === 'compare' && compareResult) {
      return <VersionCompareView comparison={compareResult} onBack={handleBackToTimeline} />;
    }

    return (
      <Spin spinning={loading || comparing}>
        {/* 时间线模式顶部操作栏：版本对比入口 */}
        {viewMode === 'timeline' && versions.length >= 2 && (
          <div style={{ marginBottom: 16, textAlign: 'right' }}>
            <Button type="text" icon={<DiffOutlined />} onClick={handleEnterCompareMode}>
              版本对比
            </Button>
          </div>
        )}

        {/* 对比选择模式 */}
        {viewMode === 'compare'
          ? renderCompareList()
          : versions.length === 0 && !loading
            ? (
              <div
                style={{
                  textAlign: 'center',
                  padding: '40px 0',
                  color: '#999',
                  fontSize: 14,
                }}
              >
                暂无版本历史
              </div>
            )
            : (
              <Timeline style={{ paddingTop: 8 }}>
                {versions.map(renderTimelineItem)}
              </Timeline>
            )}
      </Spin>
    );
  };

  const drawerTitle =
    viewMode === 'compare' && compareResult
      ? `版本对比 - ${resumeName}`
      : `版本历史 - ${resumeName}`;

  const drawerWidth = viewMode === 'compare' && compareResult ? 960 : 420;

  return (
    <Drawer
      title={drawerTitle}
      open={open}
      onClose={() => { if (!restoreLock.current) onClose(); }}
      closable={!restoring}
      maskClosable={!restoring}
      keyboard={!restoring}
      width={drawerWidth}
      destroyOnClose
    >
      {renderContent()}
      <Pagination current={page} pageSize={20} total={versionTotal} showSizeChanger={false} onChange={setPage} />
    </Drawer>
  );
}

export default VersionHistoryDrawer;
