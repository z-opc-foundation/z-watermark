package com.zifang.z.watermark.web.config;

import com.zifang.z.watermark.core.service.WatermarkService;
import com.zifang.z.watermark.web.client.AuditTraceHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 * <p><b>★ M2 #22：溯源闭环装配</b>：
 * <ul>
 *   <li>配置 {@code z.watermark.audit-base-url}（如 {@code http://127.0.0.1:8097}）
 *       ⇒ 本域挂上审计域的 HTTP 客户端 ⇒ 打水印时把溯源记录交给 z-audit 落台账，
 *       并支持按追踪码反查；</li>
 *   <li>不配置 ⇒ **不挂** ⇒ 打水印照常工作，但**没有溯源台账**
 *       （响应里 {@code traceRecorded=false} 会明确标出这个缺口）。</li>
 * </ul>
 *
 * <p><b>⚠️ 为什么不让本仓自己写 {@code z_audit_event} 表</b>：哈希链要求**单写者** ——
 * 多个写者各持自己的链尾 ⇒ 链分叉 ⇒ 链校验必然报断。
 * z-audit 服务是该表的唯一权威写者。
 *
 * @author zifang
 */
@AutoConfiguration
@Import(WatermarkModuleDataSource.class)
public class WatermarkAutoConfiguration {

    /**
     * 审计域基址（**可空** ⇒ 不挂溯源出口）。
     *
     * @return 基址
     */
    @Value("${z.watermark.audit-base-url:}")
    private String auditBaseUrl;

    /**
     * 水印服务（**三种内置实现** + 溯源闭环）。
     *
     * <p>{@code @ConditionalOnMissingBean} ⇒ 宿主自己装配了同类型 bean 时本仓让位
     * （比如宿主想加自定义水印算法）。
     *
     * @return 水印服务
     */
    @Bean
    @ConditionalOnMissingBean
    public WatermarkService watermarkService() {
        WatermarkService service = new WatermarkService();
        if (auditBaseUrl != null && !auditBaseUrl.trim().isEmpty()) {
            AuditTraceHttpClient client = new AuditTraceHttpClient(auditBaseUrl.trim());
            service.setTraceRecorder(client);
            service.setTraceQuery(client);
        }
        return service;
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
