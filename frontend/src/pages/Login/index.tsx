import { useState } from 'react';
import { Form, Input, Button, message } from 'antd';
import { PlayCircleOutlined, LockOutlined, UserOutlined } from '@ant-design/icons';
import AuthBrand from './AuthBrand';
import styles from './auth.module.css';
import { Link, useNavigate } from 'react-router-dom';
import { useAuthStore } from '@/stores/authStore';
import { authService } from '@/api/modules/auth';
import type { LoginRequest } from '@/types/api.d';

function Login() {
  const navigate = useNavigate();
  const [loading, setLoading] = useState(false);
  const [demoLoading, setDemoLoading] = useState(false);
  const [form] = Form.useForm();

  const handleSubmit = async (values: LoginRequest) => {
    if (loading) return;
    setLoading(true);
    try {
      const response = await authService.login(values);
      useAuthStore.getState().setSession(response);
      navigate('/interview');
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
      const response = await authService.demoLogin();
      useAuthStore.getState().setSession(response);
      message.success('已创建独立演示空间');
      navigate('/interview');
    } catch (error) {
      message.error(error instanceof Error ? error.message : '创建演示空间失败');
    } finally {
      setDemoLoading(false);
    }
  };

  return (
    <main className={styles.page}>
      <div className={styles.layout}>
        <AuthBrand />
        <section className={styles.form} aria-labelledby="login-heading">
          <header className={styles.heading}>
            <h2 id="login-heading">登录工作台</h2>
            <p>登录后继续你的面试练习与复盘。</p>
          </header>
          <Form form={form} onFinish={handleSubmit} layout="vertical" size="large" requiredMark={false}>
            <Form.Item name="username" label="用户名" rules={[
              { required: true, message: '请输入用户名' },
              { pattern: /^[a-zA-Z0-9_]{4,24}$/, message: '用户名必须是4-24位字母、数字或下划线' },
            ]}>
              <Input prefix={<UserOutlined aria-hidden="true" />} autoComplete="username" placeholder="4–24 位字母、数字或下划线" />
            </Form.Item>
            <Form.Item name="password" label="密码" rules={[
              { required: true, message: '请输入密码' },
              { min: 6, max: 64, message: '密码长度6-64位' },
            ]}>
              <Input.Password prefix={<LockOutlined aria-hidden="true" />} autoComplete="current-password" placeholder="请输入密码" />
            </Form.Item>
            <Form.Item>
              <Button type="primary" htmlType="submit" loading={loading} disabled={demoLoading} className={styles.submit}>登录</Button>
            </Form.Item>
          </Form>
          <p className={styles.switch}>没有账号？<Link to="/register">注册账号</Link></p>
          <div className={styles.demo}>
            <Button icon={<PlayCircleOutlined aria-hidden="true" />} onClick={handleDemoLogin} loading={demoLoading} disabled={loading}>进入独立演示空间</Button>
            <p className={styles.demoNote}>无需密码，每次进入都会创建限时空间。长期保存材料请注册账号。</p>
          </div>
        </section>
      </div>
    </main>
  );
}

export default Login;
