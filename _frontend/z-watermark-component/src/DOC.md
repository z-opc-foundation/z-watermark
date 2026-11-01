# @yuku123/z-watermark-component

z-watermark（水印中间件）可复用前端组件（lead 005 §9 component 共享）。

## 使用

```js
import { configureWatermark } from '@yuku123/z-watermark-component'
configureWatermark({ apiBase: '' })
```

## pages 入口（路由清单）

```js
import { routes, menuItems, appMeta } from '@yuku123/z-watermark-component/pages'
```

| 路径 | 页面 | 说明 |
|---|---|---|
| `/z-watermark/home` | HomePage | 首页着陆 |
| `/z-watermark/embed` | EmbedPage | 水印嵌入试跑（POST `/z-team/api/watermark/embed`） |
| `/z-watermark/trace` | TracePage | 溯源查询（GET `/z-team/api/watermark/trace/{traceId}`，200/404/503 三态） |
| `/z-watermark/docs` | ComponentDocPage | 组件文档 |

## 后端契约

- `POST /z-team/api/watermark/embed`，body `{type, content, operatorId, tenantId, bizNo, traceId, timestamp}`
- `POST /z-team/api/watermark/extract`
- `GET /z-team/api/watermark/trace/{traceId}`
- `GET /api/watermark/health`（健康检查）

`.` 入口保持 props-only（lead 005 §5）；fetch 只出现在 `./pages` 的默认装配里。
