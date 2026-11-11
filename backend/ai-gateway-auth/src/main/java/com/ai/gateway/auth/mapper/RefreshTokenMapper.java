package com.ai.gateway.auth.mapper;

import com.ai.gateway.common.pojo.RefreshToken;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * RefreshToken 表 Mapper（v2：token_hash 精确匹配）
 */
@Mapper
public interface RefreshTokenMapper extends BaseMapper<RefreshToken> {

    /**
     * 吊销指定用户的全部启用态 RefreshToken
     * （签发新 Token 前调用，保证每用户至多一条有效刷新凭证，基线行为保留）
     */
    @Update("UPDATE t_refresh_token SET status = 0 "
            + "WHERE user_id = #{userId} AND status = 1")
    int revokeAllByUserId(@Param("userId") Long userId);
}
