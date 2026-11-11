package com.ai.gateway.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 分页结果通用 VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageResult<T> {

    /** 当前页数据 */
    private List<T> records;

    /** 总记录数 */
    private long total;

    /** 当前页码（从 1 开始） */
    private int current;

    /** 每页大小 */
    private int size;

    /** 总页数 */
    private int pages;

    /** 是否有下一页 */
    private boolean hasNext;

    /** 是否有上一页 */
    private boolean hasPrevious;

    /**
     * 构造分页结果（1-based 页码约定，与 PageConstant.DEFAULT_PAGE / MyBatis-Plus Page 对齐）
     */
    public static <T> PageResult<T> of(List<T> records, long total, int current, int size) {
        int pages = size > 0 ? (int) Math.ceil((double) total / size) : 0;
        return PageResult.<T>builder()
                .records(records)
                .total(total)
                .current(current)
                .size(size)
                .pages(pages)
                .hasNext(current < pages)
                .hasPrevious(current > 1)
                .build();
    }
}
