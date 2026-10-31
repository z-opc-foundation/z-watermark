package com.example.spihost;

import com.zifang.z.watermark.core.domain.WatermarkTrace;
import com.zifang.z.watermark.core.spi.WatermarkTraceQuery;

/**
 * 空的溯源查询出口：只为了让容器里两个 SPI 都齐。
 *
 * @author zifang
 */
public class EmptyTraceQuery implements WatermarkTraceQuery {

    @Override
    public WatermarkTrace find(String traceId) {
        return null;
    }

    @Override
    public String name() {
        return "host-query";
    }
}
