import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import './index.css'

const root = document.getElementById('root')
if (!root) throw new Error('화면을 그릴 자리를 찾지 못했습니다.')
createRoot(root).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
