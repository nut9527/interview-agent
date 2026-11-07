import { Link, Outlet, useLocation } from 'react-router-dom';
import { BookOutlined, FolderOpenOutlined, ReadOutlined } from '@ant-design/icons';
import styles from './PracticeLibraryLayout.module.css';

const entries = [
  { to: '/questions', label: '精选题目', icon: <ReadOutlined /> },
  { to: '/knowledge/practice', label: '我的题目', icon: <BookOutlined /> },
  { to: '/knowledge', label: '资料与出题', icon: <FolderOpenOutlined /> },
];

export default function PracticeLibraryLayout() {
  const { pathname } = useLocation();
  const current = pathname.startsWith('/questions') ? '/questions'
    : pathname === '/knowledge/practice' ? '/knowledge/practice' : '/knowledge';
  return <div className={styles.libraryPage}>
    <nav className={styles.tabs} aria-label="题目与资料">
      {entries.map(entry => <Link key={entry.to} to={entry.to} aria-current={current === entry.to ? 'page' : undefined}>
        <span aria-hidden="true">{entry.icon}</span>{entry.label}
      </Link>)}
    </nav>
    <Outlet />
  </div>;
}
