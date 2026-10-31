package com.zifang.z.watermark.web.client;

import com.zifang.z.watermark.core.domain.WatermarkTrace;
import com.zifang.z.watermark.core.spi.WatermarkTraceQuery;
import com.zifang.z.watermark.core.spi.WatermarkTraceRecorder;
import com.zifang.z.watermark.core.spi.WatermarkTraceUnavailableException;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ★ 审计域 HTTP 客户端（M2 #22 —— 跨域落台账 + 反查）。
 *
 * <p><b>为什么走 HTTP 而不直接依赖 z-audit-core</b>：组织口径是
 * 「跨域交互走 HTTP 边界，不把别人的 jar 拖进本仓上下文」。
 * 加上**哈希链必须单写者** —— {@code z_audit_event} 表的权威写者是 z-audit 服务，
 * 若本仓自己写库就是第二个写者 ⇒ 两条链分叉 ⇒ 链校验必然报断。
 *
 * <p><b>★ 三态口径（查询侧）</b>：
 * <ul>
 *   <li>HTTP 200 ⇒ 查到；</li>
 *   <li>HTTP 404 ⇒ <b>台账里没有这条</b>（合规缺口）⇒ 返回 {@code null}；</li>
 *   <li>HTTP 503 / 连接失败 / 其它异常 ⇒ <b>台账不可用</b>
 *       ⇒ 抛 {@link WatermarkTraceUnavailableException}。</li>
 * </ul>
 *
 * <p><b>★ 写侧不抛异常</b>：台账写失败只返回 {@code false}
 * ⇒ 上层标记「台账没写成」但**不拖垮打水印的主链路**。
 *
 * @author zifang
 */
public class AuditTraceHttpClient implements WatermarkTraceRecorder, WatermarkTraceQuery {

    /** 审计域基址（如 {@code http://127.0.0.1:8097}）。 */
    private final String baseUrl;

    /** HTTP 客户端。 */
    private final RestClient restClient;

    /**
     * 构造。
     *
     * @param baseUrl 审计域基址（**不可为空**）
     */
    public AuditTraceHttpClient(String baseUrl) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.restClient = RestClient.builder().build();
    }

    @Override
    public boolean record(WatermarkTrace trace) {
        if (trace == null || trace.getTraceId() == null || trace.getTraceId().isEmpty()) {
            return false;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("traceId", trace.getTraceId());
        body.put("eventId", trace.getEventId());
        body.put("operatorId", trace.getOperatorId());
        body.put("tenantId", trace.getTenantId());
        body.put("bizNo", trace.getBizNo());
        body.put("targetType", "WATERMARK");
        body.put("remark", trace.getRemark());
        body.put("occurredAt", trace.getOccurredAt());
        try {
            restClient.post()
                    .uri(baseUrl + "/z-team/api/audit/trace")
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (RestClientException e) {
            // ⚠ 写失败不外抛（SPI 契约）：台账是辅链路，打水印是主链路。
            System.err.println("[z-watermark] 溯源台账写入失败（traceId=" + trace.getTraceId()
                    + "）：" + e.getMessage());
            return false;
        }
    }

    @Override
    public WatermarkTrace find(String traceId) {
        if (traceId == null || traceId.isEmpty()) {
            return null;
        }
        try {
            AuditTraceResponse response = restClient.get()
                    .uri(baseUrl + "/z-team/api/audit/trace/{traceId}", traceId)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        // ★ 404 = 台账里没有这条（**不是故障**）⇒ 不抛异常，走下面的判据。
                        if (res.getStatusCode().value() == 404) {
                            return;
                        }
                        throw new WatermarkTraceUnavailableException(
                                "审计域返回 " + res.getStatusCode().value());
                    })
                    .body(AuditTraceResponse.class);
            if (response == null) {
                return null;
            }
            if (!response.isFound()) {
                return null;
            }
            Map<String, Object> trace = response.getTrace();
            if (trace == null) {
                return null;
            }
            WatermarkTrace result = new WatermarkTrace();
            result.setTraceId(asString(trace.get("traceId")));
            result.setEventId(asString(trace.get("eventId")));
            result.setOperatorId(asString(trace.get("operatorId")));
            result.setTenantId(asString(trace.get("tenantId")));
            result.setBizNo(asString(trace.get("bizNo")));
            result.setWatermarkType(asString(trace.get("watermarkType")));
            result.setRemark(asString(trace.get("remark")));
            Object occurredAt = trace.get("occurredAt");
            if (occurredAt instanceof Number) {
                result.setOccurredAt(((Number) occurredAt).longValue());
            }
            return result;
        } catch (WatermarkTraceUnavailableException e) {
            throw e;
        } catch (RestClientException e) {
            throw new WatermarkTraceUnavailableException("审计域不可达：" + e.getMessage(), e);
        }
    }

    @Override
    public String name() {
        return "audit-http";
    }

    /** 取值转字符串。 */
    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /**
     * 审计域反查响应体（**只取本仓需要的字段**）。
     */
    public static class AuditTraceResponse {

        /** 是否查到。 */
        private boolean found;
        /** 台账是否不可用。 */
        private boolean unavailable;
        /** 溯源记录（查到时非空）。 */
        private Map<String, Object> trace;

        public boolean isFound() {
            return found;
        }

        public void setFound(boolean found) {
            this.found = found;
        }

        public boolean isUnavailable() {
            return unavailable;
        }

        public void setUnavailable(boolean unavailable) {
            this.unavailable = unavailable;
        }

        public Map<String, Object> getTrace() {
            return trace;
        }

        public void setTrace(Map<String, Object> trace) {
            this.trace = trace;
        }
    }
}
