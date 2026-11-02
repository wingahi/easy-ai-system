package com.wgh.rag.kb.parser;

import com.wgh.rag.common.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.List;

/**
 * 文档解析分发器：遍历注入的 {@link DocumentParser} 列表，找到支持该扩展名的实现。
 * <p>
 * 统一在分发层做安全检查（存在性 / 空文件 / 大小上限）与友好错误包装（损坏文件 / 依赖缺失）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocumentParserDispatcher {

    private final List<DocumentParser> parsers;

    /** 单文件解析大小上限（MB），与上传上限保持一致 */
    @Value("${wgh.file.max-single-file-mb:50}")
    private int maxSingleFileMb = 50;

    /**
     * 解析文件为纯文本内容列表（按段落/行切分）
     */
    public List<String> parse(File file, String fileType) {
        return parseDocument(file, fileType).paragraphs();
    }

    /**
     * 解析文件并返回正文 + 统一元数据。
     */
    public ParsedDocument parseDocument(File file, String fileType) {
        String extension = fileType.toLowerCase();
        for (DocumentParser parser : parsers) {
            if (parser.supports(extension)) {
                // 先确认格式受支持，再做文件安全检查，避免未注册格式被误报为文件问题
                validateFile(file, fileType);
                try {
                    return parser.parseDetailed(file);
                } catch (BusinessException e) {
                    throw e;
                } catch (NoClassDefFoundError e) {
                    String missing = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    log.error("解析{}缺少依赖: {}", fileType, missing);
                    throw new BusinessException("解析 " + fileType + " 需要依赖 " + missing
                            + "，请检查 rag-kb/pom.xml 中的解析依赖是否完整");
                } catch (Exception e) {
                    log.error("解析{}失败: {}", fileType, e.getMessage(), e);
                    throw new BusinessException(fileType + " 文件解析失败（文件可能已损坏或格式不正确）: "
                            + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                }
            }
        }
        throw new BusinessException("不支持的文件格式: " + fileType);
    }

    /** 文件存在性 / 空文件 / 大小上限校验。 */
    private void validateFile(File file, String fileType) {
        if (file == null || !file.exists() || !file.isFile()) {
            throw new BusinessException("文件不存在或不可读: " + (file == null ? "null" : file.getPath()));
        }
        if (!file.canRead()) {
            throw new BusinessException("文件无读取权限: " + file.getPath());
        }
        long length = file.length();
        if (length <= 0) {
            throw new BusinessException("文件为空，无法解析: " + (fileType == null ? "" : fileType));
        }
        long limit = (long) Math.max(1, maxSingleFileMb) * 1024 * 1024;
        if (length > limit) {
            throw new BusinessException(String.format("文件过大（%.1f MB），超过解析上限 %d MB",
                    length / 1024.0 / 1024.0, maxSingleFileMb));
        }
    }

    /** 供测试/调用方读取当前上限。 */
    public int getMaxSingleFileMb() {
        return maxSingleFileMb;
    }

    public void setMaxSingleFileMb(int maxSingleFileMb) {
        this.maxSingleFileMb = maxSingleFileMb;
    }
}
