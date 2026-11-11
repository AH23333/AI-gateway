package com.ai.gateway.router.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * router 模块配置项（application.yml 中 router.* 前缀）
 *
 * 全部带默认值：零配置即可启动（课程演示友好），
 * 生产部署用环境变量覆盖（app 模块 yml 中占位符化）。
 */
@Data
@ConfigurationProperties(prefix = "router")
public class RouterProperties {

    /** 上游连接超时（毫秒） */
    private int connectTimeoutMs = 3_000;

    /** 非流式调用总超时（毫秒），覆盖到响应体接收完成 */
    private int callTimeoutMs = 180_000;

    /** 流式 SseEmitter 总超时（毫秒）—— Spring 异步超时触发 onTimeout */
    private long streamTimeoutMs = 180_000;

    /** 流式空闲超时（毫秒）—— 超过该时长未收到上游任何数据则中断 */
    private long streamIdleTimeoutMs = 60_000;

    /** SSE 心跳间隔（毫秒）：无数据时每 15s 发一个注释帧保活 */
    private long heartbeatIntervalMs = 15_000;

    /** D6 报文采样落盘根目录（相对路径基于进程工作目录） */
    private String payloadDir = "data/payload";

    /** D6 报文采样开关 */
    private boolean payloadSampleEnabled = true;

    /** 批量日志 flush 周期（毫秒），billing 模块 BatchLogWriter 同名消费 */
    private long logFlushIntervalMs = 500;
}
