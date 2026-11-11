package com.ai.gateway.router.mapper;

import com.ai.gateway.common.pojo.ModelPrice;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 模型价格 Mapper（t_model_price 版本化价格，选型书 3.7）
 *
 * 生效价定义：effective_time <= NOW() 且 status=1 的最大 version。
 * 改价 = 插入新版本行，不 UPDATE 老行 —— 历史账单凭 price_version 复算。
 */
@Mapper
public interface ModelPriceMapper extends BaseMapper<ModelPrice> {

    /** 查询模型当前生效价（缓存入口在 ModelConfigService） */
    @Select("SELECT * FROM t_model_price "
            + "WHERE model_code = #{modelCode} AND status = 1 AND effective_time <= NOW() "
            + "ORDER BY version DESC LIMIT 1")
    ModelPrice selectEffective(@Param("modelCode") String modelCode);
}
