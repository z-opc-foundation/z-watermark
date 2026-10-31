package com.zifang.z.watermark.core.service;

import com.zifang.z.watermark.core.domain.WatermarkPayload;
import com.zifang.z.watermark.core.domain.WatermarkTrace;
import com.zifang.z.watermark.core.domain.WatermarkType;
import com.zifang.z.watermark.core.spi.WatermarkTraceQuery;
import com.zifang.z.watermark.core.spi.WatermarkTraceRecorder;
import com.zifang.z.watermark.core.spi.Watermarker;
import com.zifang.z.watermark.core.support.WatermarkTraceIds;

import java.util.EnumMap;
import java.util.Map;

/**
 * 水印门面 —— 本仓对外的**唯一主入口**。
 *
 * <p>按水印类型路由到对应实现，同时提供「打 + 验」的成对动作。
 *
 * <p><b>线程安全</b>：实现无状态（载荷是入参）⇒ 可并发调用。
 *
 * <p><b>★ M2 溯源闭环（M2 #22）</b>：本类新增两条闭环入口 ——
 * <ul>
 *   <li>{@link #embedTraced} —— 打水印 + **写溯源台账** + 立即验回（三合一）；</li>
 *   <li>{@link #trace} —— 按追踪码**反查**台账（盲水印溯源的闭环出口）。</li>
 * </ul>
 *
 * <p><b>★ 追踪码单一来源</b>：{@link #embedTraced} 会在载荷**没有追踪码时自动补一个短码**，
 * 并把补出来的追踪码**回写到载荷** ⇒ 后续不论谁拿这个载荷去查，
 * 拿到的都是**同一个**追踪码。⚠️ 严禁在别处各自生成追踪码 ——
 * 两个追踪码 ⇒ 水印里的查不到台账，台账里的查不到水印，闭环就是断的。
 *
 * <p><b>★ 台账写失败不拖垮打水印</b>：台账是辅链路 ⇒ {@code embedTraced} 写台账失败
 * 只标记 {@code traceRecorded=false}，**照样返回带水印的内容**。
 * 但**不能静默** —— 响应里必须能看到「台账没写成」这个事实，
 * 否则合规缺口会被当成正常流程掩盖过去。
 *
 * @author zifang
 */
public class WatermarkService {

    /** 类型 → 实现。 */
    private final Map<WatermarkType, Watermarker> watermarkers = new EnumMap<>(WatermarkType.class);

    /** 溯源写入出口（可空 ⇒ 不写台账）。 */
    private WatermarkTraceRecorder traceRecorder;

    /** 溯源查询出口（可空 ⇒ 无反查能力）。 */
    private WatermarkTraceQuery traceQuery;

    /** 默认构造：装载三种内置实现。 */
    public WatermarkService() {
        register(new VisibleTextWatermarker());
        register(new ImageStegoWatermarker());
        register(new BlindTextWatermarker());
    }

    /**
     * 设溯源写入出口。
     *
     * @param recorder 出口（可空 ⇒ 不写台账）
     */
    public void setTraceRecorder(WatermarkTraceRecorder recorder) {
        this.traceRecorder = recorder;
    }

    /**
     * 设溯源查询出口。
     *
     * @param query 出口（可空 ⇒ 无反查能力）
     */
    public void setTraceQuery(WatermarkTraceQuery query) {
        this.traceQuery = query;
    }

    /** 溯源写入出口（可空）。 */
    public WatermarkTraceRecorder getTraceRecorder() {
        return traceRecorder;
    }

    /** 溯源查询出口（可空）。 */
    public WatermarkTraceQuery getTraceQuery() {
        return traceQuery;
    }

    /**
     * 注册一个实现（同类型覆盖）。
     *
     * @param watermarker 实现（可空 ⇒ 忽略）
     */
    public void register(Watermarker watermarker) {
        if (watermarker != null && watermarker.type() != null) {
            watermarkers.put(watermarker.type(), watermarker);
        }
    }

    /**
     * 打水印。
     *
     * @param type    水印类型（**不可为 null**）
     * @param content 载体内容
     * @param payload 载荷
     * @return 带水印的内容（类型不支持 ⇒ 原样返回）
     */
    public Object embed(WatermarkType type, Object content, WatermarkPayload payload) {
        Watermarker w = watermarkers.get(type);
        if (w == null) {
            return content;
        }
        return w.embed(content, payload);
    }

    /**
     * 验水印（提取载荷）。
     *
     * @param type    水印类型
     * @param content 载体内容
     * @return 载荷（**可能为 null** —— 无水印 / 提取失败）
     */
    public WatermarkPayload extract(WatermarkType type, Object content) {
        Watermarker w = watermarkers.get(type);
        if (w == null) {
            return null;
        }
        return w.extract(content);
    }

    /**
     * 打水印并**立即验回**（自检：确保本次嵌入真的能被提取 —— M1 的验收动作）。
     *
     * @param type    水印类型
     * @param content 载体内容
     * @param payload 载荷
     * @return 自检结果
     */
    public WatermarkVerifyResult embedAndVerify(WatermarkType type, Object content, WatermarkPayload payload) {
        Object watermarked = embed(type, content, payload);
        WatermarkPayload extracted = extract(type, watermarked);
        boolean ok = extracted != null && payload.getTraceId() != null
                && payload.getTraceId().equals(extracted.getTraceId());
        return new WatermarkVerifyResult(watermarked, extracted, ok);
    }

    /** 已注册的实现类型。 */
    public java.util.Set<WatermarkType> supportedTypes() {
        return java.util.Collections.unmodifiableSet(watermarkers.keySet());
    }

    /**
     * 打验自检结果。
     */
    // ========================================================================
    // M2 溯源闭环
    // ========================================================================

    /**
     * ★ 打水印 + 写溯源台账 + 立即验回（**闭环主入口**）。
     *
     * <p><b>三件事按序做</b>：
     * <ol>
     *   <li><b>补追踪码</b>：载荷没有追踪码 ⇒ 补一个短码（{@link WatermarkTraceIds}）
     *       并<b>回写载荷</b> ⇒ 追踪码只有一个来源；</li>
     *   <li><b>打水印并验回</b>：确认这次嵌入真的能被提取；</li>
     *   <li><b>写溯源台账</b>：把「谁 / 什么单号 / 什么租户 / 哪条追踪码」写进台账
     *       ⇒ 之后凭盲水印里的短码能反查出完整上下文。</li>
     * </ol>
     *
     * <p><b>★ 台账写失败不外抛</b>：结果里 {@code traceRecorded=false} + {@code traceError} 说明原因，
     * 但带水印的内容**照常返回**（水印是主链路，台账是辅链路）。
     *
     * @param type    水印类型（不可为 null）
     * @param content 载体内容
     * @param payload 载荷（**不可为 null**）
     * @return 闭环结果
     */
    public TracedWatermarkResult embedTraced(WatermarkType type, Object content, WatermarkPayload payload) {
        long start = System.currentTimeMillis();
        if (payload == null) {
            return new TracedWatermarkResult(content, null, false, false, "载荷为 null", 0L, false);
        }
        // ---- 1. 追踪码单一来源：没有就补一个，并回写载荷 ----
        boolean generated = false;
        if (payload.getTraceId() == null || payload.getTraceId().isEmpty()) {
            payload.setTraceId(WatermarkTraceIds.next());
            generated = true;
        }
        if (payload.getTimestamp() <= 0) {
            payload.setTimestamp(System.currentTimeMillis());
        }

        // ---- 2. 打 + 验 ----
        WatermarkVerifyResult verified = embedAndVerify(type, content, payload);

        // ---- 3. 写溯源台账（失败不外抛） ----
        boolean recorded = false;
        String error = null;
        if (traceRecorder == null) {
            error = "未配置溯源台账出口（本次不写台账）";
        } else {
            try {
                recorded = traceRecorder.record(new WatermarkTrace(payload, type));
                if (!recorded) {
                    error = "溯源台账写入未成功（出口返回 false）";
                }
            } catch (RuntimeException e) {
                error = "溯源台账写入异常：" + e.getMessage();
            }
        }
        return new TracedWatermarkResult(verified.getWatermarked(), payload, verified.isVerified(),
                recorded, error, System.currentTimeMillis() - start, generated);
    }

    /**
     * ★ 按追踪码反查溯源台账（**盲水印溯源的闭环出口**）。
     *
     * <p>用法：拿到泄密内容 ⇒ 提取出盲水印里的追踪码 ⇒ 调本方法 ⇒ 还原
     * 「谁在什么时候把哪份单号导出去的」。
     *
     * <p><b>★ 三态口径</b>：
     * <ul>
     *   <li>{@code found=true} ⇒ 查到了；</li>
     *   <li>{@code found=false, unavailable=false} ⇒ <b>台账里没有这条</b>
     *       （合规缺口；要能区分「本来就没打」与「台账被清了」）；</li>
     *   <li>{@code unavailable=true} ⇒ <b>台账服务不可用</b>
     *       （基础设施故障；与上一条处置动作不同）。</li>
     * </ul>
     *
     * @param traceId 追踪码
     * @return 反查结果（**不返回 null**）
     */
    public TraceLookupResult trace(String traceId) {
        if (traceId == null || traceId.isEmpty()) {
            return TraceLookupResult.notFound("追踪码为空");
        }
        if (traceQuery == null) {
            return TraceLookupResult.unavailable("未配置溯源台账查询出口");
        }
        try {
            WatermarkTrace found = traceQuery.find(traceId);
            if (found == null) {
                return TraceLookupResult.notFound("台账中没有该追踪码的记录");
            }
            return TraceLookupResult.found(found);
        } catch (com.zifang.z.watermark.core.spi.WatermarkTraceUnavailableException e) {
            return TraceLookupResult.unavailable(e.getMessage());
        } catch (RuntimeException e) {
            return TraceLookupResult.unavailable("溯源台账查询异常：" + e.getMessage());
        }
    }

    /**
     * 打验自检结果。
     */
    public static class WatermarkVerifyResult {

        /** 带水印的内容。 */
        private final Object watermarked;
        /** 提取回的载荷。 */
        private final WatermarkPayload extracted;
        /** 是否通过（traceId 一致）。 */
        private final boolean verified;

        public WatermarkVerifyResult(Object watermarked, WatermarkPayload extracted, boolean verified) {
            this.watermarked = watermarked;
            this.extracted = extracted;
            this.verified = verified;
        }

        public Object getWatermarked() {
            return watermarked;
        }

        public WatermarkPayload getExtracted() {
            return extracted;
        }

        public boolean isVerified() {
            return verified;
        }
    }

    /**
     * 闭环打水印结果。
     */
    public static class TracedWatermarkResult {

        /** 带水印的内容。 */
        private final Object watermarked;
        /** 载荷（**已回写追踪码** ⇒ 调用方拿到的就是最终追踪码）。 */
        private final WatermarkPayload payload;
        /** 水印自检是否通过（提取回的追踪码与载荷一致）。 */
        private final boolean verified;
        /** 溯源台账是否写成。 */
        private final boolean traceRecorded;
        /** 台账未写成的原因（可空）。 */
        private final String traceError;
        /** 耗时（毫秒）。 */
        private final long elapsedMillis;
        /** 追踪码是否由本次自动生成（true ⇒ 调用方原本没给）。 */
        private final boolean traceIdGenerated;

        public TracedWatermarkResult(Object watermarked, WatermarkPayload payload, boolean verified,
                                     boolean traceRecorded, String traceError, long elapsedMillis,
                                     boolean traceIdGenerated) {
            this.watermarked = watermarked;
            this.payload = payload;
            this.verified = verified;
            this.traceRecorded = traceRecorded;
            this.traceError = traceError;
            this.elapsedMillis = elapsedMillis;
            this.traceIdGenerated = traceIdGenerated;
        }

        public Object getWatermarked() {
            return watermarked;
        }

        public WatermarkPayload getPayload() {
            return payload;
        }

        public boolean isVerified() {
            return verified;
        }

        public boolean isTraceRecorded() {
            return traceRecorded;
        }

        public String getTraceError() {
            return traceError;
        }

        public long getElapsedMillis() {
            return elapsedMillis;
        }

        public boolean isTraceIdGenerated() {
            return traceIdGenerated;
        }

        /** 追踪码（载荷为空 ⇒ null）。 */
        public String getTraceId() {
            return payload == null ? null : payload.getTraceId();
        }
    }

    /**
     * ★ 溯源反查结果（**三态**）。
     */
    public static class TraceLookupResult {

        /** 是否查到。 */
        private final boolean found;
        /** 台账是否不可用（**与「没查到」严格区分**）。 */
        private final boolean unavailable;
        /** 溯源记录（查到时非空）。 */
        private final WatermarkTrace trace;
        /** 说明（未查到 / 不可用时的原因）。 */
        private final String message;

        private TraceLookupResult(boolean found, boolean unavailable, WatermarkTrace trace, String message) {
            this.found = found;
            this.unavailable = unavailable;
            this.trace = trace;
            this.message = message;
        }

        /**
         * 查到了。
         *
         * @param trace 记录
         * @return 结果
         */
        public static TraceLookupResult found(WatermarkTrace trace) {
            return new TraceLookupResult(true, false, trace, "已找到溯源记录");
        }

        /**
         * 查到了但台账里没有这条（**合规缺口**，不是故障）。
         *
         * @param message 说明
         * @return 结果
         */
        public static TraceLookupResult notFound(String message) {
            return new TraceLookupResult(false, false, null, message);
        }

        /**
         * 台账不可用（**基础设施故障**）。
         *
         * @param message 说明
         * @return 结果
         */
        public static TraceLookupResult unavailable(String message) {
            return new TraceLookupResult(false, true, null, message);
        }

        public boolean isFound() {
            return found;
        }

        public boolean isUnavailable() {
            return unavailable;
        }

        public WatermarkTrace getTrace() {
            return trace;
        }

        public String getMessage() {
            return message;
        }

        /**
         * 汇总视图（对外输出）。
         *
         * @return 键值视图
         */
        public java.util.Map<String, Object> toView() {
            java.util.Map<String, Object> view = new java.util.LinkedHashMap<>();
            view.put("found", found);
            view.put("unavailable", unavailable);
            view.put("message", message);
            view.put("trace", trace == null ? null : trace.toView());
            return view;
        }
    }
}
