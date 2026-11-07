import { useState, useEffect, useRef } from 'react';
import { Layout, Menu, Button, Tag, Modal, Dropdown, Tooltip } from 'antd';
import {
  FileTextOutlined,
  ReadOutlined,
  MessageOutlined,
  ApartmentOutlined,
  BookOutlined,
  DownOutlined,
  FlagOutlined,
  SettingOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
} from '@ant-design/icons';
import { Link, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { authService } from '@/api/modules/auth';
import { useAuthStore } from '@/stores/authStore';
import { useResumeEditorStatus } from '@/stores/resumeEditorStatus';
import { captureAccountScope } from '@/api/accountScope';
import { hasResumeDrafts } from '@/pages/Resumes/Editor/resumeDraft';

import brandMark from '@/assets/brand-mark.svg';
import styles from './MainLayout.module.css';

const { Sider, Header } = Layout;

const menuItems = [
  { key: '/interview', label: <Link to="/interview">模拟面试</Link>, icon: <MessageOutlined /> },
  { key: '/questions', label: <Link to="/questions">题目与资料</Link>, icon: <ReadOutlined /> },
  { key: 'materials', label: '求职材料', icon: <FileTextOutlined />, children: [
    { key: '/resumes', label: <Link to="/resumes">简历</Link> },
    { key: '/target-jobs', label: <Link to="/target-jobs">目标岗位</Link> },
  ] },
];

const trainingPages = [
  { path: '/interview?plan=1', title: '个人训练计划', icon: <ApartmentOutlined /> },
  { path: '/learning/goals', title: '训练目标', icon: <FlagOutlined /> },
  { path: '/learning/memory', title: '学习观察', icon: <BookOutlined /> },
];

const pageTitles: Record<string, string> = {
  '/interview': '模拟面试', '/questions': '精选题目', '/resumes': '简历',
  '/resumes/edit': '简历编辑', '/target-jobs': '目标岗位', '/profile': '账号设置',
  '/questions/practice': '组题练习', '/knowledge': '资料与出题', '/knowledge/history': '问答记录',
  '/knowledge/practice': '我的题目', '/knowledge/feedback': '反馈记录',
  '/learning/goals': '训练目标', '/learning/memory': '学习观察',
};

function currentPageTitle(path: string, search: string) {
  if (path === '/interview' && new URLSearchParams(search).has('plan')) return '个人训练计划';
  if (path.endsWith('/practices')) return '历次练习';
  return pageTitles[path] ?? '题目详情';
}

function MainLayout() {
  const navigate = useNavigate();
  const location = useLocation();
  const selectedKey = location.pathname.startsWith('/knowledge') ? '/questions' : menuItems.flatMap(item => item.children ?? [item]).find(item => location.pathname === item.key
    || location.pathname.startsWith(item.key + '/'))?.key;
  const title = currentPageTitle(location.pathname, location.search);
  const content = useRef<HTMLDivElement>(null);
  useEffect(() => {
    document.title = title + ' · 职智达';
    content.current?.scrollTo({ top: 0 });
  }, [location.pathname, title]);
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
      // 离线时仍可本地退出，但旧响应不能注销新身份。
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
    <Layout className={styles.shell}>
      <Sider
        width={224}
        breakpoint="md"
        collapsedWidth={64}
        collapsible
        collapsed={collapsed}
        trigger={null}
        onCollapse={setCollapsed}
        className={styles.sider} data-collapsed={collapsed}
      >
        <Link to="/interview" className={styles.logoWrap} aria-label="职智达工作台">
          <img src={brandMark} width="36" height="36" alt="" />
          {!collapsed && <span className={styles.logoText}>职智达</span>}
        </Link>
        <div className={styles.navBody}>
          {!collapsed && <div className={styles.groupLabel}>工作空间</div>}
          <Menu mode="inline" selectedKeys={selectedKey && title !== '个人训练计划' ? [selectedKey] : []}
            defaultOpenKeys={['materials']} items={menuItems} inlineIndent={15}
            style={{ borderInlineEnd: 0 }} />
          <nav aria-label="学习记录" className={styles.studyNav}>
            {!collapsed && <div className={styles.groupLabel}>学习记录</div>}
            {trainingPages.map(item => (
              <Tooltip key={item.path} title={collapsed ? item.title : undefined} placement="right">
                <Link to={item.path} className={styles.navLink} aria-label={item.title}
                  aria-current={title === item.title ? 'page' : undefined}>
                  {item.icon}{!collapsed && item.title}
                </Link>
              </Tooltip>
            ))}
          </nav>
        </div>
        <div className={styles.siderFooter}>
          <Tooltip title={collapsed ? '账号设置' : undefined} placement="right">
            <Link to="/profile" className={styles.navLink} aria-label="账号设置"
              aria-current={location.pathname === '/profile' ? 'page' : undefined}>
              <SettingOutlined />{!collapsed && '账号设置'}
            </Link>
          </Tooltip>
        </div>
      </Sider>

      <Layout className={styles.main}>
        <Header className={styles.header}>
          <div className={styles.location}>
            <Button type="text" aria-label={collapsed ? '展开导航' : '收起导航'} aria-expanded={!collapsed}
              icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
              onClick={() => setCollapsed(value => !value)} />
            <span>工作台</span><span aria-hidden="true" className={styles.slash}>/</span>
            <strong>{title}</strong>
          </div>
          <div className={styles.userSection}>
            {user?.demo && <Tag>独立演示</Tag>}
            <Dropdown trigger={['click']} menu={{ items: [
              { key: 'profile', icon: <SettingOutlined />, label: <Link to="/profile">账号设置</Link> },
              { type: 'divider' },
              { key: 'logout', label: '退出登录', disabled: loading, onClick: confirmLogout },
            ] }}>
              <Button type="text" className={styles.account} loading={loading} aria-label="账号菜单">
                <span aria-hidden="true" className={styles.avatar}>{Array.from(user?.displayName || user?.username || '用')[0]}</span>
                <span className={styles.userName}>{user?.displayName || user?.username || '用户'}</span><DownOutlined className={styles.caret} />
              </Button>
            </Dropdown>
          </div>
        </Header>
        <div className={'ant-layout-content ' + styles.content} ref={content}>
          <div className={styles.canvas}><Outlet /></div>
        </div>
      </Layout>
    </Layout>
  );
}

export default MainLayout;
