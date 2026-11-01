import { useState } from 'react'
import { Button, Card, Descriptions, Input, Space, Tag, Typography, message } from 'antd'
import { SearchOutlined } from '@ant-design/icons'
import { watermarkApi } from '../services/api'

const { Text } = Typography

/** 溯源查询：GET /z-team/api/watermark/trace/{traceId}（200/404/503 三态）。 */
export default function TracePage() {
    const [traceId, setTraceId] = useState('')
    const [loading, setLoading] = useState(false)
    const [state, setState] = useState(null) // {kind: 'found'|'notfound'|'unavailable'|'error', data|msg}

    const runQuery = async () => {
        if (!traceId.trim()) { message.warning('请输入 traceId'); return }
        setLoading(true)
        try {
            const { data } = await watermarkApi.trace(traceId.trim())
            setState({ kind: 'found', data })
        } catch (e) {
            if (e.response?.status === 404) setState({ kind: 'notfound' })
            else if (e.response?.status === 503) setState({ kind: 'unavailable', msg: e.response?.data?.message || 'trace 存储暂不可用' })
            else setState({ kind: 'error', msg: `${e.response?.status || e.message}` })
        } finally {
            setLoading(false)
        }
    }

    return (
        <div>
            <Card title="溯源查询" style={{ marginBottom: 16 }}>
                <Space.Compact style={{ width: '100%', maxWidth: 560 }}>
                    <Input
                        value={traceId}
                        onChange={(e) => setTraceId(e.target.value)}
                        placeholder="输入 traceId（嵌入接口返回的）"
                        onPressEnter={runQuery}
                    />
                    <Button type="primary" icon={<SearchOutlined />} loading={loading} onClick={runQuery}>查询</Button>
                </Space.Compact>
            </Card>

            {state?.kind === 'found' && (
                <Card title="溯源记录">
                    <Descriptions bordered column={1} size="small">
                        {Object.entries(state.data)
                            .filter(([, v]) => v !== null && v !== undefined)
                            .map(([k, v]) => (
                                <Descriptions.Item key={k} label={k}>
                                    <Text copyable={typeof v === 'string' && v.length > 12} code={typeof v !== 'string'}>
                                        {typeof v === 'object' ? JSON.stringify(v) : String(v)}
                                    </Text>
                                </Descriptions.Item>
                            ))}
                    </Descriptions>
                </Card>
            )}
            {state?.kind === 'notfound' && (
                <Card><Tag color="orange">未找到该 traceId 的溯源记录（404）</Tag></Card>
            )}
            {state?.kind === 'unavailable' && (
                <Card><Tag color="red">溯源服务暂不可用（503）：{state.msg}</Tag></Card>
            )}
            {state?.kind === 'error' && (
                <Card><Tag color="red">查询失败：{state.msg}</Tag></Card>
            )}
        </div>
    )
}
