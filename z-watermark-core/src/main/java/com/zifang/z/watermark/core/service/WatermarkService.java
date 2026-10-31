package com.zifang.z.watermark.core.service;

import com.zifang.z.watermark.core.domain.WatermarkPayload;
import com.zifang.z.watermark.core.domain.WatermarkType;
import com.zifang.z.watermark.core.spi.Watermarker;

import java.util.EnumMap;
import java.util.Map;

/**
 * 水印门面 —— 本仓对外的**唯一主入口**。
 *
 * <p>按水印类型路由到对应实现，同时提供「打 + 验」的成对动作。
 *
 * <p><b>线程安全</b>：实现无状态（载荷是入参）⇒ 可并发调用。
 *
 * @author zifang
 */
public class WatermarkService {

    /** 类型 → 实现。 */
    private final Map<WatermarkType, Watermarker> watermarkers = new EnumMap<>(WatermarkType.class);

    /** 默认构造：装载三种内置实现。 */
    public WatermarkService() {
        register(new VisibleTextWatermarker());
        register(new ImageStegoWatermarker());
        register(new BlindTextWatermarker());
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
}
