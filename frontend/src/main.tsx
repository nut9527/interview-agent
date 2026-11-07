import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { RouterProvider } from 'react-router-dom';
import { ConfigProvider, type ThemeConfig } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import router from '@/router';
import './index.css';
import { useAuthStore } from '@/stores/authStore';

window.addEventListener('storage', (event) => {
  if (event.storageArea === localStorage && (event.key === null || event.key === 'token' || event.key === 'user')) {
    useAuthStore.getState().loadUserFromStorage();
  }
});

const workspaceTheme: ThemeConfig = {
  token: {
    colorPrimary: '#215544', colorInfo: '#215544', colorLink: '#28664c',
    colorPrimaryHover: '#347158', colorPrimaryActive: '#173e36',
    colorPrimaryBg: '#edf5e9', colorPrimaryBgHover: '#dfecd9',
    colorText: '#172e28', colorTextSecondary: '#475e54', colorTextTertiary: '#64766b',
    colorBorder: '#bdcbc1', colorBorderSecondary: '#dce4dc',
    colorBgLayout: '#f4f6f2', colorBgContainer: '#ffffff',
    colorError: '#a33a2b', colorSuccess: '#2f6b4f', colorWarning: '#9a6a1e',
    borderRadius: 8, borderRadiusLG: 12, borderRadiusSM: 6, borderRadiusXS: 4,
    controlHeight: 38, controlHeightLG: 44, fontSize: 14,
    fontSizeHeading1: 32, fontSizeHeading2: 22, fontSizeHeading3: 17,
    fontFamily: 'var(--sans)', lineHeight: 1.65,
    boxShadowSecondary: '0 8px 32px rgba(23, 62, 54, .12)',
    motion: false,
  },
  components: {
    Button: { primaryShadow: 'none', defaultShadow: 'none', dangerShadow: 'none' },
    Menu: { itemBg: 'transparent', itemSelectedBg: '#e9f1e4', itemSelectedColor: '#215544', itemHeight: 42 },
    Tag: { defaultBg: '#f1f5ee', defaultColor: '#475e54' },
    Progress: { defaultColor: '#4d7956', remainingColor: '#e7eee4' },
    Select: { optionSelectedBg: '#e9f1e4' },
  },
};

// 静态消息和确认弹窗也需使用共享主题。
ConfigProvider.config({
  holderRender: children => <ConfigProvider locale={zhCN} theme={workspaceTheme} button={{ autoInsertSpace: false }} wave={{ disabled: true }}>{children}</ConfigProvider>,
});

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ConfigProvider locale={zhCN} theme={workspaceTheme} button={{ autoInsertSpace: false }} wave={{ disabled: true }}>
      <RouterProvider router={router} fallbackElement={<div role="status">正在加载页面…</div>} />
    </ConfigProvider>
  </StrictMode>,
);
