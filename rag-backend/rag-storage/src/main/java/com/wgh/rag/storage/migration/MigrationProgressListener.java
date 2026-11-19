package com.wgh.rag.storage.migration;

import com.wgh.rag.storage.service.StorageObjectInfo;

/**
 * 迁移进度监听：异步作业用它把引擎内部的计数实时暴露给查询接口。
 * <p>
 * 回调可能来自多个并发线程，实现方需保证线程安全。
 */
public interface MigrationProgressListener {

    /** 源对象枚举完成，回传总数。 */
    void onTotal(int total);

    /**
     * 单个对象处理结束。
     *
     * @param object      源对象
     * @param outcome     结果
     * @param copiedBytes 实际搬运字节数（仅 COPIED 时大于 0）
     */
    void onObjectFinished(StorageObjectInfo object, MigrationOutcome outcome, long copiedBytes);

    /**
     * 是否已请求取消。引擎在开始处理每个对象前检查；取消是协作式的，
     * 已在处理中的对象会继续完成。
     */
    boolean isCancelled();

    /** 同步调用/测试使用的空实现。 */
    MigrationProgressListener NOOP = new MigrationProgressListener() {
        @Override
        public void onTotal(int total) {
        }

        @Override
        public void onObjectFinished(StorageObjectInfo object, MigrationOutcome outcome, long copiedBytes) {
        }

        @Override
        public boolean isCancelled() {
            return false;
        }
    };
}
