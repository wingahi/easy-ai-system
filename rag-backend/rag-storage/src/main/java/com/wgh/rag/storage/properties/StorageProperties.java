package com.wgh.rag.storage.properties;

import com.wgh.rag.storage.constant.StorageConstants;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 文件存储配置：{@code wgh.rag.storage.type=local|oss}，两组配置块互不影响。
 */
@Data
@Component
@ConfigurationProperties(prefix = StorageConstants.CONFIG_PREFIX)
public class StorageProperties {

    /** 存储类型：local（默认）/ oss */
    private String type = StorageConstants.TYPE_LOCAL;

    private Local local = new Local();

    private Oss oss = new Oss();

    @Data
    public static class Local {
        /** 本地根目录，默认沿用历史配置 ./uploads */
        private String baseDir = StorageConstants.DEFAULT_BASE_DIR;
        /** 静态访问前缀，仅用于 resolveUrl 返回逻辑地址 */
        private String urlPrefix = StorageConstants.DEFAULT_URL_PREFIX;
        /** 单文件大小上限（MB） */
        private int maxFileSizeMb = StorageConstants.DEFAULT_MAX_FILE_SIZE_MB;
    }

    @Data
    public static class Oss {
        /** 公网 endpoint，如 https://oss-cn-hangzhou.aliyuncs.com */
        private String endpoint;
        /** 内网 endpoint（可选），配置后服务端读写走内网 */
        private String internalEndpoint;
        private String bucket;
        private String accessKeyId;
        private String accessKeySecret;
        /** 对象 key 前缀，避免与同 bucket 其他业务冲突，可为空 */
        private String keyPrefix = "";
        /** true 时 resolveUrl 返回公网直链；false 时返回签名 URL */
        private boolean publicRead = false;
        /** 签名 URL 有效期（分钟） */
        private int signedUrlExpireMinutes = StorageConstants.DEFAULT_SIGNED_URL_EXPIRE_MINUTES;
        /** 上传失败重试次数（含首次） */
        private int maxRetries = StorageConstants.DEFAULT_MAX_RETRIES;
        /** 单文件大小上限（MB） */
        private int maxFileSizeMb = StorageConstants.DEFAULT_MAX_FILE_SIZE_MB;

        /** 返回缺失的必填配置项，便于启动 WARN 与调用时错误提示。 */
        public List<String> missingConfigFields() {
            List<String> missing = new ArrayList<>();
            if (isBlank(endpoint)) missing.add("endpoint");
            if (isBlank(bucket)) missing.add("bucket");
            if (isBlank(accessKeyId)) missing.add("accessKeyId");
            if (isBlank(accessKeySecret)) missing.add("accessKeySecret");
            return missing;
        }

        public boolean configComplete() {
            return missingConfigFields().isEmpty();
        }

        private static boolean isBlank(String value) {
            return value == null || value.isBlank();
        }
    }
}
