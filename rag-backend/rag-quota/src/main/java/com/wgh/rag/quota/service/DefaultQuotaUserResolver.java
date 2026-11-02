package com.wgh.rag.quota.service;

import com.wgh.rag.common.security.LoginUser;
import com.wgh.rag.common.security.SecurityUtils;
import org.springframework.stereotype.Component;

/**
 * 默认配额用户解析：优先取 LoginUser ThreadLocal（JWT 过滤器填充），
 * 其次退化到 Spring SecurityContext，最终兜底单用户 "anonymous"。
 * <p>
 * 限制：兜底实现下所有未登录请求共享同一份 anonymous 配额；
 * 分享链路、异步线程（无 SecurityContext）也会落到 anonymous。
 */
@Component
public class DefaultQuotaUserResolver implements QuotaUserResolver {

    @Override
    public String currentUsername() {
        LoginUser loginUser = LoginUser.get();
        if (loginUser != null && loginUser.getUsername() != null && !loginUser.getUsername().isBlank()) {
            return loginUser.getUsername();
        }
        return SecurityUtils.currentUsername();
    }
}
