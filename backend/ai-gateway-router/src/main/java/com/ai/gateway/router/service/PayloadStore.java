package com.ai.gateway.router.service;

import com.ai.gateway.common.constant.RedisKeyConstant;
import com.ai.gateway.common.util.JsonUtil;
import com.ai.gateway.router.config.RouterProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 报文采样落盘（D6，选型书 3.2：报文出库）
 *
 * 为什么采样而不是全量落盘？
 * 明细表已剥离 MEDIUMTEXT 报文列（窄表 D4），报文放本地磁盘；
 * 但全量落盘磁盘增长与对话 QPS 同阶，课程/演示场景无回放价值 ——
 * "每分钟首请求"采样率兼顾"有样本可查"与"磁盘可控"。
 *
 * 节流实现：Redis SET NX EX 65s（key=route:payload-sample:{yyyyMMddHHmm}），
 * 拿到锁的请求才落盘；单实例下天然互斥，多实例下 Redis 全局互斥。
 *
 * 失败语义：落盘失败只降级（payload_ref=null），绝不影响主链路。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PayloadStore {

    private static final DateTimeFormatter MINUTE_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyyMM");

    private final StringRedisTemplate redisTemplate;
    private final RouterProperties properties;

    /**
     * 采样落盘（非采样请求直接返回 null）
     *
     * @return payload_ref（inv/{yyyyMM}/{traceId}.json）；未采样/失败返回 null
     */
    public String store(String traceId, Object requestPayload, Object responsePayload) {
        if (!properties.isPayloadSampleEnabled()) {
            return null;
        }
        // 1. 每分钟首请求节流
        LocalDateTime now = LocalDateTime.now();
        String throttleKey = RedisKeyConstant.PAYLOAD_SAMPLE + now.format(MINUTE_FMT);
        Boolean firstOfMinute = redisTemplate.opsForValue()
                .setIfAbsent(throttleKey, "1", Duration.ofSeconds(65));
        if (!Boolean.TRUE.equals(firstOfMinute)) {
            return null;
        }

        // 2. 落盘 {payloadDir}/inv/{yyyyMM}/{traceId}.json
        String month = now.format(MONTH_FMT);
        String ref = "inv/" + month + "/" + traceId + ".json";
        try {
            Path dir = Paths.get(properties.getPayloadDir(), "inv", month);
            Files.createDirectories(dir);
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("traceId", traceId);
            envelope.put("capturedAt", now.toString());
            envelope.put("request", requestPayload);
            envelope.put("response", responsePayload);
            Files.writeString(dir.resolve(traceId + ".json"),
                    JsonUtil.toJson(envelope), StandardCharsets.UTF_8);
            log.info("报文采样落盘: {}", ref);
            return ref;
        } catch (Exception e) {
            log.warn("报文采样落盘失败(已降级跳过): ref={}, err={}", ref, e.getMessage());
            return null;
        }
    }
}
