/**
 * z-watermark API client：走 /z-team/api/watermark/** 面（水印：embed/extract/trace）。
 */
import {createRequest} from '@yuku123/z-frontend-common'

const request = createRequest({baseURL: '', tokenKey: 'zwatermark_token'})

export default request

export function configureWatermark(config) {
    if (config && config.apiBase !== undefined) {
        request.defaults.baseURL = config.apiBase
    }
}

export const watermarkApi = {
    embed: (payload) => request.post('/z-team/api/watermark/embed', payload),
    extract: (payload) => request.post('/z-team/api/watermark/extract', payload),
    trace: (traceId) => request.get(`/z-team/api/watermark/trace/${encodeURIComponent(traceId)}`),
    health: () => request.get('/api/watermark/health'),
}
