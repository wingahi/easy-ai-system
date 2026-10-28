package com.wgh.rag.kb.parser;

import com.wgh.rag.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * CSV 解析器：每行一个条目
 */
@Slf4j
@Component
public class CsvParser implements DocumentParser {

    @Override
    public boolean supports(String extension) {
        return "csv".equals(extension);
    }

    @Override
    public List<String> parse(File file) {
        List<String> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) {
                    rows.add(line.trim());
                }
            }
        } catch (IOException e) {
            log.error("解析CSV失败: {}", e.getMessage(), e);
            throw new BusinessException("CSV解析失败: " + e.getMessage());
        }
        return rows;
    }
}
