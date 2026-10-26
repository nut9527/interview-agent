import { useState } from 'react';
import { Alert, Button, Descriptions } from 'antd';
import { useNavigate } from 'react-router-dom';
import { useAuthStore } from '@/stores/authStore';
import PasswordModal from './components/PasswordModal';
import DeleteAccountModal from './components/DeleteAccountModal';
import styles from './index.module.css';

export default function Profile() {
  const user = useAuthStore(state => state.user);
  const [passwordOpen, setPasswordOpen] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const navigate = useNavigate();
  if (!user) return null;
  const reset = () => { useAuthStore.getState().clearUser(); navigate('/login', { replace: true }); };
  return <main className={styles.page}>
    <header className={styles.heading}><h1>账号设置</h1>
      <p>管理登录与账号安全。面试材料在训练室导入和选择。</p></header>
    <section className={styles.section} aria-labelledby="account-info-heading">
      <h2 id="account-info-heading">账号信息</h2>
      <Descriptions layout="vertical" column={{ xs: 1, sm: 2 }} colon={false} items={[
        { key: 'username', label: '用户名', children: user.username },
        { key: 'displayName', label: '称呼', children: user.displayName || user.username },
        { key: 'kind', label: '账号类型', children: user.demo ? '独立演示空间' : '个人账号' },
      ]} />
    </section>
    <section className={styles.section} aria-labelledby="account-security-heading">
      <h2 id="account-security-heading">账号安全</h2>
      {user.demo ? <Alert type="info" showIcon message="当前为独立演示空间" description="导入材料和面试记录只属于本次访问，演示到期后会自动清理。" />
        : <div className={styles.actionRow}><p>定期更新密码，保护你的面试材料与记录。</p><Button onClick={() => setPasswordOpen(true)}>修改密码</Button></div>}
    </section>
    {!user.demo && <section className={styles.section} aria-labelledby="account-delete-heading">
      <h2 id="account-delete-heading">注销账号</h2>
      <div className={styles.actionRow}><p>永久删除账号及个人数据，删除后无法恢复。</p>
        <Button danger onClick={() => setDeleteOpen(true)}>注销账号</Button></div>
    </section>}
    <Button className={styles.back} onClick={() => navigate('/interview')}>返回面试训练室</Button>
    {!user.demo && <><PasswordModal open={passwordOpen} onClose={() => setPasswordOpen(false)} onChanged={reset} />
      <DeleteAccountModal open={deleteOpen} user={user} onClose={() => setDeleteOpen(false)} onDeleted={reset} /></>}
  </main>;
}
