package com.wgh.rag.kb.port.vector;

import java.util.List;

/**
 * 集合游标扫描结果（供回填/对账使用）。
 *
 * @param records    本页记录
 * @param nextCursor 下一页游标；为 null 表示已到末尾
 */
public record ScanPage(List<VectorRecord> records, String nextCursor) {

    public static ScanPage empty() {
        return new ScanPage(List.of(), null);
    }
}
