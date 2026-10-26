import styles from './auth.module.css';

export default function AuthBrand() {
  return <section className={styles.brand} aria-label="职智达面试练习">
    <h1>职智达</h1>
    <p>用自己的岗位和简历，完成一次有依据的面试练习。</p>
    <ul className={styles.facts}>
      <li><span>01</span>支持 PDF / Word 简历</li>
      <li><span>02</span>标准 6 轮，可调整长度</li>
      <li><span>03</span>面试记录仅本人可见</li>
    </ul>
  </section>;
}
