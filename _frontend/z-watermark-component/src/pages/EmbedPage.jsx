import { useState } from 'react'
import { Button, Card, Form, Input, Space, Tag, Typography, message } from 'antd'
import { BgColorsOutlined } from '@ant-design/icons'
import { watermarkApi } from '../services/api'

const { TextArea } = Input
const { Paragraph, Text } = Typography

/** 水印嵌入试跑：POST /z-team/api/watermark/embed。 */
export default function EmbedPage() {
    const [form] = Form.useForm()
    const [loading, setLoading] = useState(false)
    const [result, setResult] = useState(null)

    const runEmbed = async (values) => {
        if (!values.content?.trim()) { message.warning('请输入待嵌入内容'); return }
        setLoading(true)
        try {
            const { data } = await watermarkApi.embed({
                type: values.type || 'text',
                content: values.content,
                operatorId: values.operatorId || '',
                tenantId: values.tenantId || '',
                bizNo: values.bizNo || '',
                traceId: values.traceId || '',
                timestamp: Date.now(),
            })
            if (!data.ok) throw new Error(data.message || '嵌入失败')
            setResult(data)
            message.success('水印嵌入成功')
        } catch (e) {
            message.error(`嵌入失败：${e.response?.status || e.message}`)
        } finally {
            setLoading(false)
        }
    }

    const StateTag = ({ label, ok }) => (
        <Space size={6}><Text type="secondary">{label}</Text><Tag color={ok ? 'green' : 'red'}>{ok ? '是' : '否'}</Tag></Space>
    )

    return (
        <div>
            <Card title="水印嵌入" style={{ marginBottom: 16 }}>
                <Form form={form} layout="vertical" onFinish={runEmbed}>
                    <Space size={12} wrap>
                        <Form.Item name="type" label="类型" initialValue="text" style={{ minWidth: 140 }}>
                            <Input disabled />
                        </Form.Item>
                        <Form.Item name="operatorId" label="操作人" style={{ minWidth: 160 }}>
                            <Input placeholder="operatorId" />
                        </Form.Item>
                        <Form.Item name="tenantId" label="租户" style={{ minWidth: 160 }}>
                            <Input placeholder="tenantId" />
                        </Form.Item>
                        <Form.Item name="bizNo" label="业务号" style={{ minWidth: 160 }}>
                            <Input placeholder="bizNo" />
                        </Form.Item>
                        <Form.Item name="traceId" label="traceId（可空自动生成）" style={{ minWidth: 220 }}>
                            <Input placeholder="留空则由服务端生成" />
                        </Form.Item>
                    </Space>
                    <Form.Item name="content" label="内容">
                        <TextArea rows={5} placeholder="粘贴待嵌入水印的文本内容" />
                    </Form.Item>
                    <Button type="primary" icon={<BgColorsOutlined />} loading={loading} htmlType="submit">
                        嵌入水印
                    </Button>
                </Form>
            </Card>

            {result && (
                <Card title="嵌入结果">
                    <Space direction="vertical" size={10} style={{ width: '100%' }}>
                        <Space size={24} wrap>
                            <StateTag label="校验通过" ok={result.verified} />
                            <StateTag label="traceId 已生成" ok={result.traceIdGenerated} />
                            <StateTag label="trace 已落档" ok={result.traceRecorded} />
                        </Space>
                        {result.traceError && <Tag color="red">{result.traceError}</Tag>}
                        <div>
                            <Text type="secondary">带水印内容：</Text>
                            <Paragraph copyable style={{ whiteSpace: 'pre-wrap', marginBottom: 0 }}>{result.content}</Paragraph>
                        </div>
                        <div>
                            <Text type="secondary">traceId：</Text>
                            <Text copyable code>{result.traceId || '-'}</Text>
                        </div>
                        {result.extracted != null && (
                            <div>
                                <Text type="secondary">回提水印：</Text>
                                <Text code>{String(result.extracted)}</Text>
                            </div>
                        )}
                    </Space>
                </Card>
            )}
        </div>
    )
}
