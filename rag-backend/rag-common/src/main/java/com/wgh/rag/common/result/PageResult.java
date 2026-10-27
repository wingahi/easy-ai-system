package com.wgh.rag.common.result;

import lombok.Data;
import java.io.Serializable;
import java.util.List;

/**
 * 分页结果
 */
@Data
public class PageResult<T> implements Serializable {

    private Long total;
    private Long page;
    private Long size;
    private List<T> records;

    public PageResult() {}

    public PageResult(Long total, Long page, Long size, List<T> records) {
        this.total = total;
        this.page = page;
        this.size = size;
        this.records = records;
    }
}
