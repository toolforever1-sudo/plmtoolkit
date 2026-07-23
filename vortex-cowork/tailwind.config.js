/** @type {import('tailwindcss').Config} */
export default {
  content: [
    './src/renderer/**/*.{js,jsx,ts,tsx,html}'
  ],
  theme: {
    extend: {
      colors: {
        surface: {
          900: '#0f0f0f',
          800: '#181818',
          700: '#212121',
          600: '#2a2a2a',
          500: '#333333',
          400: '#3d3d3d',
        },
        accent: {
          DEFAULT: '#e10600',
          hover: '#ff1a15',
          dim: '#e1060020',
        }
      },
      fontFamily: {
        sans: ['-apple-system', 'BlinkMacSystemFont', 'Segoe UI', 'sans-serif'],
        mono: ['SF Mono', 'Monaco', 'Inconsolata', 'monospace'],
      }
    }
  },
  plugins: []
}
