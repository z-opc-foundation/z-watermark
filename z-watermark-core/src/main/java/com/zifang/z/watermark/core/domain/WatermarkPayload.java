package com.zifang.z.watermark.core.domain;

/**
 * 水印载荷（要写进内容里的信息）。
 *
 * <p><b>载荷口径</b>：载荷是**待编码的明文**，具体编码方式由水印算法决定
 * （显式水印直接渲染文字；隐写/盲水印则把载荷编码成比特流嵌进载体）。
 *
 * <p><b>载荷里放什么</b>：至少要能回答「这份内容是谁、什么时候、经什么渠道流出去的」。
 * 典型字段：操作人 ID / 租户 ID / 时间戳 / 业务单号 / traceId。
 *
 * @author zifang
 */
public class WatermarkPayload {

    /** 操作人 / 责任人标识。 */
    private String operatorId;

    /** 租户 / 组织标识。 */
    private String tenantId;

    /** 业务单号（合同号 / 订单号等）。 */
    private String bizNo;

    /** 追踪码（全局唯一，用于反查）。 */
    private String traceId;

    /** 打标时刻（epoch 毫秒）。 */
    private long timestamp;

    /** 备注（可选）。 */
    private String remark;

    public WatermarkPayload() {
    }

    public WatermarkPayload(String operatorId, String tenantId, String bizNo, String traceId,
                            long timestamp) {
        this.operatorId = operatorId;
        this.tenantId = tenantId;
        this.bizNo = bizNo;
        this.traceId = traceId;
        this.timestamp = timestamp;
    }

    /**
     * 拼装载荷字符串（**规范顺序**，解码端按同顺序解析）。
     *
     * <p>格式：{@code operatorId|tenantId|bizNo|traceId|timestamp}，
     * 空字段用**单个短横线**占位（不用空串，避免 {@code "a||b"} 与 {@code "a|b"} 歧义）。
     *
     * @return 载荷字符串
     */
    public String toPayloadString() {
        return nvl(operatorId) + "|" + nvl(tenantId) + "|" + nvl(bizNo) + "|"
                + nvl(traceId) + "|" + timestamp;
    }

    /**
     * 解析载荷字符串（{@link #toPayloadString()} 的逆操作）。
     *
     * @param payload 载荷字符串（可空）
     * @return 解析结果（可空 ⇒ 输入为空）
     */
    public static WatermarkPayload parse(String payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        String[] parts = payload.split("\\|", -1);
        WatermarkPayload p = new WatermarkPayload();
        p.setOperatorId(parts.length > 0 ? denvl(parts[0]) : null);
        p.setTenantId(parts.length > 1 ? denvl(parts[1]) : null);
        p.setBizNo(parts.length > 2 ? denvl(parts[2]) : null);
        p.setTraceId(parts.length > 3 ? denvl(parts[3]) : null);
        if (parts.length > 4) {
            try {
                p.setTimestamp(Long.parseLong(parts[4].trim()));
            } catch (NumberFormatException e) {
                p.setTimestamp(0L);
            }
        }
        return p;
    }

    /** 空值归一（null ⇒ "-"）。 */
    private static String nvl(String s) {
        return s == null || s.isEmpty() ? "-" : s;
    }

    /** 空值还原（"-" / 空 ⇒ null）。 */
    private static String denvl(String s) {
        return s == null || s.isEmpty() || "-".equals(s) ? null : s;
    }

    public String getOperatorId() {
        return operatorId;
    }

    public void setOperatorId(String operatorId) {
        this.operatorId = operatorId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getBizNo() {
        return bizNo;
    }

    public void setBizNo(String bizNo) {
        this.bizNo = bizNo;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    @Override
    public String toString() {
        return "WatermarkPayload{" + toPayloadString() + "}";
    }
}
