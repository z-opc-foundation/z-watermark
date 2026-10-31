package com.zifang.z.watermark.web.api;

import com.zifang.z.watermark.core.domain.WatermarkPayload;
import com.zifang.z.watermark.core.domain.WatermarkType;
import com.zifang.z.watermark.core.service.WatermarkService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 水印端点。
 *
 * <p><b>路由前缀</b>：{@code /z-team/api/watermark/**}。
 *
 * <p><b>「打 + 验」成对提供</b>：能打不能验没意义 ⇒ 打水印的响应里
 * **自带自检结果**（`embedAndVerify` 立即验回，比对 traceId）。
 *
 * @author zifang
 */
@RestController
@RequestMapping("/z-team/api/watermark")
public class WatermarkController {

    /** 水印服务（可空 ⇒ 未装配）。 */
    private final ObjectProvider<WatermarkService> watermarkServiceProvider;

    /**
     * 构造。
     *
     * @param watermarkServiceProvider 水印服务提供者
     */
    public WatermarkController(ObjectProvider<WatermarkService> watermarkServiceProvider) {
        this.watermarkServiceProvider = watermarkServiceProvider;
    }

    /**
     * ★ 打水印 + 写溯源台账 + 立即验回（**闭环主入口**，M2 #22）。
     *
     * <p><b>追踪码单一来源</b>：调用方不给追踪码 ⇒ 本域补一个**短码**并回写载荷；
     * 响应里的 {@code traceId} 就是水印里那个码 ⇒ 拿它去反查台账必然对得上。
     *
     * <p><b>⚠️ 台账没写成时 HTTP 仍是 200</b>：打水印成功了，
     * 但溯源留痕失败 ⇒ 响应里 {@code traceRecorded=false} + {@code traceError} 说明原因。
     * 不改成 5xx 是因为「水印是主链路、台账是辅链路」——
     * 但**不能静默**（合规缺口必须能被调用方看见）。
     *
     * @param request 请求体
     * @return 带水印内容 + 自检结果 + 溯源状态
     */
    @PostMapping("/embed")
    public ResponseEntity<Map<String, Object>> embed(@RequestBody WatermarkRequest request) {
        WatermarkService service = watermarkServiceProvider.getIfAvailable();
        if (service == null) {
            return ResponseEntity.status(503).body(error("水印服务未装配"));
        }
        WatermarkType type = WatermarkType.fromCode(request.getType());
        if (type == null) {
            return ResponseEntity.badRequest().body(error("未知的水印类型：" + request.getType()));
        }
        WatermarkPayload payload = new WatermarkPayload(
                request.getOperatorId(), request.getTenantId(), request.getBizNo(),
                request.getTraceId(),
                request.getTimestamp() == null ? System.currentTimeMillis() : request.getTimestamp());

        // ★ 用 embedTraced（不是 embedAndVerify）：打完水印顺手把溯源台账写出去。
        WatermarkService.TracedWatermarkResult r =
                service.embedTraced(type, request.getContent(), payload);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("type", type.name());
        body.put("verified", r.isVerified());
        body.put("content", r.getWatermarked());
        body.put("traceId", r.getTraceId());
        body.put("traceIdGenerated", r.isTraceIdGenerated());
        body.put("traceRecorded", r.isTraceRecorded());
        body.put("traceError", r.getTraceError());
        body.put("extracted", r.getPayload() == null ? null : r.getPayload().toPayloadString());
        return ResponseEntity.ok(body);
    }

    /**
     * ★ 按追踪码反查溯源台账（**三态**：200 查到 / 404 台账没有 / 503 台账不可用）。
     *
     * <p>用法：拿到泄密内容 ⇒ 提取盲水印里的追踪码 ⇒ 调本端点 ⇒ 还原完整上下文。
     *
     * @param traceId 追踪码
     * @return 反查结果
     */
    @GetMapping("/trace/{traceId}")
    public ResponseEntity<Map<String, Object>> trace(@PathVariable String traceId) {
        WatermarkService service = watermarkServiceProvider.getIfAvailable();
        if (service == null) {
            return ResponseEntity.status(503).body(error("水印服务未装配"));
        }
        WatermarkService.TraceLookupResult result = service.trace(traceId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", !result.isUnavailable());
        body.putAll(result.toView());
        if (result.isUnavailable()) {
            return ResponseEntity.status(503).body(body);
        }
        if (!result.isFound()) {
            return ResponseEntity.status(404).body(body);
        }
        return ResponseEntity.ok(body);
    }

    /**
     * 提取水印载荷（反查）。
     *
     * @param request 请求体
     * @return 提取结果
     */
    @PostMapping("/extract")
    public ResponseEntity<Map<String, Object>> extract(@RequestBody WatermarkRequest request) {
        WatermarkService service = watermarkServiceProvider.getIfAvailable();
        if (service == null) {
            return ResponseEntity.status(503).body(error("水印服务未装配"));
        }
        WatermarkType type = WatermarkType.fromCode(request.getType());
        if (type == null) {
            return ResponseEntity.badRequest().body(error("未知的水印类型：" + request.getType()));
        }
        WatermarkPayload extracted = service.extract(type, request.getContent());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("found", extracted != null);
        body.put("extracted", extracted == null ? null : extracted.toPayloadString());
        body.put("traceId", extracted == null ? null : extracted.getTraceId());
        return ResponseEntity.ok(body);
    }

    /** 错误体。 */
    private static Map<String, Object> error(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("message", message);
        return body;
    }

    /**
     * 水印请求体。
     */
    public static class WatermarkRequest {

        /** 水印类型（VISIBLE_TEXT / IMAGE_STEGANO / BLIND_TRACE）。 */
        private String type;
        /** 载体内容（文本 / 图片字节的 base64 场景由调用方先解码）。 */
        private String content;
        /** 操作人。 */
        private String operatorId;
        /** 租户。 */
        private String tenantId;
        /** 业务单号。 */
        private String bizNo;
        /** 追踪 ID。 */
        private String traceId;
        /** 时间戳（epoch 毫秒）。 */
        private Long timestamp;

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
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

        public Long getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(Long timestamp) {
            this.timestamp = timestamp;
        }
    }
}
