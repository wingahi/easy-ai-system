package com.wgh.rag.chat.service;

import com.wgh.rag.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 聊天图片服务（多模态问答）
 * 上传：校验类型/大小 → 落盘 uploads/chat-images/ → 返回 base64 data URL 直接发给视觉模型
 */
@Slf4j
@Service
public class ChatImageService {

    /** 允许的图片类型 → 扩展名 */
    private static final Map<String, String> ALLOWED_TYPES = Map.of(
            "image/png", ".png",
            "image/jpeg", ".jpg",
            "image/gif", ".gif",
            "image/webp", ".webp");

    /** 单张图片最大 10MB（base64 膨胀约 4/3，需控制请求体） */
    private static final long MAX_SIZE_BYTES = 10L * 1024 * 1024;

    /** 单条消息最多携带图片数 */
    public static final int MAX_IMAGES_PER_MESSAGE = 5;

    @Value("${wgh.file.upload-dir:/tmp/wgh-rag/uploads}")
    private String uploadDir;

    /**
     * 存储上传图片，返回可直接发给模型的 data URL
     */
    public String store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("图片文件不能为空");
        }
        String contentType = file.getContentType();
        String ext = ALLOWED_TYPES.get(contentType);
        if (ext == null) {
            throw new BusinessException("仅支持 PNG / JPG / GIF / WebP 图片");
        }
        if (file.getSize() > MAX_SIZE_BYTES) {
            throw new BusinessException("图片大小不能超过 10MB");
        }
        try {
            byte[] bytes = file.getBytes();
            Path dir = Paths.get(uploadDir, "chat-images");
            Files.createDirectories(dir);
            Path target = dir.resolve(UUID.randomUUID() + ext);
            Files.write(target, bytes);
            log.info("聊天图片已落盘: {} ({} bytes)", target, bytes.length);
            return "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(bytes);
        } catch (IOException e) {
            log.error("聊天图片存储失败", e);
            throw new BusinessException("图片存储失败: " + e.getMessage());
        }
    }

    /**
     * 校验前端传入的 imageUrls：非空时最多 5 张，每张须为 data:image/ 或 http(s) URL
     */
    public void validateImageUrls(List<String> imageUrls) {
        if (imageUrls == null || imageUrls.isEmpty()) {
            return;
        }
        if (imageUrls.size() > MAX_IMAGES_PER_MESSAGE) {
            throw new BusinessException("单条消息最多携带 " + MAX_IMAGES_PER_MESSAGE + " 张图片");
        }
        Set<String> seen = new java.util.HashSet<>();
        for (String url : imageUrls) {
            if (url == null || url.isBlank()) {
                throw new BusinessException("图片 URL 不能为空");
            }
            boolean valid = url.startsWith("data:image/")
                    || url.startsWith("http://") || url.startsWith("https://");
            if (!valid) {
                throw new BusinessException("图片仅支持 data:image/ base64 或 http(s) URL");
            }
            if (!seen.add(url)) {
                throw new BusinessException("存在重复图片");
            }
        }
    }
}
