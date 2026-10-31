package com.zifang.z.watermark.core.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 水印溯源记录（**水印侧的追踪码台账条目**）。
 *
 * <p><b>为什么需要它</b>：盲水印的载荷**只带追踪码**（载体容量是硬约束），
 * 提取出来只知道「这份内容属于哪条追踪码」——
 * 操作人 / 租户 / 单号 / 时间都要**回查台账**才能还原。
 * 本类就是那条台账的条目模型。
 *
 * <p><b>⚠️ 本类与 z-audit 的 {@code AuditEvent} 是两件事</b>：
 * <ul>
 *   <li>{@code WatermarkTrace} = 水印域自己的台账条目（「我给谁打了什么水印」）；</li>
 *   <li>{@code AuditEvent} = 审计域的哈希链事件（「发生过什么动作，可验不可篡改」）。</li>
 * </ul>
 * 两者靠 **traceId 关联**：水印域的台账记录经 HTTP 写入审计域，
 * 由审计域负责**落库 + 哈希链**；水印域只保留**读出口**用于反查。
 *
 * @author zifang
 */
public class WatermarkTrace {

    /** 追踪码（**幂等键**）。 */
    private String traceId;
    /** 操作人 / 责任人标识。 */
    private String operatorId;
    /** 租户 / 组织标识。 */
    private String tenantId;
    /** 业务单号。 */
    private String bizNo;
    /** 水印类型（{@code VISIBLE_TEXT} / {@code IMAGE_STEGANO} / {@code BLIND_TRACE}）。 */
    private String watermarkType;
    /** 打标时刻（epoch 毫秒）。 */
    private long occurredAt;
    /** 事件 ID（跨域写入时用它做**幂等键**）。 */
    private String eventId;
    /** 备注。 */
    private String remark;

    public WatermarkTrace() {
    }

    /**
     * 构造（从水印载荷 + 类型派生）。
     *
     * @param payload       水印载荷
     * @param watermarkType 水印类型
     */
    public WatermarkTrace(WatermarkPayload payload, WatermarkType watermarkType) {
        if (payload != null) {
            this.traceId = payload.getTraceId();
            this.operatorId = payload.getOperatorId();
            this.tenantId = payload.getTenantId();
            this.bizNo = payload.getBizNo();
            this.remark = payload.getRemark();
            if (payload.getTimestamp() > 0) {
                this.occurredAt = payload.getTimestamp();
            }
        }
        this.watermarkType = watermarkType == null ? null : watermarkType.name();
        if (this.occurredAt <= 0) {
            this.occurredAt = System.currentTimeMillis();
        }
        this.eventId = defaultEventId(this.traceId);
    }

    /**
     * 默认事件 ID（**幂等键**）：{@code WM:<traceId>}。
     *
     * <p><b>为什么用固定前缀</b>：同一份内容被「编排层记一次 + 水印域记一次」时，
     * 两边算出同一个 eventId ⇒ 落库时撞唯一索引 ⇒ **只留一条** ⇒ 不重复记账。
     *
     * @param traceId 追踪码
     * @return 事件 ID（追踪码为空 ⇒ null）
     */
    public static String defaultEventId(String traceId) {
        if (traceId == null || traceId.isEmpty()) {
            return null;
        }
        return "WM:" + traceId;
    }

    /**
     * 汇总视图（对外输出）。
     *
     * @return 键值视图
     */
    public Map<String, Object> toView() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("traceId", traceId);
        view.put("eventId", eventId);
        view.put("operatorId", operatorId);
        view.put("tenantId", tenantId);
        view.put("bizNo", bizNo);
        view.put("watermarkType", watermarkType);
        view.put("occurredAt", occurredAt);
        view.put("remark", remark);
        return view;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
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

    public String getWatermarkType() {
        return watermarkType;
    }

    public void setWatermarkType(String watermarkType) {
        this.watermarkType = watermarkType;
    }

    public long getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(long occurredAt) {
        this.occurredAt = occurredAt;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    @Override
    public String toString() {
        return "WatermarkTrace{" + toView() + "}";
    }
}
