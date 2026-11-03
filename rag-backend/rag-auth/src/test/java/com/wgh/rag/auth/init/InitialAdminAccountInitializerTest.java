package com.wgh.rag.auth.init;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.wgh.rag.auth.security.PasswordPolicy;
import com.wgh.rag.auth.security.PasswordProperties;
import com.wgh.rag.auth.entity.WghRole;
import com.wgh.rag.auth.entity.WghUser;
import com.wgh.rag.auth.mapper.RoleMapper;
import com.wgh.rag.auth.mapper.UserMapper;
import com.wgh.rag.auth.mapper.UserRoleMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 初始管理员初始化：无环境变量不建账号、注入口令才创建且首登需改密、已存在不覆盖。
 */
@DisplayName("初始管理员账号初始化")
class InitialAdminAccountInitializerTest {

    private UserMapper userMapper;
    private UserRoleMapper userRoleMapper;
    private RoleMapper roleMapper;
    private PasswordEncoder passwordEncoder;
    private InitialAdminAccountInitializer initializer;

    @BeforeEach
    void setUp() {
        // 单测无 Spring 上下文，需手动初始化 MyBatis-Plus 的 Lambda 列缓存
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), WghRole.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), WghUser.class);
        userMapper = mock(UserMapper.class);
        userRoleMapper = mock(UserRoleMapper.class);
        roleMapper = mock(RoleMapper.class);
        passwordEncoder = new BCryptPasswordEncoder();
        initializer = new InitialAdminAccountInitializer(userMapper, userRoleMapper, roleMapper,
                passwordEncoder, new PasswordPolicy(new PasswordProperties()));
    }

    @Test
    @DisplayName("未注入 WGH_INIT_ADMIN_PASSWORD 时不创建默认管理员")
    void noEnvPassword_doesNotCreateAdmin() throws Exception {
        ReflectionTestUtils.setField(initializer, "initAdminPassword", "");

        initializer.run(null);

        verify(userMapper, never()).insert(any(WghUser.class));
    }

    @Test
    @DisplayName("注入口令时创建 admin 账号，BCrypt 入库且 password_must_change=1")
    void injectedPassword_createsAdminWithMustChange() throws Exception {
        ReflectionTestUtils.setField(initializer, "initAdminPassword", "InitAdmin123");
        when(userMapper.selectOne(any())).thenReturn(null);
        WghRole role = new WghRole();
        role.setId(1L);
        role.setRoleCode("admin");
        when(roleMapper.selectOne(any())).thenReturn(role);
        when(userMapper.insert(any(WghUser.class))).thenAnswer(invocation -> {
            WghUser user = invocation.getArgument(0);
            user.setId(100L);
            return 1;
        });

        initializer.run(null);

        org.mockito.ArgumentCaptor<WghUser> captor =
                org.mockito.ArgumentCaptor.forClass(WghUser.class);
        verify(userMapper).insert(captor.capture());
        WghUser saved = captor.getValue();
        assertThat(saved.getUsername()).isEqualTo("admin");
        assertThat(saved.getPasswordMustChange()).isEqualTo(1);
        assertThat(saved.getSource()).isEqualTo("local");
        assertThat(saved.getPasswordHash()).startsWith("$2");
        assertThat(passwordEncoder.matches("InitAdmin123", saved.getPasswordHash())).isTrue();
        verify(userRoleMapper).insert(100L, 1L);
    }

    @Test
    @DisplayName("已存在 admin 账号时不覆盖口令")
    void existingAdmin_notOverwritten() throws Exception {
        ReflectionTestUtils.setField(initializer, "initAdminPassword", "InitAdmin123");
        WghUser existing = new WghUser();
        existing.setId(1L);
        existing.setUsername("admin");
        when(userMapper.selectOne(any())).thenReturn(existing);

        initializer.run(null);

        verify(userMapper, never()).insert(any(WghUser.class));
        verify(userMapper, never()).updateById(any(WghUser.class));
    }

    @Test
    @DisplayName("注入口令不满足强度要求时跳过创建")
    void weakPassword_skipsCreation() throws Exception {
        ReflectionTestUtils.setField(initializer, "initAdminPassword", "short");
        when(userMapper.selectOne(any())).thenReturn(null);

        initializer.run(null);

        verify(userMapper, never()).insert(any(WghUser.class));
    }
}
