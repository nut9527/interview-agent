import { useState } from 'react';
import { Form, Input, Button, message } from 'antd';
import AuthBrand from './AuthBrand';
import styles from './auth.module.css';
import { useNavigate } from 'react-router-dom';
import { useAuthStore } from '@/stores/authStore';
import { authService } from '@/api/modules/auth';
import type { LoginRequest, AuthResponse } from '@/types/api.d';
import { USE_MOCK } from '@/config/runtime';

function Login() {
  const navigate = useNavigate();
  const [loading, setLoading] = useState(false);
  const [demoLoading, setDemoLoading] = useState(false);
  const [form] = Form.useForm();

  const handleSubmit = async (values: LoginRequest) => {
    if (loading) return;
    setLoading(true);
    try {
      if (USE_MOCK) {
        const mockResponse: AuthResponse = {
          token: 'mock-token-123456',
          expiresAt: new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString(),
          user: {
            id: 1,
            username: values.username,
            displayName: '演示用户',
            targetRole: '前端开发实习生',
            skillTags: ['React', 'TypeScript', 'Vue'],
            demo: false,
          },
        };
        useAuthStore.getState().setSession(mockResponse);
        message.success('登录成功（Mock 模式）');
        navigate('/interview');
      } else {
        const response = await authService.login(values);
        useAuthStore.getState().setSession(response);
        navigate('/interview');
      }
    } catch (error) {
      message.error(error instanceof Error ? error.message : '登录失败');
    } finally {
      setLoading(false);
    }
  };

  const handleDemoLogin = async () => {
    if (loading || demoLoading) return;
    setDemoLoading(true);
    try {
      const response: AuthResponse = USE_MOCK
        ? {
            token: `mock-demo-${Date.now()}`,
            expiresAt: new Date(Date.now() + 4 * 60 * 60 * 1000).toISOString(),
            user: {
              id: Date.now(),
              username: 'demo',
              displayName: '演示用户',
              targetRole: 'java后端工程师',
              skillTags: ['Java', 'MySQL', 'Spring Boot', 'Redis', 'Docker', 'React', 'Git'],
              demo: true,
            },
          }
        : await authService.demoLogin();
      useAuthStore.getState().setSession(response);
      message.success('已创建独立演示空间');
      navigate('/interview');
    } catch (error) {
      message.error(error instanceof Error ? error.message : '创建演示空间失败');
    } finally {
      setDemoLoading(false);
    }
  };

  const handleRegisterClick = () => {
    navigate('/register');
  };

  return (
    <main className={styles.page}>
      <div className={styles.layout}>
        <AuthBrand />
        <section className={styles.form} aria-labelledby="login-heading">
          <header className={styles.heading}>
            <h2 id="login-heading">登录</h2>
            <p>继续管理简历、准备岗位与练习面试。</p>
            {USE_MOCK && <span className={styles.mock}>当前使用本地演示数据</span>}
          </header>
          <Form form={form} onFinish={handleSubmit} layout="vertical" size="large" requiredMark={false}>
            <Form.Item name="username" label="用户名" rules={[
              { required: true, message: '请输入用户名' },
              { pattern: /^[a-zA-Z0-9_]{4,24}$/, message: '用户名必须是4-24位字母、数字或下划线' },
            ]}>
              <Input autoComplete="username" placeholder="4–24 位字母、数字或下划线" />
            </Form.Item>
            <Form.Item name="password" label="密码" rules={[
              { required: true, message: '请输入密码' },
              { min: 6, max: 64, message: '密码长度6-64位' },
            ]}>
              <Input.Password autoComplete="current-password" placeholder="请输入密码" />
            </Form.Item>
            <Form.Item>
              <Button type="primary" htmlType="submit" loading={loading} disabled={demoLoading} className={styles.submit}>登录</Button>
            </Form.Item>
          </Form>
          <p className={styles.switch}>没有账号？<Button type="link" onClick={handleRegisterClick}>注册账号</Button></p>
          <div className={styles.demo}>
            <Button type="link" onClick={handleDemoLogin} loading={demoLoading} disabled={loading}>进入独立演示空间 →</Button>
            <p className={styles.demoNote}>无需密码，数据独立，4 小时后自动失效。</p>
          </div>
        </section>
      </div>
    </main>
  );
}

export default Login;
