package com.ai.gateway.common.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 路由配置实体 —— 对应 t_routing_config 表（单行配置表，id 恒为 1）
 *
 * 承载 FR-11（路由总开关）与 FR-05（打分权重热更新）：
 * 管理端 PUT 接口更新本表 → 进程内缓存（Caffeine 60s + 更新时主动失效）
 * → 无需重启即生效。单体下"改库 + 清缓存"天然一致，
 * 多实例时升级为配置中心广播失效（选型书第八章升级触发条件）。
 */
@Data
@TableName("t_routing_config")
public class RoutingConfig {

    /** 单行表主键，恒为 1 */
    @TableId(type = IdType.INPUT)
    private Long id;

    /** 路由总开关：1=开启智能路由（model 缺省时），0=关闭（缺省模型走 default-model） */
    private Integer enabled;

    /** 质量分权重 w_q（默认 0.6，OQ-01 关闭口径） */
    private Double weightQuality;

    /** 成本权重 w_c（默认 0.4）；约束：w_q + w_c = 1 */
    private Double weightCost;

    private LocalDateTime gmtModified;
}
