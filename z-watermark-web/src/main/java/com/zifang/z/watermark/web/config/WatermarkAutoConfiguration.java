package com.zifang.z.watermark.web.config;

import com.zifang.z.watermark.web.client.AuditTraceHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * z-watermark <b>管控面（web）自动装配</b>。
 *
 * <p><b>只管部署关切</b>：本域数据源 + 健康检查 + **溯源 SPI 的 HTTP 实现**。
 * <b>引擎装配已拆到 {@code z-watermark-starter}</b>（M2 #23）——
 * {@link com.zifang.z.watermark.core.service.WatermarkService} 由
 * {@code com.zifang.z.watermark.starter.config.WatermarkStarterAutoConfiguration} 提供，
 * 本类不再重复定义。
 *
 * <p><b>★ 溯源闭环的接线方式（M2 #23 改）</b>：早先本类自己 {@code new WatermarkService()}
 * 再 {@code setTraceRecorder(...)}。拆分后本类<b>只提供 SPI bean</b>（{@link AuditTraceHttpClient}
 * 同时实现写入与查询两个 SPI），starter 的装配会**自动把它接进引擎**。
 * ⇒ 引擎装配只有一处，本类不碰引擎实例；两侧也不依赖 bean 注册顺序（starter 用
 * {@code ObjectProvider} 惰性取）。
 *
 * <p><b>★ M2 #22：溯源闭环口径</b>：
 * <ul>
 *   <li>配置 {@code z.watermark.audit-base-url}（如 {@code http://127.0.0.1:8097}）
 *       ⇒ 挂上审计域 HTTP 客户端 ⇒ 打水印时把溯源记录交给 z-audit 落台账，并支持按追踪码反查；</li>
 *   <li>不配置 ⇒ **不挂** ⇒ 打水印照常工作，但**没有溯源台账**
 *       （响应里 {@code traceRecorded=false} 会明确标出这个缺口）。</li>
 * </ul>
 *
 * <p><b>⚠️ 为什么不让本仓自己写 {@code z_audit_event} 表</b>：哈希链要求**单写者** ——
 * 多个写者各持自己的链尾 ⇒ 链分叉 ⇒ 链校验必然报断。
 * z-audit 服务是该表的唯一权威写者。
 *
 * <p>⚠️ 本装配**不扫描**包树（无 {@code @ComponentScan}）—— 跨域交互按组织口径走 HTTP 边界。
 *
 * @author zifang
 */
@AutoConfiguration
@Import(WatermarkModuleDataSource.class)
public class WatermarkAutoConfiguration {

    /**
     * 溯源台账 HTTP 客户端（**同时是写入与查询两个 SPI 的实现**）。
     *
     * <p>{@code @ConditionalOnProperty} 要求 {@code z.watermark.audit-base-url} **非空** ——
     * 用 SpEL 而不是 {@code @ConditionalOnProperty} 的默认匹配：后者把「键存在但值为空串」
     * 也当成命中（空串不是 false），会让空配置也去建一个基址为空的客户端。
     *
     * @param baseUrl 审计域基址
     * @return 溯源 SPI 实现（starter 会自动接进引擎）
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnExpression("'${z.watermark.audit-base-url:}' != ''")
    public AuditTraceHttpClient auditTraceHttpClient(
            @Value("${z.watermark.audit-base-url:}") String baseUrl) {
        return new AuditTraceHttpClient(baseUrl.trim());
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
