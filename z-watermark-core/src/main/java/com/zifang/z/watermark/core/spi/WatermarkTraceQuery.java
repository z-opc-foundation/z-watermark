package com.zifang.z.watermark.core.spi;

import com.zifang.z.watermark.core.domain.WatermarkTrace;

/**
 * 水印溯源查询出口（SPI）。
 *
 * <p><b>三态必须分清</b>（这是本 SPI 存在的核心理由）：
 * <ul>
 *   <li><b>查到</b> —— {@link #find(String)} 返回记录；</li>
 *   <li><b>没查到</b> —— 返回 {@code null}（追踪码不存在 / 台账里没有）；</li>
 *   <li><b>查不了</b> —— **抛 {@link WatermarkTraceUnavailableException}**
 *       （台账服务不可达 / 超时 / 返回异常）。</li>
 * </ul>
 *
 * <p>⚠️ **「没查到」与「查不了」绝不能混为一谈**：
 * 前者说明「这份水印没有台账记录」（可能是没走闭环，也可能是记录被删）——
 * 这是**要报警的合规缺口**；
 * 后者说明「台账服务挂了」—— 这是**基础设施故障**，两者处置动作完全不同。
 * 若都返回 {@code null}，运维会以为「没打过水印」，实际是台账不可用。
 *
 * @author zifang
 */
public interface WatermarkTraceQuery {

    /**
     * 按追踪码查溯源记录。
     *
     * @param traceId 追踪码（可空 ⇒ 返回 {@code null}）
     * @return 记录；查不到返回 {@code null}
     * @throws WatermarkTraceUnavailableException 台账不可用（**必须与「查不到」区分**）
     */
    WatermarkTrace find(String traceId);

    /**
     * 出口名（用于日志）。
     *
     * @return 名称
     */
    String name();
}
