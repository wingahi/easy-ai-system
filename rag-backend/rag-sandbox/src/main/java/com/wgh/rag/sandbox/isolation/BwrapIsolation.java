package com.wgh.rag.sandbox.isolation;

import com.wgh.rag.sandbox.model.SandboxException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Linux 轻量容器隔离：通过 bubblewrap 创建用户命名空间
 *
 * <ul>
 *   <li>{@code --unshare-all}：独立的 user/pid/ipc/uts/cgroup/net 命名空间（网络默认隔离）</li>
 *   <li>系统目录只读挂载，工作目录绑定到容器内 {@code /work}</li>
 *   <li>{@code --die-with-parent}：宿主进程退出后沙箱进程一并结束</li>
 * </ul>
 *
 * <p>依赖宿主已安装 bwrap，且允许非特权用户命名空间；不可用时由上层决定拒绝或降级。
 */
public class BwrapIsolation implements SandboxIsolation {

    private static final String WORK_IN_CONTAINER = "/work";

    @Override
    public String name() {
        return "bwrap";
    }

    @Override
    public boolean available() {
        return locateBwrap() != null;
    }

    @Override
    public List<String> wrap(List<String> command, Path workDir, boolean allowNetwork) throws IOException {
        String bwrap = locateBwrap();
        if (bwrap == null) {
            throw new SandboxException("bwrap 不可用");
        }
        Path realWorkDir = workDir.toRealPath();
        List<String> args = new ArrayList<>();
        args.add(bwrap);
        args.add("--die-with-parent");
        args.add("--new-session");
        args.add("--unshare-all");
        if (allowNetwork) {
            // --unshare-all 已包含 net，显式追加 --share-net 才能恢复网络
            args.add("--share-net");
        }
        args.add("--proc");
        args.add("/proc");
        args.add("--dev");
        args.add("/dev");
        args.add("--tmpfs");
        args.add("/tmp");
        for (String dir : List.of("/usr", "/bin", "/sbin", "/lib", "/lib64", "/lib32",
                "/etc", "/opt", "/run", "/var/lib/sss")) {
            args.add("--ro-bind-try");
            args.add(dir);
            args.add(dir);
        }
        args.add("--bind");
        args.add(realWorkDir.toString());
        args.add(WORK_IN_CONTAINER);
        args.add("--chdir");
        args.add(WORK_IN_CONTAINER);
        args.add("--setenv");
        args.add("HOME");
        args.add(WORK_IN_CONTAINER);
        args.add("--setenv");
        args.add("TMPDIR");
        args.add(WORK_IN_CONTAINER);
        args.add("--");
        args.addAll(translatePaths(command, realWorkDir));
        return args;
    }

    /** 容器内路径与宿主不同，需要把工作目录下的参数映射到 /work */
    private List<String> translatePaths(List<String> command, Path workDir) {
        String prefix = workDir.toString();
        List<String> translated = new ArrayList<>(command.size());
        for (String arg : command) {
            if (arg.equals(prefix)) {
                translated.add(WORK_IN_CONTAINER);
            } else if (arg.startsWith(prefix + java.io.File.separator)) {
                translated.add(WORK_IN_CONTAINER + arg.substring(prefix.length()));
            } else {
                translated.add(arg);
            }
        }
        return translated;
    }

    private String locateBwrap() {
        for (String candidate : List.of("/usr/bin/bwrap", "/bin/bwrap", "/usr/local/bin/bwrap")) {
            if (Files.isExecutable(Path.of(candidate))) {
                return candidate;
            }
        }
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return null;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            Path candidate = Path.of(dir, "bwrap");
            if (Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        return null;
    }
}
