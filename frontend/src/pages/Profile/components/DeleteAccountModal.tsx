import { useEffect, useState } from 'react';
import { Alert, Form, Input, Modal, Typography, message } from 'antd';
import type { UserProfile } from '@/types/api.d';
import { userService } from '@/api/modules/user';

interface DeleteAccountModalProps {
  open: boolean;
  user: UserProfile;
  onClose: () => void;
  onDeleted: () => void;
}

interface DeleteAccountFormValues {
  username: string;
  currentPassword: string;
}

function DeleteAccountModal({ open, user, onClose, onDeleted }: DeleteAccountModalProps) {
  const [form] = Form.useForm<DeleteAccountFormValues>();
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!open) form.resetFields();
  }, [form, open]);

  const handleSubmit = async () => {
    const values = await form.validateFields();
    setSubmitting(true);
    try {
      await userService.deleteAccount({ currentPassword: values.currentPassword });
      message.success('账户及个人数据已删除');
      onDeleted();
    } catch (error) {
      message.error(error instanceof Error ? error.message : '注销账户失败');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      title="永久注销账户"
      open={open}
      okText="永久删除我的账户"
      cancelText="保留账户"
      okButtonProps={{ danger: true }}
      confirmLoading={submitting}
      onOk={handleSubmit}
      onCancel={onClose}
      destroyOnClose
    >
      <Alert
        type="error"
        showIcon
        message="此操作无法撤销"
        description="你的账户、登录会话、简历、目标岗位和面试记录都会被永久删除。"
        style={{ marginBottom: 20 }}
      />
      <Typography.Paragraph>
        输入用户名 <Typography.Text code>{user.username}</Typography.Text> 和当前密码以确认。
      </Typography.Paragraph>
      <Form form={form} layout="vertical" requiredMark={false}>
        <Form.Item
          name="username"
          label="确认用户名"
          rules={[
            { required: true, message: '请输入用户名' },
            {
              validator: (_, value) => value === user.username
                ? Promise.resolve()
                : Promise.reject(new Error('用户名不匹配')),
            },
          ]}
        >
          <Input autoComplete="off" placeholder={user.username} />
        </Form.Item>
        <Form.Item
          name="currentPassword"
          label="当前密码"
          rules={[{ required: true, message: '请输入当前密码' }]}
        >
          <Input.Password autoComplete="current-password" />
        </Form.Item>
      </Form>
    </Modal>
  );
}

export default DeleteAccountModal;
