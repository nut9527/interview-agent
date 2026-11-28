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
  /** 直接交付恢复后的快照及 revision，父编辑器以此更新内容和并发基线。 */
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
  const { versions, versionTotal, fetchVersions, restoreVersion, compareResult, compareVersions } = useResumeStore();

  const mounted = useRef(false);
  const restoreLock = useRef(false);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(false);
  const [restoring, setRestoring] = useState(false);
  const [viewMode, setViewMode] = useState<ViewMode>('timeline');
  const [selectedVersions, setSelectedVersions] = useState<ResumeVersionSummary[]>([]);
  const [comparing, setComparing] = useState(false);

  useEffect(() => {
    setPage(1);
  }, [resumeId]);

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

  useEffect(() => {
    if (!open) {
      setPage(1);
      setViewMode('timeline');
      setSelectedVersions([]);
    }
  }, [open]);

  // 按版本号排列左右两侧，避免新旧方向随勾选顺序变化。
  useEffect(() => {
    if (selectedVersions.length === 2 && viewMode === 'compare') {
      const [v1, v2] = [...selectedVersions].sort((a, b) => a.versionNumber - b.versionNumber);
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

  const handleRestore = (version: ResumeVersionSummary) => {
    if (version.changeType === 'CURRENT' || version.id === null) return;

    // 确认弹窗可能晚于账号切换或组件卸载；确认动作和返回结果都必须验证原作用域。
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

  const handleEnterCompareMode = () => {
    setViewMode('compare');
    setSelectedVersions([]);
    useResumeStore.setState({ compareResult: null });
  };

  const handleBackToTimeline = () => {
    setViewMode('timeline');
    setSelectedVersions([]);
    useResumeStore.setState({ compareResult: null });
  };

  const handleVersionSelect = (version: ResumeVersionSummary, checked: boolean) => {
    if (checked) {
      if (selectedVersions.length >= 2) return;
      setSelectedVersions([...selectedVersions, version]);
    } else {
      setSelectedVersions(selectedVersions.filter((v) => v.id !== version.id));
    }
  };

  const getSelectionHint = (): string | null => {
    if (selectedVersions.length === 0) return '请勾选两个版本进行对比';
    if (selectedVersions.length === 1) return '请再选择一个版本';
    return null;
  };

  // 当前保存内容使用虚拟版本项，没有可用于恢复的历史版本 id。
  const isCurrentVersion = (v: ResumeVersionSummary): boolean => v.changeType === 'CURRENT' || v.id === null;

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

  const renderTimelineItem = (version: ResumeVersionSummary) => {
    const isCurrent = isCurrentVersion(version);
    const changeLabel = CHANGE_TYPE_MAP[version.changeType] || version.changeType;

    return (
      <Timeline.Item key={version.versionNumber}>
        <div style={{ marginBottom: 6 }}>
          <Text strong style={{ fontSize: 14 }}>
            {isCurrent ? '当前版本' : `v${version.versionNumber}`}
          </Text>
          <Tag color={isCurrent ? 'green' : 'default'} style={{ marginLeft: 8 }}>
            {isCurrent ? '当前版本' : changeLabel}
          </Tag>
        </div>

        <div style={{ fontSize: 13, color: '#555', marginBottom: 4 }}>
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

  const renderContent = () => {
    if (viewMode === 'compare' && compareResult) {
      return <VersionCompareView comparison={compareResult} onBack={handleBackToTimeline} />;
    }

    return (
      <Spin spinning={loading || comparing}>
        {viewMode === 'timeline' && versions.length >= 2 && (
          <div style={{ marginBottom: 16, textAlign: 'right' }}>
            <Button type="text" icon={<DiffOutlined />} onClick={handleEnterCompareMode}>
              版本对比
            </Button>
          </div>
        )}

        {viewMode === 'compare' ? (
          renderCompareList()
        ) : versions.length === 0 && !loading ? (
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
        ) : (
          <Timeline style={{ paddingTop: 8 }}>{versions.map(renderTimelineItem)}</Timeline>
        )}
      </Spin>
    );
  };

  const drawerTitle = viewMode === 'compare' && compareResult ? `版本对比 - ${resumeName}` : `版本历史 - ${resumeName}`;

  const drawerWidth = viewMode === 'compare' && compareResult ? 960 : 420;

  return (
    <Drawer
      title={drawerTitle}
      open={open}
      onClose={() => {
        if (!restoreLock.current) onClose();
      }}
      closable={!restoring}
      maskClosable={!restoring}
      keyboard={!restoring}
      width={drawerWidth}
      destroyOnHidden
    >
      {renderContent()}
      <Pagination current={page} pageSize={20} total={versionTotal} showSizeChanger={false} onChange={setPage} />
    </Drawer>
  );
}

export default VersionHistoryDrawer;
