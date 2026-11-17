import { defineConfig, loadEnv, type Plugin } from 'vite'
import react from '@vitejs/plugin-react'

const LOCAL_DEV_SOURCES = [
  'http://localhost:8080',
  'http://127.0.0.1:8080',
  'ws://localhost:5173',
  'ws://127.0.0.1:5173',
]

/** The CSP's connect-src: 'self', the API's origin, and in dev the local gateway and HMR socket. */
export function connectSources(apiUrl: string | undefined, dev: boolean): string {
  const sources = new Set<string>(["'self'"])
  if (apiUrl) {
    // A relative VITE_API_URL ("/api/v1") is same-origin, which 'self' covers.
    try {
      sources.add(new URL(apiUrl).origin)
    } catch {
      /* relative */
    }
  }
  if (dev || !apiUrl) LOCAL_DEV_SOURCES.forEach((s) => sources.add(s))
  return [...sources].join(' ')
}

function cspConnectSrc(apiUrl: string | undefined, dev: boolean): Plugin {
  return {
    name: 'gitoracle-csp-connect-src',
    transformIndexHtml: (html) => html.replace('__GITORACLE_CONNECT_SRC__', connectSources(apiUrl, dev)),
  }
}

// https://vite.dev/config/
export default defineConfig(({ command, mode }) => {
  const env = loadEnv(mode, process.cwd(), 'VITE_')
  return {
    plugins: [react(), cspConnectSrc(env.VITE_API_URL, command === 'serve')],
  }
})
