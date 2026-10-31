package com.example.spihost;

import com.zifang.z.watermark.core.spi.WatermarkTraceQuery;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * 模拟「自己提供溯源 SPI 的宿主」（M2 #23）。
 *
 * <p>与 {@code com.example.embedhost.StarterHostTestApplication} 分处<b>不同包树</b>：
 * 组件扫描互不可见，引擎装配只能走 starter 的 {@code AutoConfiguration.imports}。
 * 第二个用例验的因此是「imports 装配 + ObjectProvider 自动接线」，
 * 而不是「包贴近被组件扫描兜底」。
 *
 * @author zifang
 */
@SpringBootApplication
public class SpiHostTestApplication {

    @Bean
    public RecordingTraceRecorder watermarkTraceRecorder() {
        return new RecordingTraceRecorder();
    }

    @Bean
    public WatermarkTraceQuery watermarkTraceQuery() {
        return new EmptyTraceQuery();
    }
}
