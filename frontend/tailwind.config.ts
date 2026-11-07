import type { Config } from 'tailwindcss';

// Colours are CSS variables (see src/index.css) so light and dark themes share one set of class names.
const token = (name: string) => `rgb(var(--${name}) / <alpha-value>)`;

export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  darkMode: ['class'],
  theme: {
    extend: {
      colors: {
        bg: token('bg'),
        surface: token('surface'),
        'surface-2': token('surface-2'),
        border: token('border'),
        text: token('text'),
        muted: token('muted'),
        accent: token('accent'),
        'accent-fg': token('accent-fg'),
        success: token('success'),
        warning: token('warning'),
        danger: token('danger'),
        info: token('info'),
        sidebar: token('sidebar'),
        'sidebar-text': token('sidebar-text'),
        'sidebar-active': token('sidebar-active'),
      },
      fontFamily: {
        sans: ['Inter', 'ui-sans-serif', 'system-ui', '-apple-system', 'Segoe UI', 'Roboto', 'sans-serif'],
        mono: ['ui-monospace', 'SFMono-Regular', 'Menlo', 'monospace'],
      },
      fontSize: { table: ['13px', '18px'] },
      borderRadius: { sm: '4px', md: '6px', lg: '10px' },
    },
  },
  plugins: [],
} satisfies Config;
