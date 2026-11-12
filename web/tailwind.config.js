/** Tailwind 설정. FrameCritic 카메라 바디의 색만 토큰으로 남긴다. */
/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        viewfinder: '#121312',
        panel: '#1A1C1A',
        hairline: '#2A2C2A',
        ink: '#ECECE7',
        sage: '#C4DAA8',
        muted: '#8C8F8A',
      },
      fontFamily: {
        sans: [
          '"Pretendard"',
          '"Apple SD Gothic Neo"',
          '"Noto Sans KR"',
          'system-ui',
          '-apple-system',
          'sans-serif',
        ],
        display: ['"Helvetica Neue"', 'system-ui', 'sans-serif'],
      },
      maxWidth: {
        page: '720px',
      },
    },
  },
  plugins: [],
}
