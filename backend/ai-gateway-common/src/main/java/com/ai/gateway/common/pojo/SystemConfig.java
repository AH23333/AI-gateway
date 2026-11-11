package com.ai.gateway.common.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统设置实体 —— 对应 t_system_config 表（v2.4 系统设置中心）
 *
 * key-value 结构：DB 覆盖 yml，管理端在线改免重启；
 * description 由代码定义维护（启动时同步回写），config_value 由管理端维护。
 */
@Data
@TableName("t_system_config")
public class SystemConfig {

    /** 配置键（点分层级，如 site.name） */
    @TableId(type = IdType.INPUT)
    private String configKey;

    /** 配置值（文本存储，读取方经 SystemConfigHolder 按需转型） */
    private String configValue;

    /** 人类可读说明（设置页展示，代码定义同步） */
    private String description;

    /** 最近修改人用户 ID（access 过滤器注入的 X-User-Id） */
    private Long updatedBy;

    private LocalDateTime gmtModified;
}
