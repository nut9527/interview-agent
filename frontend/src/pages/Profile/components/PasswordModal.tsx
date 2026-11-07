import { useEffect, useState } from 'react';
import { Alert, Form, Input, Modal, Progress, Typography, message } from 'antd';
import { LockOutlined } from '@ant-design/icons';
import type { ChangePasswordRequest } from '@/types/api.d';
import { userService } from '@/api/modules/user';

interface PasswordModalProps {
  open: boolean;
  onClose: () => void;
  onChanged: () => void;
}

interface PasswordFormValues extends ChangePasswordRequest {
  confirmPassword: string;
}

function passwordStrength(password: string): number {
  if (!password) return 0;
  let score = password.length >= 8 ? 35 : 15;
  if (/[A-Za-z]/.test(password) && /\d/.test(password)) score += 35;
  if (/[^A-Za-z0-9]/.test(password)) score += 20;
  if (password.length >= 12) score += 10;
  return Math.min(score, 100);
}

function PasswordModal({ open, onClose, onChanged }: PasswordModalProps) {
  const [form] = Form.useForm<PasswordFormValues>();
  const [submitting, setSubmitting] = useState(false);
  const newPassword = Form.useWatch('newPassword', form) || '';
  const strength = passwordStrength(newPassword);

  useEffect(() => {
    if (!open) form.resetFields();
  }, [form, open]);

  const handleSubmit = async () => {
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      await userService.changePassword({
        currentPassword: values.currentPassword,
        newPassword: values.newPassword,
      });
      message.success('密码已更新，请重新登录');
      onChanged();
    } catch (error) {
      message.error(error instanceof Error ? error.message : '修改密码失败');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      title="更新登录密码"
      open={open}
      okText="更新并重新登录"
      cancelText="取消"
      confirmLoading={submitting}
      onOk={handleSubmit}
      onCancel={onClose}
      destroyOnClose
    >
      <Alert
        type="info"
        showIcon
        message="修改成功后，所有已登录设备都会退出。"
        style={{ marginBottom: 20 }}
      />
      <Form form={form} layout="vertical" requiredMark={false}>
        <Form.Item
          name="currentPassword"
          label="当前密码"
          rules={[{ required: true, message: '请输入当前密码' }]}
        >
          <Input.Password prefix={<LockOutlined />} autoComplete="current-password" />
        </Form.Item>
        <Form.Item
          name="newPassword"
          label="新密码"
          rules={[
            { required: true, message: '请输入新密码' },
            { min: 8, max: 64, message: '密码长度必须为 8-64 位' },
            { pattern: /^(?=.*[A-Za-z])(?=.*\d).+$/, message: '至少包含一个字母和一个数字' },
          ]}
        >
          <Input.Password prefix={<LockOutlined />} autoComplete="new-password" />
        </Form.Item>
        {newPassword && (
          <div style={{ margin: '-8px 0 16px' }}>
            <Progress
              percent={strength}
              showInfo={false}
              size="small"
              strokeColor="var(--amber)"
            />
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              建议使用 12 位以上，并混合字母、数字和符号
            </Typography.Text>
          </div>
        )}
        <Form.Item
          name="confirmPassword"
          label="确认新密码"
          dependencies={['newPassword']}
          rules={[
            { required: true, message: '请再次输入新密码' },
            ({ getFieldValue }) => ({
              validator(_, value) {
                return !value || getFieldValue('newPassword') === value
                  ? Promise.resolve()
                  : Promise.reject(new Error('两次输入的密码不一致'));
              },
            }),
          ]}
        >
          <Input.Password prefix={<LockOutlined />} autoComplete="new-password" />
        </Form.Item>
      </Form>
    </Modal>
  );
}

export default PasswordModal;
