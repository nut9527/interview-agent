import { useState } from 'react';
import { Form, Input, Button, message } from 'antd';
import { LockOutlined, UserOutlined } from '@ant-design/icons';
import AuthBrand from '../Login/AuthBrand';
import styles from '../Login/auth.module.css';
import { Link, useNavigate } from 'react-router-dom';
import { ApiError } from '@/api/ApiError';
import { authService } from '@/api/modules/auth';
import type { RegisterRequest } from '@/types/api.d';

function Register() {
  const navigate = useNavigate();
  const [loading, setLoading] = useState(false);
  const [form] = Form.useForm();

  const handleSubmit = async (values: RegisterRequest & { confirmPassword: string }) => {
    if (loading) return;
    setLoading(true);
    try {
      await authService.register(values);
      message.success('注册成功，请登录');
      navigate('/login');
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        message.error('用户名已存在，请更换用户名');
      } else {
        const msg =
          (error instanceof Error ? error.message : '注册失败，请稍后重试');
        message.error(msg);
      }
    } finally {
      setLoading(false);
    }
  };

  return (
    <main className={styles.page}>
      <div className={styles.layout}>
        <AuthBrand />
        <section className={styles.form} aria-labelledby="register-heading">
          <header className={styles.heading}>
            <h2 id="register-heading">创建账号</h2>
            <p>保存材料，记录每次练习。</p>
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
              <Input.Password prefix={<LockOutlined aria-hidden="true" />} autoComplete="new-password" placeholder="6–64 位密码" />
            </Form.Item>
            <Form.Item name="confirmPassword" label="确认密码" dependencies={['password']} rules={[
              { required: true, message: '请确认密码' },
              ({ getFieldValue }) => ({
                validator(_, value: string) {
                  if (!value || getFieldValue('password') === value) return Promise.resolve();
                  return Promise.reject(new Error('两次输入的密码不一致'));
                },
              }),
            ]}>
              <Input.Password prefix={<LockOutlined aria-hidden="true" />} autoComplete="new-password" placeholder="再次输入密码" />
            </Form.Item>
            <Form.Item>
              <Button type="primary" htmlType="submit" loading={loading} className={styles.submit}>注册账号</Button>
            </Form.Item>
          </Form>
          <p className={styles.switch}>已有账号？<Link to="/login">登录</Link></p>
        </section>
      </div>
    </main>
  );
}

export default Register;
