package com.zifang.z.watermark.starter;

import com.example.embedhost.StarterHostTestApplication;
import com.example.spihost.RecordingTraceRecorder;
import com.example.spihost.SpiHostTestApplication;
import com.zifang.z.watermark.core.domain.WatermarkPayload;
import com.zifang.z.watermark.core.domain.WatermarkType;
import com.zifang.z.watermark.core.service.WatermarkService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * z-watermark starter <b>嵌入式装配</b>验收（M2 #23）。
 *
 * <p>两个判据：
 * <ol>
 *   <li><b>纯引擎可用</b>：宿主只放 starter jar（无 web、无 SPI）⇒ 能打水印、能提取回载荷；</li>
 *   <li><b>溯源 SPI 自动接上</b>：宿主在自己的配置里提供 {@code WatermarkTraceRecorder} /
 *       {@code WatermarkTraceQuery} ⇒ 引擎自动接上，不用改 starter、不用手工 set。</li>
 * </ol>
 *
 * <p>两个宿主模拟应用分处<b>不同包树</b>（{@code com.example.embedhost} / {@code com.example.spihost}），
 * 且与 starter 装配类毫无关系 ⇒ 组件扫描绝无可能兜底，装配只能走 AutoConfiguration.imports。
 *
 * @author zifang
 */
class StarterEmbedTest {

    private static WatermarkPayload payload() {
        return new WatermarkPayload("op-001", "tenant-9", "C20261031001", "trace-abc-123",
                1767225600000L);
    }

    @Test
    @DisplayName("★ 宿主只放 starter jar（无 SPI）⇒ 水印引擎自动装配且真能打/取")
    void hostEmbeddingStarterGetsWorkingEngine() {
        try (var ctx = new SpringApplicationBuilder(StarterHostTestApplication.class)
                .web(WebApplicationType.NONE)
                .run()) {

            WatermarkService service = ctx.getBean(WatermarkService.class);
            assertNotNull(service, "★ 宿主应用必须能仅靠 AutoConfiguration.imports 装配出 WatermarkService");

            Object watermarked = service.embed(WatermarkType.VISIBLE_TEXT, "合同正文内容", payload());
            assertNotNull(watermarked, "★ 打水印不能返回 null");

            WatermarkPayload extracted = service.extract(WatermarkType.VISIBLE_TEXT, watermarked);
            assertNotNull(extracted, "★ 打完必须能提取回来（否则引擎是空壳）");
            assertEquals("trace-abc-123", extracted.getTraceId(), "★ 提取回的追踪码必须一致");
        }
    }

    @Test
    @DisplayName("★ 宿主自己提供溯源 SPI ⇒ 引擎自动接上并调用（不用改 starter、不用手工 set）")
    void hostProvidedTraceSpiIsWiredAutomatically() {
        try (var ctx = new SpringApplicationBuilder(SpiHostTestApplication.class)
                .web(WebApplicationType.NONE)
                .run()) {

            WatermarkService service = ctx.getBean(WatermarkService.class);
            RecordingTraceRecorder recorder = ctx.getBean(RecordingTraceRecorder.class);

            // 打一次带追踪的水印 ⇒ 会走 recorder 出口 ⇒ 证明 SPI 真被接上了。
            WatermarkService.TracedWatermarkResult result =
                    service.embedTraced(WatermarkType.VISIBLE_TEXT, "合同正文", payload());
            assertNotNull(result);

            assertTrue(recorder.isCalled(),
                    "★ 宿主提供的 WatermarkTraceRecorder 必须被引擎自动接上并调用");
            assertEquals("trace-abc-123", recorder.getLastTraceId(),
                    "★ 传给出口的追踪码必须来自载荷（单一来源口径）");
        }
    }
}
