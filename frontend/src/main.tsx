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

const paperInkTheme: ThemeConfig = {
  token: {
    colorPrimary: '#1a1a1a', colorInfo: '#1a1a1a', colorLink: '#1a1a1a',
    colorPrimaryHover: '#393730', colorPrimaryActive: '#1a1a1a',
    colorPrimaryBg: '#f3f0ea', colorPrimaryBgHover: '#e6e2da',
    colorText: '#1a1a1a', colorTextSecondary: '#57534c', colorTextTertiary: '#8c867d',
    colorBorder: '#d3cec4', colorBorderSecondary: '#e6e2da',
    colorBgLayout: '#faf9f6', colorBgContainer: '#ffffff',
    colorError: '#a33a2b', colorSuccess: '#2f6b4f', colorWarning: '#9a6a1e',
    colorInfoBg: '#faf9f6', colorInfoBorder: '#e6e2da',
    colorSuccessBg: '#faf9f6', colorSuccessBorder: '#e6e2da',
    colorWarningBg: '#faf9f6', colorWarningBorder: '#e6e2da',
    colorErrorBg: '#faf9f6', colorErrorBorder: '#e6e2da',
    borderRadius: 4, borderRadiusLG: 4, borderRadiusSM: 2, borderRadiusXS: 2,
    controlHeight: 36, controlHeightLG: 40, fontSize: 14, fontSizeLG: 15,
    fontSizeHeading1: 28, fontSizeHeading2: 22, fontSizeHeading3: 17,
    fontFamily: 'var(--sans)', lineHeight: 1.75,
    boxShadow: 'none', boxShadowSecondary: 'none', boxShadowTertiary: 'none',
    motion: false,
    motionDurationFast: '0.12s', motionDurationMid: '0.14s', motionDurationSlow: '0.16s',
  },
  components: {
    Button: { primaryShadow: 'none', defaultShadow: 'none', dangerShadow: 'none' },
    Menu: { itemBg: 'transparent', itemSelectedBg: '#f1eee7', itemSelectedColor: '#1a1a1a', itemHeight: 40 },
    Tag: { defaultBg: '#f3f0ea', defaultColor: '#57534c' },
    Progress: { defaultColor: '#b45309', remainingColor: '#eeeae2' },
    Input: { activeShadow: 'none', errorActiveShadow: 'none', warningActiveShadow: 'none' },
    Select: { activeOutlineColor: 'transparent', optionSelectedBg: '#f1eee7' },
    Modal: { boxShadow: '0 12px 32px rgba(26,26,26,.10)' },
  },
};

// Static messages and confirmation dialogs also need the shared theme.
ConfigProvider.config({
  holderRender: children => <ConfigProvider locale={zhCN} theme={paperInkTheme} button={{ autoInsertSpace: false }} wave={{ disabled: true }}>{children}</ConfigProvider>,
});

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ConfigProvider locale={zhCN} theme={paperInkTheme} button={{ autoInsertSpace: false }} wave={{ disabled: true }}>
      <RouterProvider router={router} fallbackElement={<div role="status">正在加载页面…</div>} />
    </ConfigProvider>
  </StrictMode>,
);
