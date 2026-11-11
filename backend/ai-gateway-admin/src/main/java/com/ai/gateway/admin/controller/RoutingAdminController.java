package com.ai.gateway.admin.controller;

import com.ai.gateway.admin.annotation.Audited;
import com.ai.gateway.admin.service.RoutingAdminService;
import com.ai.gateway.common.dto.PageResult;
import com.ai.gateway.common.dto.Result;
import com.ai.gateway.common.pojo.InvokeLogV2;
import com.ai.gateway.common.pojo.RoutingConfig;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 管理端接口 —— 智能路由配置与分析（ADMIN 角色）
 *
 * 热更新语义：所有写操作立即失效缓存，下一笔请求生效，无需重启。
 */
@Tag(name = "管理端-智能路由")
@RestController
@RequestMapping("/admin/routing")
@RequiredArgsConstructor
public class RoutingAdminController {

    private final RoutingAdminService routingAdminService;

    @Operation(summary = "当前路由配置（开关 + 权重）")
    @GetMapping("/config")
    public Result<RoutingConfig> getConfig() {
        return Result.ok(routingAdminService.getConfig());
    }

    @Operation(summary = "路由总开关（0=直连基线 1=智能路由）")
    @Audited(action = "ROUTING_SWITCH", desc = "路由总开关切换")
    @PutMapping("/switch")
    public Result<Void> updateSwitch(@RequestParam Integer enabled) {
        routingAdminService.updateSwitch(enabled);
        return Result.ok();
    }

    @Operation(summary = "打分权重热更新（w_q + w_c = 1）")
    @Audited(action = "ROUTING_WEIGHTS", desc = "路由打分权重更新")
    @PutMapping("/weights")
    public Result<Void> updateWeights(@RequestParam Double weightQuality,
                                      @RequestParam Double weightCost) {
        routingAdminService.updateWeights(weightQuality, weightCost);
        return Result.ok();
    }

    @Operation(summary = "逐模型实时路由分析（质量/延迟/健康分/熔断/价格）")
    @GetMapping("/analysis")
    public Result<List<Map<String, Object>>> analysis() {
        return Result.ok(routingAdminService.analysis());
    }

    @Operation(summary = "智能路由决策日志分页（可解释性回放）")
    @GetMapping("/decisions")
    public Result<PageResult<InvokeLogV2>> decisions(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.ok(routingAdminService.decisions(page, size));
    }
}
