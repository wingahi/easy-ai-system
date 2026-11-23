package com.wgh.rag.kb.job;

/**
 * 知识库接入调度中心的作业类型与负载字段常量。
 *
 * <p>集中声明可避免提交端（{@code KnowledgeFileService}）与执行端（各 {@code JobHandler}）
 * 出现字符串漂移；作业类型一经上线不应修改，否则历史作业将因找不到处理器进入死信。</p>
 */
public final class KbJobTypes {

    /** 单文件解析/切分/向量化（BACKGROUND）。 */
    public static final String FILE_PROCESS = "KB_FILE_PROCESS";

    /** 知识库全量重建（BACKGROUND）。 */
    public static final String REBUILD = "KB_REBUILD";

    /** 孤儿存储对象扫描（MAINTENANCE）。 */
    public static final String ORPHAN_CLEANUP = "KB_ORPHAN_CLEANUP";

    /** 过期分片上传会话清理（MAINTENANCE）。 */
    public static final String UPLOAD_CLEANUP = "KB_UPLOAD_CLEANUP";

    /** 长期记忆清理（MAINTENANCE）。 */
    public static final String AGENT_MEMORY_CLEANUP = "KB_AGENT_MEMORY_CLEANUP";

    /** URL 连接器定时同步（MAINTENANCE）。 */
    public static final String CONNECTOR_SYNC = "KB_CONNECTOR_SYNC";

    /** 负载字段：知识库 ID。 */
    public static final String PAYLOAD_KB_ID = "kbId";

    /** 负载字段：文件 ID。 */
    public static final String PAYLOAD_FILE_ID = "fileId";

    /** 负载字段：重建目标向量表。 */
    public static final String PAYLOAD_TARGET_COLLECTION = "targetCollection";

    /** 负载字段：重建目标 embedding 模型 ID。 */
    public static final String PAYLOAD_TARGET_MODEL_ID = "targetModelId";

    /** 负载字段：重建目标向量维度。 */
    public static final String PAYLOAD_TARGET_DIMENSION = "targetDimension";

    private KbJobTypes() {
    }
}
