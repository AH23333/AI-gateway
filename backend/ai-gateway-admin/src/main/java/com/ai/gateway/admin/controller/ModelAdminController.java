package com.ai.gateway.admin.controller;

import com.ai.gateway.admin.dto.ModelUpsertRequest;
import com.ai.gateway.admin.dto.PriceUpsertRequest;
import com.ai.gateway.admin.service.ModelAdminService;
import com.ai.gateway.common.dto.Result;
import com.ai.gateway.common.pojo.ModelInfo;
import com.ai.gateway.common.pojo.ModelPrice;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端接口 —— 模型与价格管理（ADMIN 角色）
 *
 * 价格版本化（选型书 3.7）：POST /admin/models/{id}/prices 追加新版本并立即生效，
 * 历史版本可查（GET prices），历史账单凭 price_version 复算。
 */
@Tag(name = "管理端-模型与价格")
@RestController
@RequestMapping("/admin/models")
@RequiredArgsConstructor
public class ModelAdminController {

    private final ModelAdminService modelAdminService;

    @Operation(summary = "模型列表（含禁用）")
    @GetMapping
    public Result<List<ModelInfo>> listModels() {
        return Result.ok(modelAdminService.listModels());
    }

    @Operation(summary = "新增模型")
    @PostMapping
    public Result<ModelInfo> createModel(@RequestBody ModelUpsertRequest request) {
        return Result.ok(modelAdminService.createModel(request));
    }

    @Operation(summary = "编辑模型（编码不建议修改）")
    @PutMapping("/{id}")
    public Result<ModelInfo> updateModel(@PathVariable Long id, @RequestBody ModelUpsertRequest request) {
        return Result.ok(modelAdminService.updateModel(id, request));
    }

    @Operation(summary = "删除模型（有调用日志/被引用时拒绝，只能禁用）")
    @DeleteMapping("/{id}")
    public Result<Void> deleteModel(@PathVariable Long id) {
        modelAdminService.deleteModel(id);
        return Result.ok();
    }

    @Operation(summary = "价格版本历史")
    @GetMapping("/{id}/prices")
    public Result<List<ModelPrice>> priceHistory(@PathVariable Long id) {
        return Result.ok(modelAdminService.priceHistory(id));
    }

    @Operation(summary = "新增价格版本（立即生效）")
    @PostMapping("/{id}/prices")
    public Result<ModelPrice> addPrice(@PathVariable Long id, @RequestBody PriceUpsertRequest request) {
        return Result.ok(modelAdminService.addPrice(id, request));
    }
}
