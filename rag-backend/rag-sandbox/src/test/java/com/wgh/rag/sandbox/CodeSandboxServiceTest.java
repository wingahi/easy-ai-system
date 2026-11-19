package com.wgh.rag.sandbox;

import com.wgh.rag.sandbox.audit.SandboxAuditLogger;
import com.wgh.rag.sandbox.config.SandboxProperties;
import com.wgh.rag.sandbox.isolation.SandboxExecIsolation;
import com.wgh.rag.sandbox.model.SandboxException;
import com.wgh.rag.sandbox.model.SandboxRequest;
import com.wgh.rag.sandbox.model.SandboxResult;
import com.wgh.rag.sandbox.service.CodeSandboxService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 沙箱定向测试：只覆盖核心安全边界（默认关闭、硬超时、输出截断、语言白名单、清理、网络拒绝）。
 *
 * <p>不启动 Spring 上下文，直接构造服务；依赖宿主预装的 python3 / node。
 */
class CodeSandboxServiceTest {

    @TempDir
    Path tempDir;

    private SandboxProperties properties() {
        SandboxProperties properties = new SandboxProperties();
        properties.setEnabled(true);
        properties.setWorkRoot(tempDir.resolve("root").toString());
        properties.setIsolation("AUTO");
        return properties;
    }

    private CodeSandboxService service(SandboxProperties properties) {
        return new CodeSandboxService(properties, new SandboxAuditLogger(null, false));
    }

    @Test
    void disabledByDefaultRejectsExecution() {
        SandboxProperties properties = new SandboxProperties();
        assertThat(properties.isEnabled()).isFalse();

        assertThatThrownBy(() -> service(properties).execute(SandboxRequest.of("PYTHON", "print(1)")))
                .isInstanceOf(SandboxException.class)
                .hasMessageContaining("未启用");
    }

    @Test
    void pythonExecutesInIsolatedProcessAndCleansWorkDir() throws IOException {
        assumeCommand("python3");
        SandboxResult result = service(properties()).execute(
                SandboxRequest.of("PYTHON", "print('hello-sandbox')"));

        assertThat(result.exitCode()).isZero();
        assertThat(result.timedOut()).isFalse();
        assertThat(result.stdout()).contains("hello-sandbox");
        assertThat(result.isolation()).isIn("sandbox-exec", "bwrap", "degraded");
        assertWorkRootClean();
    }

    @Test
    void javascriptExecutes() {
        assumeCommand("node");
        SandboxResult result = service(properties()).execute(
                SandboxRequest.of("javascript", "console.log('js-sandbox')"));

        assertThat(result.exitCode()).isZero();
        assertThat(result.stdout()).contains("js-sandbox");
    }

    @Test
    void hardTimeoutKillsProcess() {
        assumeCommand("python3");
        long start = System.currentTimeMillis();
        SandboxResult result = service(properties()).execute(
                new SandboxRequest("PYTHON", "import time\ntime.sleep(30)\n", null, 300L));
        long elapsed = System.currentTimeMillis() - start;

        assertThat(result.timedOut()).isTrue();
        assertThat(result.killed()).isTrue();
        assertThat(elapsed).isLessThan(10_000L);
    }

    @Test
    void oversizedOutputIsTruncatedAndKilled() {
        assumeCommand("python3");
        SandboxProperties properties = properties();
        properties.setMaxOutputBytes(4_096);
        properties.setDefaultTimeoutMs(10_000);

        SandboxResult result = service(properties).execute(
                SandboxRequest.of("PYTHON", "print('x' * 500000)"));

        assertThat(result.truncated()).isTrue();
        assertThat(result.killed()).isTrue();
        assertThat(result.stdout().length()).isLessThanOrEqualTo(4_096);
    }

    @Test
    void unsupportedLanguageIsRejected() {
        assertThatThrownBy(() -> service(properties()).execute(
                SandboxRequest.of("bash", "echo hi")))
                .isInstanceOf(SandboxException.class)
                .hasMessageContaining("不支持的语言");
    }

    @Test
    void oversizedCodeIsRejected() {
        SandboxProperties properties = properties();
        properties.setMaxCodeBytes(16);
        assertThatThrownBy(() -> service(properties).execute(
                SandboxRequest.of("PYTHON", "print('this code is longer than sixteen bytes')")))
                .isInstanceOf(SandboxException.class)
                .hasMessageContaining("大小上限");
    }

    @Test
    void degradedIsolationRequiresExplicitOptIn() {
        SandboxProperties properties = properties();
        properties.setIsolation("DEGRADED");
        properties.setAllowDegradedIsolation(false);

        assertThatThrownBy(() -> service(properties).execute(SandboxRequest.of("PYTHON", "print(1)")))
                .isInstanceOf(SandboxException.class)
                .hasMessageContaining("降级隔离未显式开启");
    }

    @Test
    void degradedIsolationRunsWhenExplicitlyAllowed() {
        assumeCommand("python3");
        SandboxProperties properties = properties();
        properties.setIsolation("DEGRADED");
        properties.setAllowDegradedIsolation(true);

        SandboxResult result = service(properties).execute(SandboxRequest.of("PYTHON", "print('degraded')"));

        assertThat(result.exitCode()).isZero();
        assertThat(result.isolation()).isEqualTo("degraded");
        assertThat(result.stdout()).contains("degraded");
    }

    @Test
    void networkIsDeniedUnderMacSandbox() {
        assumeCommand("python3");
        Assumptions.assumeTrue(new SandboxExecIsolation().available(),
                "仅在具备 sandbox-exec 的 macOS 上验证网络隔离");
        String code = """
                import socket
                try:
                    socket.create_connection(("1.1.1.1", 80), timeout=2)
                    print("NETWORK_OK")
                except Exception as e:
                    print("NETWORK_BLOCKED", type(e).__name__)
                """;
        SandboxResult result = service(properties())
                .execute(new SandboxRequest("PYTHON", code, null, 5_000L));

        assertThat(result.stdout()).contains("NETWORK_BLOCKED");
    }

    private void assertWorkRootClean() throws IOException {
        Path base = tempDir.resolve("root").resolve("wgh-sandbox");
        if (!Files.isDirectory(base)) {
            return;
        }
        try (Stream<Path> children = Files.list(base)) {
            assertThat(children.count()).isZero();
        }
    }

    private void assumeCommand(String command) {
        boolean available;
        try {
            Process process = new ProcessBuilder(command, "--version")
                    .redirectErrorStream(true)
                    .start();
            available = process.waitFor() == 0;
        } catch (Exception e) {
            available = false;
        }
        Assumptions.assumeTrue(available, "宿主缺少命令: " + command);
    }
}
