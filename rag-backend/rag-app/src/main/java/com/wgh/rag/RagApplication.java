package com.wgh.rag;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * WGH RAG System 启动类
 */
@SpringBootApplication(scanBasePackages = "com.wgh.rag")
@MapperScan("com.wgh.rag.**.mapper")
@EnableAsync
public class RagApplication {

    public static void main(String[] args) {
        SpringApplication.run(RagApplication.class, args);
    }
}
