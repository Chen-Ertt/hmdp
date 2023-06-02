package com.hmdp.dto;

import lombok.Data;

import java.util.List;

/**
 * 实现滚动分页的dto
 */
@Data
public class ScrollResult {
    private List<?> list;
    private Long minTime;
    private Integer offset;
}
