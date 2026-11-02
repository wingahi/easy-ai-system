package com.wgh.rag.kb.parser;

import com.wgh.rag.common.exception.BusinessException;
import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * EML 邮件解析器（Jakarta Mail）：抽取主题、发件人、收件人、日期与正文。
 * HTML 正文用 Jsoup 转纯文本；附件暂不解析（仅在正文中忽略）。
 */
@Slf4j
@Component
public class EmlParser implements DocumentParser {

    @Override
    public boolean supports(String extension) {
        return "eml".equals(extension);
    }

    @Override
    public List<String> parse(File file) {
        return parseDetailed(file).paragraphs();
    }

    @Override
    public ParsedDocument parseDetailed(File file) {
        try (InputStream is = new FileInputStream(file)) {
            MimeMessage message = new MimeMessage(Session.getInstance(new Properties()), is);
            List<String> paragraphs = new ArrayList<>();

            String subject = message.getSubject();
            if (subject != null && !subject.isBlank()) paragraphs.add("主题: " + subject);
            String from = addressOf(message.getFrom());
            if (from != null) paragraphs.add("发件人: " + from);
            String to = addressOf(message.getRecipients(Message.RecipientType.TO));
            if (to != null) paragraphs.add("收件人: " + to);
            if (message.getSentDate() != null) {
                paragraphs.add("日期: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                        .format(message.getSentDate()));
            }

            List<String> body = new ArrayList<>();
            collectBody(message, body, 0);
            for (String b : body) {
                if (!b.isBlank()) paragraphs.add(b.trim());
            }

            return new ParsedDocument(paragraphs,
                    subject != null && !subject.isBlank() ? subject : DocumentMetadataSupport.inferTitle(paragraphs),
                    null, null, "UTF-8", DocumentMetadataSupport.countChars(paragraphs));
        } catch (Exception e) {
            log.error("解析EML失败: {}", e.getMessage(), e);
            throw new BusinessException("EML解析失败（邮件格式不正确）: " + e.getMessage());
        }
    }

    private String addressOf(jakarta.mail.Address[] addresses) {
        if (addresses == null || addresses.length == 0) return null;
        StringBuilder sb = new StringBuilder();
        for (jakarta.mail.Address a : addresses) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(a.toString());
        }
        return sb.toString();
    }

    private void collectBody(Part part, List<String> out, int depth) throws Exception {
        if (depth > 5) return;
        if (part.isMimeType("text/plain")) {
            Object content = part.getContent();
            if (content instanceof String s && !s.isBlank()) out.add(s);
        } else if (part.isMimeType("text/html")) {
            Object content = part.getContent();
            if (content instanceof String s) {
                String text = Jsoup.parse(s).body() != null ? Jsoup.parse(s).body().text() : s;
                if (!text.isBlank()) out.add(text);
            }
        } else if (part.isMimeType("multipart/*")) {
            Multipart multipart = (Multipart) part.getContent();
            for (int i = 0; i < multipart.getCount(); i++) {
                BodyPart bp = multipart.getBodyPart(i);
                // 跳过附件，只保留正文
                if (Part.ATTACHMENT.equalsIgnoreCase(bp.getDisposition())) continue;
                collectBody(bp, out, depth + 1);
            }
        }
    }
}
