package com.wgh.rag.storage;

/**
 * 存储模块常量：存储类型、配置前缀、key 规则与默认参数。
 * <p>
 * 配置键名（{@code wgh.rag.storage.*}）与类型值（{@code local}/{@code oss}）为对外契约，禁止改动。
 */
public interface StorageConstants {

    /** 配置前缀，对应 wgh.rag.storage.* */
    String CONFIG_PREFIX = "wgh.rag.storage";

    String TYPE_LOCAL = "local";
    String TYPE_OSS = "oss";

    /** 对象 key 的一级目录 */
    String KEY_PREFIX_KB = "kb/";

    /** 本地存储默认根目录（沿用历史配置） */
    String DEFAULT_BASE_DIR = "./uploads";
    /** 本地存储默认静态访问前缀 */
    String DEFAULT_URL_PREFIX = "/uploads";

    /** 单文件大小上限（MB） */
    int DEFAULT_MAX_FILE_SIZE_MB = 50;
    /** OSS 签名 URL 默认有效期（分钟） */
    int DEFAULT_SIGNED_URL_EXPIRE_MINUTES = 60;
    /** OSS 上传默认重试次数（含首次） */
    int DEFAULT_MAX_RETRIES = 3;

    /** 1 MB 的字节数 */
    long BYTES_PER_MB = 1024L * 1024L;
    /** 签名 URL 每分钟毫秒数 */
    long MILLIS_PER_MINUTE = 60_000L;

    /** OSS 公网直链默认协议 */
    String DEFAULT_PUBLIC_URL_SCHEME = "https";

    /** 本地原子写入临时文件前缀/后缀 */
    String LOCAL_TEMP_PREFIX = ".upload-";
    String TEMP_SUFFIX = ".part";
    /** OSS 上传暂存临时文件前缀 */
    String OSS_TEMP_PREFIX = "wgh-oss-upload-";
}
