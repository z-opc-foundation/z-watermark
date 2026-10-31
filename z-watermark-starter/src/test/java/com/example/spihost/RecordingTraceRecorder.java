package com.example.spihost;

import com.zifang.z.watermark.core.domain.WatermarkTrace;
import com.zifang.z.watermark.core.spi.WatermarkTraceRecorder;

/**
 * 记录型溯源写入出口：宿主自己的实现，不引用 starter 的任何装配类。
 *
 * @author zifang
 */
public class RecordingTraceRecorder implements WatermarkTraceRecorder {

    boolean called;
    String lastTraceId;

    @Override
    public boolean record(WatermarkTrace trace) {
        this.called = true;
        this.lastTraceId = trace == null ? null : trace.getTraceId();
        return true;
    }

    @Override
    public String name() {
        return "host-recorder";
    }

    /** 出口是否被调用过。 */
    public boolean isCalled() {
        return called;
    }

    /** 最近一次出口收到的追踪码。 */
    public String getLastTraceId() {
        return lastTraceId;
    }
}
