import { useEffect } from 'react';
import { useLocation } from 'react-router-dom';
import { FileTextOutlined, CommentOutlined, LockOutlined } from '@ant-design/icons';
import brandMark from '@/assets/brand-mark.svg';
import styles from './auth.module.css';

export default function AuthBrand() {
  const { pathname } = useLocation();
  useEffect(() => {
    document.title = (pathname === '/register' ? '创建账号' : '登录') + ' · 职智达';
  }, [pathname]);

  return <section className={styles.brand} aria-label="职智达面试练习">
    <div className={styles.wordmark}><img src={brandMark} width="38" height="38" alt="" />职智达</div>
    <div className={styles.pitch}>
      <h1>下一场面试，<br /><em>从这里练起。</em></h1>
      <p>带上目标岗位和个人经历，<br />练习面试问答，回看每一轮反馈。</p>
    </div>
    <svg className={styles.art} viewBox="0 0 640 304" aria-hidden="true" focusable="false">
      <defs>
        <pattern id="auth-dots" width="14" height="14" patternUnits="userSpaceOnUse"><circle cx="2" cy="2" r="1" fill="#95bba0" opacity=".35" /></pattern>
        <linearGradient id="auth-front" x2="1" y2="1" gradientUnits="objectBoundingBox"><stop stopColor="#e5f5b3" /><stop offset="1" stopColor="#b4d883" /></linearGradient>
      </defs>
      <path d="M0 0h640v304H0z" fill="url(#auth-dots)" />
      <g fill="none" stroke="#86a78c" opacity=".47" transform="rotate(-13 326 184)">
        <ellipse cx="326" cy="184" rx="258" ry="77" />
        <ellipse cx="326" cy="184" rx="276" ry="86" strokeDasharray="3 8" />
      </g>
      <g fill="none" stroke="#c5e5a0" opacity=".85">
        <path d="M85 118v12m-6-6h12m457 72v12m-6-6h12" />
        <circle cx="123" cy="249" r="3" /><circle cx="501" cy="83" r="2" />
      </g>
      <g transform="matrix(1 .28 0 1 264 17)" strokeLinejoin="round">
        <path d="M0 15 12 5Q16 1 23 2l168 0q12 0 12 12v99q0 7-7 13l-11 9H25L3 158z" fill="#315e51" stroke="#a2c4a5" />
        <path d="M12 12h163q13 0 13 13v97q0 13-13 13H52l-32 27v-27h-8q-12 0-12-12V25q0-13 12-13z" fill="#527864" stroke="#bfdbb7" strokeWidth="1.6" />
        <g fill="none" stroke="#dcf1c7" strokeWidth="5" strokeLinecap="round"><path d="M30 48h106M30 65h128M30 82h75" /></g>
      </g>
      <g transform="matrix(1 -.27 0 1 154 129)" strokeLinejoin="round">
        <path d="M4 12 15 3q4-3 11-3h168q15 0 15 16v94q0 9-8 16l-11 9-18 1v24l-14 7-34-28H17z" fill="#80a575" stroke="#143e34" strokeWidth="2" />
        <path d="M14 10h169q13 0 13 14v95q0 14-14 14h-25v34l-38-34H14q-14 0-14-14V24q0-14 14-14z" fill="url(#auth-front)" stroke="#254c39" strokeWidth="2" />
        <g stroke="#274e40" strokeWidth="6" strokeLinecap="round">
          <path d="M30 68v9m13-18v27m13-34v41m13-48v55m13-44v33m13-55v76m13-66v56m13-42v28m13-39v51m13-39v27m13-18v9" />
        </g>
      </g>
      <path d="m114 68 3 11 11 3-11 3-3 11-3-11-11-3 11-3z" fill="#d8f38a" />
      <circle cx="480" cy="240" r="4" fill="#d8f38a" />
    </svg>
    <ul className={styles.facts}>
      <li><FileTextOutlined aria-hidden="true" />PDF / Word 简历</li>
      <li><CommentOutlined aria-hidden="true" />多轮问答</li>
      <li><LockOutlined aria-hidden="true" />私密记录</li>
    </ul>
  </section>;
}
