package com.ai.gateway.common.pojo;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户实体 —— 对应 t_user 表（v2：新增预扣字段）
 *
 * v2 关键变更（选型书 3.1/5.1）：
 * - 主键改为自增（选型书 3.8，删除 UidGenerator DB 分配）；
 * - 新增 reserved_daily / reserved_monthly：两段式计费的"预扣水位"，
 *   reserve 时原子加、settle/reconcile 时减，
 *   与 used_* 一起参与"是否超配额"的同一条条件更新判断（防并发超扣）。
 *
 * 配额语义：
 * daily_quota / monthly_quota  — 配额上限（Token 数）
 * used_daily  / used_monthly   — 已结算用量（settle 事务内累加）
 * reserved_*                   — 在途预扣水位（reserve 加、结算/回滚减）
 * 不变量：used_* + reserved_* <= quota（由条件更新在写入点保证）
 */
@Data
@TableName("t_user")
public class User {

    /** 主键（自增，选型书 3.8） */
    @JsonSerialize(using = ToStringSerializer.class)
    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    /** BCrypt 密文，永不返回前端 */
    @JsonIgnore
    private String password;

    private String email;

    /** 每日配额上限（Token 数） */
    private Long dailyQuota;

    /** 每月配额上限（Token 数） */
    private Long monthlyQuota;

    /** 当日已结算用量 */
    private Long usedDaily;

    /** 当月已结算用量 */
    private Long usedMonthly;

    /** 当日在途预扣水位（两段式，v2 新增） */
    private Long reservedDaily;

    /** 当月在途预扣水位（两段式，v2 新增） */
    private Long reservedMonthly;

    /** 角色：ADMIN=管理员, USER=普通用户 */
    private String role;

    /** 状态：0=禁用, 1=启用 */
    private Integer status;

    private LocalDateTime gmtCreate;

    private LocalDateTime gmtModified;
}
