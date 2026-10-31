package com.zifang.z.watermark.core.spi;

import com.zifang.z.watermark.core.domain.WatermarkPayload;
import com.zifang.z.watermark.core.domain.WatermarkType;

/**
 * 水印算法 SPI —— 打水印与验水印的一对能力。
 *
 * <p>每个实现同时提供 {@link #embed}（嵌入）与 {@link #extract}（提取/验证），
 * 因为**能打不能验的水印没有意义**（M1 的验收标准就是成对）。
 *
 * @author zifang
 */
public interface Watermarker {

    /**
     * 嵌入水印。
     *
     * @param content 载体内容（文本 / 图片字节等；可空）
     * @param payload 水印载荷（**不可为 null**）
     * @return 带水印的内容（同类型；失败 ⇒ 返回原内容）
     */
    Object embed(Object content, WatermarkPayload payload);

    /**
     * 提取水印。
     *
     * @param content 载体内容（可空）
     * @return 提取到的载荷（**可能为 null** —— 表示没有水印或提取失败）
     */
    WatermarkPayload extract(Object content);

    /**
     * 本实现支持的水印类型。
     *
     * @return 水印类型
     */
    WatermarkType type();
}
