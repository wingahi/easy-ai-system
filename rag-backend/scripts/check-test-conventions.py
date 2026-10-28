#!/usr/bin/env python3
"""测试代码规范静态检查（供 CI 与本地使用）。

规则：
1. 命名：@Test / @ParameterizedTest / @RepeatedTest / @TestFactory 方法的名称必须是纯 ASCII 标识符，禁止中文或其它非 ASCII 命名。
2. 注释：每个测试方法上方必须紧邻中文注释（Javadoc 或行注释，且至少包含一个中文字符）。

用法：
    python3 scripts/check-test-conventions.py [--root <仓库根目录>]
    默认 root 为脚本所在目录的上一级（rag-backend）。

退出码：0 通过；1 存在违规。
"""

import argparse
import os
import re
import sys

TEST_ANNOTATION = re.compile(r"^\s*@(Test|ParameterizedTest|RepeatedTest|TestFactory)\b")
CJK = re.compile(r"[\u4e00-\u9fff]")
# 取 '(' 之前的最后一个标识符作为方法名
IDENT_BEFORE_PAREN = re.compile(r"([A-Za-z0-9_$\u0080-\uffff]+)\s*\(")


def is_test_source(path):
    """判断是否为测试源码（.../src/test/java/...）。"""
    norm = path.replace(os.sep, "/")
    return "/src/test/java/" in norm and "/target/" not in norm and norm.endswith(".java")


def find_method_line(lines, annotation_index):
    """从注解行向下找到方法声明行（跳过其它注解、空行、行注释）。"""
    for j in range(annotation_index + 1, len(lines)):
        stripped = lines[j].strip()
        if not stripped or stripped.startswith("@") or stripped.startswith("//"):
            continue
        return j
    return -1


def method_name(line):
    """提取方法名；无法解析时返回 None。"""
    match = IDENT_BEFORE_PAREN.search(line)
    return match.group(1) if match else None


def has_chinese_comment(lines, annotation_index):
    """判断注解上方是否紧邻中文注释（允许中间隔着其它注解）。"""
    j = annotation_index - 1
    while j >= 0:
        stripped = lines[j].strip()
        if not stripped or stripped.startswith("@"):
            j -= 1
            continue
        if stripped.startswith("//"):
            return bool(CJK.search(stripped))
        if stripped.endswith("*/"):
            block = [stripped]
            k = j - 1
            while k >= 0 and "/*" not in lines[k]:
                block.append(lines[k])
                k -= 1
            if k >= 0:
                block.append(lines[k])
            return bool(CJK.search("\n".join(block)))
        return False
    return False


def check_file(path):
    """返回该文件的违规列表 [(行号, 规则, 说明)]。"""
    with open(path, encoding="utf-8") as f:
        lines = f.readlines()

    violations = []
    checked_method_lines = set()

    for i, line in enumerate(lines):
        if not TEST_ANNOTATION.match(line):
            continue
        method_line_index = find_method_line(lines, i)
        if method_line_index < 0 or method_line_index in checked_method_lines:
            continue
        checked_method_lines.add(method_line_index)

        name = method_name(lines[method_line_index])
        if name is None:
            continue

        if not name.isascii():
            violations.append((
                method_line_index + 1,
                "命名",
                f"测试方法名含非 ASCII 字符：{name}",
            ))

        if not has_chinese_comment(lines, i):
            violations.append((
                i + 1,
                "注释",
                f"测试方法缺少紧邻中文注释：{name}",
            ))

    return len(checked_method_lines), violations


def main():
    parser = argparse.ArgumentParser(description="测试代码规范静态检查")
    parser.add_argument("--root", default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                        help="扫描根目录，默认为 rag-backend")
    args = parser.parse_args()

    root = os.path.abspath(args.root)
    if not os.path.isdir(root):
        print(f"[ERROR] 扫描根目录不存在：{root}")
        return 2

    scanned_files = 0
    total_methods = 0
    all_violations = []

    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d != "target"]
        for filename in filenames:
            path = os.path.join(dirpath, filename)
            if not is_test_source(path):
                continue
            scanned_files += 1
            method_count, violations = check_file(path)
            total_methods += method_count
            rel = os.path.relpath(path, root)
            for line_no, rule, message in violations:
                all_violations.append(f"[{rule}] {rel}:{line_no} {message}")

    print(f"扫描测试文件：{scanned_files}")
    print(f"测试方法数：{total_methods}")

    if all_violations:
        print(f"违规：{len(all_violations)}")
        for v in all_violations:
            print("  " + v)
        print("\n请修正后再提交：测试方法必须使用英文命名，并在方法上方添加中文注释。")
        return 1

    print("违规：0")
    return 0


if __name__ == "__main__":
    sys.exit(main())
