package com.zifang.z.watermark.core.spi;

import com.zifang.z.watermark.core.domain.WatermarkTrace;

/**
 * 水印溯源写入出口（SPI）。
 *
 * <p><b>为什么定 SPI 而不直接依赖 z-audit</b>：组织口径是「跨域交互走 HTTP 边界，
 * 不把别人的 jar 拖进本仓上下文」。若 z-watermark-core 直接依赖 z-audit-core，
 * 两个能力仓就绑死了（改 z-audit 的模型会连带 z-watermark 编译失败），
 * 且本仓的「单仓可装配」属性被破坏。
 * ⇒ 本仓只定**接口**，由 web 层用 HTTP 调 z-audit 实现。
 *
 * <p><b>契约</b>：
 * <ul>
 *   <li><b>不抛异常</b> —— 写台账失败不得拖垮打水印的主链路；</li>
 *   <li><b>幂等</b> —— 同一 {@code eventId} 重复写只留一条。</li>
 * </ul>
 *
 * @author zifang
 */
public interface WatermarkTraceRecorder {

    /**
     * 写一条溯源记录。
     *
     * @param trace 溯源记录（可空 ⇒ 直接返回 false）
     * @return true = 写入成功（含「已存在，幂等命中」）
     */
    boolean record(WatermarkTrace trace);

    /**
     * 出口名（用于日志）。
     *
     * @return 名称
     */
    String name();
}
