package com.wgh.rag.auth.init;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.wgh.rag.auth.security.PasswordPolicy;
import com.wgh.rag.common.entity.WghRole;
import com.wgh.rag.common.entity.WghUser;
import com.wgh.rag.common.exception.BusinessException;
import com.wgh.rag.common.mapper.RoleMapper;
import com.wgh.rag.common.mapper.UserMapper;
import com.wgh.rag.common.mapper.UserRoleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 初始管理员账号初始化。
 * <p>
 * 仓库内 SQL 不再硬编码可登录的默认口令。启动时若注入了 {@code WGH_INIT_ADMIN_PASSWORD}
 * （或 {@code wgh.auth.init-admin.password}）且库中不存在 admin 账号，则创建初始管理员，
 * 并置 {@code password_must_change=1} 要求首登改密；口令缺失时不创建默认管理员（fail-closed）。
 * 已存在 admin 账号时不做任何修改，避免覆盖线上口令。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InitialAdminAccountInitializer implements ApplicationRunner {

    public static final String ADMIN_USERNAME = "admin";

    private final UserMapper userMapper;
    private final UserRoleMapper userRoleMapper;
    private final RoleMapper roleMapper;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;

    @Value("${wgh.auth.init-admin.password:${WGH_INIT_ADMIN_PASSWORD:}}")
    private String initAdminPassword;

    @Override
    public void run(ApplicationArguments args) {
        WghUser existing = findAdmin();
        if (existing != null) {
            log.info("初始管理员已存在（username={}），不覆盖其口令", ADMIN_USERNAME);
            return;
        }
        if (!StringUtils.hasText(initAdminPassword)) {
            log.error("未注入初始管理员口令（WGH_INIT_ADMIN_PASSWORD）且库中不存在 admin，本次启动不创建默认管理员；"
                    + "如需初始化请设置该环境变量后重启");
            return;
        }

        try {
            passwordPolicy.validate(initAdminPassword);
        } catch (BusinessException e) {
            log.error("初始管理员口令不符合强度要求：{}；已跳过创建", e.getMessage());
            return;
        }

        WghRole adminRole = roleMapper.selectOne(new LambdaQueryWrapper<WghRole>()
                .select(WghRole::getId)
                .eq(WghRole::getRoleCode, ADMIN_USERNAME)
                .last("LIMIT 1"));
        if (adminRole == null) {
            log.error("未找到 admin 角色，已跳过初始管理员创建；请先执行 data.sql 的角色/权限种子");
            return;
        }

        WghUser user = new WghUser();
        user.setUsername(ADMIN_USERNAME);
        user.setPasswordHash(passwordEncoder.encode(initAdminPassword));
        user.setName("管理员");
        user.setStatus(1);
        user.setSource("local");
        user.setTrial(0);
        user.setPasswordMustChange(1);
        user.setPasswordVersion(1);

        try {
            userMapper.insert(user);
            userRoleMapper.insert(user.getId(), adminRole.getId());
            log.info("已创建初始管理员账号 username={}，口令来自启动参数，首次登录需先修改密码", ADMIN_USERNAME);
        } catch (DuplicateKeyException e) {
            log.warn("初始管理员账号已被并发创建，跳过 username={}", ADMIN_USERNAME);
        }
    }

    private WghUser findAdmin() {
        return userMapper.selectOne(new LambdaQueryWrapper<WghUser>()
                .eq(WghUser::getUsername, ADMIN_USERNAME)
                .last("LIMIT 1"));
    }
}
