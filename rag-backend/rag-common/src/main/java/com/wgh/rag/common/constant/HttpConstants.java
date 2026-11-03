package com.wgh.rag.common.constant;

/**
 * HTTP / 协议层通用常量（header 名、Bearer 前缀、常用 Content-Type）。
 * 仅存放跨模块复用且与具体业务无关的协议常量。
 */
public interface HttpConstants {

    String HEADER_AUTHORIZATION = "Authorization";
    String HEADER_X_REQUEST_ID = "X-Request-Id";
    String HEADER_X_FORWARDED_FOR = "X-Forwarded-For";
    String HEADER_X_API_KEY = "X-API-Key";
    String HEADER_CONTENT_TYPE = "Content-Type";

    /** Bearer 鉴权前缀（含尾随空格） */
    String BEARER_PREFIX = "Bearer ";

    String CONTENT_TYPE_JSON = "application/json";
    String CONTENT_TYPE_JSON_UTF8 = "application/json;charset=UTF-8";
}
