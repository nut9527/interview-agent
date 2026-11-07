import styles from './index.module.css';

/** A studio microphone framed by sound waves on the interview welcome panel. */
export default function InterviewIllustration() {
  return <svg className={styles.heroArt} viewBox="0 0 440 216" fill="none" aria-hidden="true" focusable="false">
    <defs>
      <pattern id="mic-grid" width="19" height="19" patternUnits="userSpaceOnUse">
        <circle cx="1" cy="1" r="1" fill="#b8d5b8" opacity=".32" />
      </pattern>
      <pattern id="mic-grille" width="5" height="5" patternUnits="userSpaceOnUse">
        <path d="M0 0L5 5M5 0L0 5" stroke="#ddf2a5" strokeWidth=".8" />
      </pattern>
    </defs>
    <path fill="url(#mic-grid)" d="M0 0h440v216H0z" />
    <g stroke="#abcf9d" strokeWidth=".85" opacity=".48" transform="rotate(-16 247 109)">
      <ellipse cx="247" cy="109" rx="173" ry="67" />
      <ellipse cx="247" cy="109" rx="185" ry="75" strokeDasharray="2 6" />
      <path d="M44 110h39m329 0h23M247 21V7m0 201v-12" />
    </g>
    <g fill="#c8e69d">
      <path d="m103 68 2 7 7 2-7 2-2 7-2-7-7-2 7-2zM379 133l1.7 5.3 5.3 1.7-5.3 1.7-1.7 5.3-1.7-5.3-5.3-1.7 5.3-1.7z" />
      <circle cx="385" cy="48" r="3" /><circle cx="79" cy="148" r="2.5" />
    </g>
    <g transform="translate(213 29) rotate(17 34 68)" strokeLinejoin="round" strokeLinecap="round">
      <path d="M15 160v-4q0-5 9-6l29-3q9 0 10 5v4z" fill="#a7c8a1" stroke="#182f2c" strokeWidth="2" />
      <ellipse cx="38" cy="159" rx="29" ry="6.5" fill="#42685d" stroke="#d1e3b4" strokeWidth="1.5" />
      <path d="M32 112h11v40q-4 4-11 0z" fill="#78a68c" stroke="#cee4b7" strokeWidth="2" />
      <path d="M-2 66v15c0 23 16 39 39 39s39-18 39-41V65h-8v14c0 19-13 32-31 32S6 98 6 80V66z" fill="#8eb795" stroke="#dbe9bd" strokeWidth="2" />
      <rect x="9" y="0" width="58" height="100" rx="29" fill="#8bb39a" stroke="#d9efba" strokeWidth="2" />
      <rect x="8" y="0" width="51" height="98" rx="25.5" fill="#2e594e" stroke="#d8ecb3" strokeWidth="2" />
      <rect x="11.5" y="4" width="43" height="70" rx="21.5" fill="url(#mic-grille)" />
      <path d="M10 54c12 5 33 6 48 1v9c-15 5-34 4-48-1z" fill="#b5cb9f" stroke="#dceaba" />
      <path d="M12 75c12 5 30 5 43 1" stroke="#a3c6a4" strokeWidth="2" />
      <circle cx="34" cy="86" r="3.3" fill="#d4f395" />
      <circle cx="71" cy="68" r="5.5" fill="#476e60" stroke="#dceaba" strokeWidth="2" />
    </g>
  </svg>;
}
