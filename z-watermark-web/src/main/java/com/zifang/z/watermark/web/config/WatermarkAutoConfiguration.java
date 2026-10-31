package com.zifang.z.watermark.web.config;

import com.zifang.z.watermark.core.service.WatermarkService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * z-watermark自动装配。
 *
 * <p>宿主引用本仓时，只要把 {@code z-watermark-web} 放进 classpath，
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * 会把本装配挂上 ⇒ 数据源 + 领域服务齐备。
 *
 * <p>⚠️ 本装配**不扫描**包树（无 {@code @ComponentScan}）—— 跨域交互按组织口径走 HTTP 边界，
 * 不把别人的 jar 拖进本仓上下文。
 *
 * @author zifang
 */
@AutoConfiguration
@Import(WatermarkModuleDataSource.class)
public class WatermarkAutoConfiguration {

    /**
     * 水印服务（**三种内置实现**）。
     *
     * <p>{@code @ConditionalOnMissingBean} ⇒ 宿主自己装配了同类型 bean 时本仓让位
     * （比如宿主想加自定义水印算法）。
     *
     * @return 水印服务
     */
    @Bean
    @ConditionalOnMissingBean
    public WatermarkService watermarkService() {
        return new WatermarkService();
    }

    /**
     * 本域健康检查（探活用）。
     *
     * @return 健康标识
     */
    @Bean
    @ConditionalOnMissingBean
    public WatermarkHealthIndicator watermarkHealthIndicator() {
        return new WatermarkHealthIndicator("z-watermark");
    }
}
