package com.ai.gateway.admin.dto;

import lombok.Data;

/**
 * 用户配额调整请求（管理端）
 *
 * 允许单边调整（只传 dailyQuota 则月配额不变）；
 * 新配额可低于当前已用量（管理判断优先，下次 reserve 自然收紧）。
 */
@Data
public class QuotaUpdateRequest {

    /** 每日配额上限（Token，>0） */
    private Long dailyQuota;

    /** 每月配额上限（Token，>0） */
    private Long monthlyQuota;
}
