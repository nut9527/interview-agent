import { useState, useEffect } from 'react';
import { Layout, Menu, Button, Tag, Modal } from 'antd';
import {
  FileTextOutlined,
  AimOutlined,
  MessageOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { Link, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { authService } from '@/api/modules/auth';
import { useAuthStore } from '@/stores/authStore';
import { useResumeEditorStatus } from '@/stores/resumeEditorStatus';
import { captureAccountScope } from '@/api/accountScope';
import { hasResumeDrafts } from '@/pages/Resumes/Editor/resumeDraft';

import styles from './MainLayout.module.css';

const { Sider, Header, Content } = Layout;

const menuItems = [
  { key: '/interview', label: '面试训练室', icon: <MessageOutlined /> },
  { key: '/resumes', label: '简历管理', icon: <FileTextOutlined /> },
  { key: '/target-jobs', label: '目标岗位', icon: <AimOutlined /> },
  { key: '/profile', label: '账号设置', icon: <UserOutlined /> },
];

function MainLayout() {
  const navigate = useNavigate();
  const location = useLocation();
  const selectedKey = menuItems.find(item => location.pathname === item.key
    || location.pathname.startsWith(item.key + '/'))?.key;
  const user = useAuthStore((state) => state.user);
  const [loading, setLoading] = useState(false);
  const [collapsed, setCollapsed] = useState(false);

  useEffect(() => {
    const mobileQuery = window.matchMedia('(max-width: 767px)');
    const syncCollapsedState = (event: MediaQueryListEvent | MediaQueryList) => {
      setCollapsed(event.matches);
    };
    syncCollapsedState(mobileQuery);
    mobileQuery.addEventListener('change', syncCollapsedState);
    return () => mobileQuery.removeEventListener('change', syncCollapsedState);
  }, []);

  const handleLogout = async () => {
    if (loading) return;
    const scope = captureAccountScope();
    setLoading(true);
    try {
      await authService.logout();
    } catch {
      // Local logout still works offline, but an old response cannot log out a new identity.
    } finally {
      if (scope.isCurrent()) {
        setLoading(false);
        useAuthStore.getState().clearUser();
        navigate('/login');
      }
    }
  };

  const confirmLogout = () => {
    if (!useResumeEditorStatus.getState().unsaved && !hasResumeDrafts(useAuthStore.getState().user?.id)) { void handleLogout(); return; }
    const scope = captureAccountScope();
    Modal.confirm({ title: '未保存的简历修改将被清理',
      content: '退出登录会清除本标签页的简历草稿。正在保存的请求可能已到达服务器，下次登录后请核对列表。',
      okText: '清理草稿并退出', okType: 'danger', cancelText: '继续编辑',
      onOk: () => { if (scope.isCurrent()) return handleLogout(); },
    });
  };

  return (
    <Layout style={{ height: '100vh', overflow: 'clip' }}>
      <Sider
        width={200}
        breakpoint="md"
        collapsedWidth={64}
        collapsible
        collapsed={collapsed}
        trigger={null}
        onCollapse={setCollapsed}
        className={styles.sider}
      >
        <div className={styles.logoWrap}>
          <span className={styles.logoText}>职智达</span>
        </div>

        <Menu
          mode="inline"
          selectedKeys={selectedKey ? [selectedKey] : []}
          items={menuItems.map((item) => ({
            key: item.key,
            icon: item.icon,
            label: <Link to={item.key}>{item.label}</Link>,
          }))}
          style={{ borderRight: 0 }}
        />
      </Sider>

      <Layout style={{ overflow: 'clip' }}>
        <Header className={styles.header}>
          <div />
          <div className={styles.userSection}>
            <span className={styles.userName}>
              {user?.displayName || user?.username || '用户'}
            </span>
            {user?.demo && <Tag>独立演示</Tag>}
            <Button
              type="text"
              loading={loading}
              onClick={confirmLogout}
            >
              退出登录
            </Button>
          </div>
        </Header>
        <Content className={styles.content}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  );
}

export default MainLayout;
