package com.wgh.rag.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wgh.rag.common.entity.WghUser;
import com.wgh.rag.common.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 存量明文密码迁移：应用启动时把 wgh_user.password_hash 中非 BCrypt 密文（无 $2a/$2b/$2y 前缀）
 * 的值按当前明文加密回写，保证老库可直接使用 BCrypt 校验。
 * <p>
 * 幂等：已是 BCrypt 密文的记录会被跳过；空密码（如 OIDC 自动建号用户）不处理。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PasswordHashMigration implements ApplicationRunner {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) {
        List<WghUser> users = userMapper.selectList(new LambdaQueryWrapper<>());
        int migrated = 0;
        for (WghUser user : users) {
            String passwordHash = user.getPasswordHash();
            if (passwordHash == null || passwordHash.isBlank()
                    || passwordHash.startsWith("$2a$")
                    || passwordHash.startsWith("$2b$")
                    || passwordHash.startsWith("$2y$")) {
                continue;
            }
            userMapper.updatePasswordHash(user.getId(), passwordEncoder.encode(passwordHash));
            migrated++;
        }
        if (migrated > 0) {
            log.warn("存量明文密码迁移完成：{} 条 wgh_user.password_hash 已加密为 BCrypt", migrated);
        }
    }
}
