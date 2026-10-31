package com.zifang.z.watermark.web.api;

import com.zifang.z.watermark.web.config.WatermarkHealthIndicator;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 本域健康检查端点（前缀 {@code /api/watermark/health}）。
 *
 * @author zifang
 */
@RestController
@RequestMapping("/api/watermark")
public class WatermarkHealthController {

    private final WatermarkHealthIndicator healthIndicator;

    public WatermarkHealthController(WatermarkHealthIndicator healthIndicator) {
        this.healthIndicator = healthIndicator;
    }

    /**
     * 健康检查。
     *
     * @return 健康信息
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("component", healthIndicator.getComponent());
        body.put("status", healthIndicator.getStatus());
        return body;
    }
}
