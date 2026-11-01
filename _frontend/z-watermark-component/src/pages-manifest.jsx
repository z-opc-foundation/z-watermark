import { HomeOutlined, BgColorsOutlined, SearchOutlined, ReadOutlined } from '@ant-design/icons'
import HomePage from './pages/HomePage'
import LoginPage from './pages/LoginPage'
import ComponentDocPage from './pages/ComponentDoc.jsx'
import EmbedPage from './pages/EmbedPage.jsx'
import TracePage from './pages/TracePage.jsx'

/** 菜单 + 路由清单（lead 005 §8.3：主壳按本清单重组）。App 壳在 suit 侧组装。 */
export const appMeta = { title: 'z-watermark 水印', short: 'z-watermark' }

export const menuItems = [
    { key: '/z-watermark/home', label: '首页', icon: <HomeOutlined /> },
    { key: '/z-watermark/embed', label: '水印嵌入', icon: <BgColorsOutlined /> },
    { key: '/z-watermark/trace', label: '溯源查询', icon: <SearchOutlined /> },
    { key: '/z-watermark/docs', label: '组件文档', icon: <ReadOutlined /> },
]

export const routes = [
    { path: '/z-watermark/home', Component: HomePage },
    { path: '/z-watermark/embed', Component: EmbedPage },
    { path: '/z-watermark/trace', Component: TracePage },
    { path: '/z-watermark/docs', Component: ComponentDocPage },
]

export { HomePage, LoginPage, ComponentDocPage }
