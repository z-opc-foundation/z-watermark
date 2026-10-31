package com.zifang.z.watermark.starter.config;

import com.zifang.z.watermark.core.service.WatermarkService;
import com.zifang.z.watermark.core.spi.WatermarkTraceQuery;
import com.zifang.z.watermark.core.spi.WatermarkTraceRecorder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * z-watermark <b>starter 自动装配</b>（引擎装配，M2 #23 starter 拆分）。
 *
 * <p><b>定位</b>：只把<b>引擎</b>（{@link WatermarkService}）装配进宿主应用。宿主只需把
 * {@code z-watermark-starter} 放进 classpath 即可零手工接线。
 *
 * <p><b>溯源出口走 SPI，不由本 starter 硬接</b>：水印的溯源能力靠
 * {@link WatermarkTraceRecorder} / {@link WatermarkTraceQuery} 两个 SPI。
 * 本装配**自动接上容器里已有的这两个 SPI bean**（有就用，没有就不接）——
 * 这样：
 * <ul>
 *   <li>宿主不提供 SPI ⇒ 引擎照常打水印，只是没有溯源台账（响应里 {@code traceRecorded=false} 明确标出）；</li>
 *   <li>宿主提供 SPI（比如指向自家审计系统）⇒ 自动接上，不用改本 starter。</li>
 * </ul>
 *
 * <p>⚠️ <b>为什么不用 {@code @Value} 硬读一个「审计基址」配置</b>：那样会把「溯源后端是 HTTP 服务」
 * 这个**部署事实**焊进引擎装配。SPI 让宿主自己决定后端形态（HTTP / 直写库 / 消息队列都行）。
 * 独立服务形态（{@code z-watermark-web}）就是靠提供 {@code AuditTraceHttpClient} 这个 SPI 实现来接 z-audit 的。
 *
 * <p><b>让位口径</b>：{@code @ConditionalOnMissingBean} ⇒ 宿主自己装配了 {@code WatermarkService}
 * （比如想加自定义水印算法）时本装配让位。
 *
 * <p>⚠️ 本装配**不扫描**包树（无 {@code @ComponentScan}）—— 跨域交互按组织口径走 HTTP 边界。
 *
 * @author zifang
 */
@AutoConfiguration
public class WatermarkStarterAutoConfiguration {

    /**
     * 水印服务（**三种内置实现** + 自动接上容器里的溯源 SPI）。
     *
     * <p>⚠️ 用 {@link ObjectProvider} 而不是直接注入两个 SPI：
     * 直接注入会让「没有 SPI bean」的宿主<b>启动失败</b>（必备依赖缺失）。
     * {@code ifAvailable} 的语义是「有就接上，没有就跳过」⇒ 两种宿主都成立，
     * 且**不依赖 bean 注册顺序**。
     *
     * @param traceRecorderProvider 溯源写入 SPI（可选）
     * @param traceQueryProvider    溯源查询 SPI（可选）
     * @return 水印服务
     */
    @Bean
    @ConditionalOnMissingBean
    public WatermarkService watermarkService(ObjectProvider<WatermarkTraceRecorder> traceRecorderProvider,
                                             ObjectProvider<WatermarkTraceQuery> traceQueryProvider) {
        WatermarkService service = new WatermarkService();
        traceRecorderProvider.ifAvailable(service::setTraceRecorder);
        traceQueryProvider.ifAvailable(service::setTraceQuery);
        return service;
    }
}
