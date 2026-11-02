package com.wgh.rag.kb.parser;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * 文档元数据抽取工具：标题推断、字符统计、文本编码识别。
 */
public final class DocumentMetadataSupport {

    private DocumentMetadataSupport() {
    }

    /** 标题：取首个非空段落，去掉 Markdown 标题符号与首尾空白，超过 100 字符截断。 */
    public static String inferTitle(List<String> paragraphs) {
        if (paragraphs == null) return null;
        for (String p : paragraphs) {
            if (p == null || p.isBlank()) continue;
            String title = p.trim()
                    .replaceFirst("^#+\\s*", "")
                    .replaceFirst("^[-*]\\s*", "");
            if (title.isBlank()) continue;
            return title.length() > 100 ? title.substring(0, 100) : title;
        }
        return null;
    }

    /** 字符数：所有段落正文长度求和（不含段落间分隔符）。 */
    public static int countChars(List<String> paragraphs) {
        if (paragraphs == null) return 0;
        return paragraphs.stream().filter(p -> p != null).mapToInt(String::length).sum();
    }

    /**
     * 文本编码识别：优先 BOM；其次 UTF-8 严格解码，失败回退 GBK（中文常见）。
     * 二进制格式（PDF/DOCX/XLSX 等）不应调用本方法。
     */
    public static String detectEncoding(File file) {
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            if (bytes.length >= 3 && bytes[0] == (byte) 0xEF
                    && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF) {
                return "UTF-8";
            }
            if (bytes.length >= 2 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE) {
                return "UTF-16LE";
            }
            if (bytes.length >= 2 && bytes[0] == (byte) 0xFE && bytes[1] == (byte) 0xFF) {
                return "UTF-16BE";
            }
            StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes));
            return "UTF-8";
        } catch (java.nio.charset.CharacterCodingException e) {
            return "GBK";
        } catch (Exception e) {
            return "UTF-8";
        }
    }

    /** 按识别出的编码读取文本（GBK 回退），避免中文乱码。 */
    public static String readText(File file) throws java.io.IOException {
        String encoding = detectEncoding(file);
        Charset charset = "GBK".equals(encoding) ? Charset.forName("GBK") : StandardCharsets.UTF_8;
        String text = new String(Files.readAllBytes(file.toPath()), charset);
        // 去掉 BOM
        return text.replaceFirst("^\uFEFF", "");
    }
}
