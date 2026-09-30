#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
手工修正 3 条自动生成失败的规则。

为什么这 3 条无法自动生成：
  1. 两条插入规则的定位锚是 `\ttry {`，在文件里重复 74~75 次 ——
     自动扩展上下文也难保证在**目标版本**上唯一，索性用"下一行"（那句
     `await internals.fs.link(staged, currentPath);`）与之组合，天然唯一。
  2. tool-fs-search 那处上游把 `return (await import(...)).rgPath;`
     改写成了 `const dependency = ...;`（为处理 .asar 打包路径），
     所以锚点必须用**目标版本的写法**，而生成器只知道 0.1.5 的原文。

本脚本把这些规则替换成针对 0.1.7 手工校准的版本，然后写回 android-patches.json。
"""
import json
import pathlib

P = pathlib.Path(r"E:\工作目录\DSH android\android-patches.json")
data = json.loads(P.read_text(encoding="utf-8"))

ANDROID_LINK_GUARD = """	// Android 10+ 禁止普通应用创建硬链接，link() 会直接 EACCES。
	// 先用 lstat 保留"目标已存在则返回 false"的语义，再用 rename 原子发布。
	if (internals.platform === "android") {
		try {
			await internals.fs.lstat(currentPath);
			return false;
		} catch {
			// 目标不存在，可以发布
		}
		await internals.fs.rename(staged, currentPath);
		await syncDirectory(dirname(currentPath), internals);
		return true;
	}"""

# 两条插入：用「\ttry { + 紧邻的 link 调用」做复合锚点。
# 这个组合在文件里唯一 —— 而单看 `\ttry {` 会命中 75 次。
LINK_CALL = "\t\tawait internals.fs.link(staged, currentPath);"
OLD_ANCHOR = "\ttry {\n" + LINK_CALL
NEW_ANCHOR = ANDROID_LINK_GUARD + "\n" + OLD_ANCHOR

MANUAL = {
    ("dsh-session-persistence-jsonl/lib/index.js", 2): {
        "find": OLD_ANCHOR,
        "replace": NEW_ANCHOR,
    },
    ("dsh-session-persistence-jsonl/lib/worker.cjs", 1): {
        "find": OLD_ANCHOR,
        "replace": NEW_ANCHOR,
    },
    # 上游改过这一行：0.1.7 里先赋给 const dependency（为处理 .asar 路径）。
    # 所以锚点用新版写法，替换内容保留这个写法并前置 android 分支。
    ("dsh-tool-fs-search/lib/index.js", 1): {
        "find": '\t\tconst dependency = (await import("@vscode/ripgrep")).rgPath;',
        "replace": (
            '\t\t// Android 的 Node 是 Linux ABI，但 process.platform 是 \'android\'，\n'
            '\t\t// 而 @vscode/ripgrep 按平台拼包名（optionalDependencies 里没有 android 变体），\n'
            '\t\t// 会抛 "Could not find @vscode/ripgrep-android-arm64"，令 glob/grep 完全不可用。\n'
            '\t\t// 这里直接解析随包分发的 linux-<arch> 二进制，绕开它的平台拼名。\n'
            '\t\tif (process.platform === "android") {\n'
            '\t\t\tconst { createRequire } = await import("node:module");\n'
            '\t\t\tconst manifest = createRequire(import.meta.url)\n'
            '\t\t\t\t.resolve(`@vscode/ripgrep-linux-${process.arch}/package.json`);\n'
            '\t\t\tconst rg = join(parse(manifest).dir, "bin", "rg");\n'
            '\t\t\t// 兜底补可执行位：zip 在 Windows 上打包不带 Unix mode，解压后常是 600，\n'
            '\t\t\t// spawn 会直接 EACCES（表现为 "ripgrep provider failure"）。\n'
            '\t\t\t// App 侧解压器已经会补，这里再保一道 —— 换打包机或重装时不会复发。\n'
            '\t\t\ttry {\n'
            '\t\t\t\tconst { chmod } = await import("node:fs/promises");\n'
            '\t\t\t\tawait chmod(rg, 0o755);\n'
            '\t\t\t} catch {\n'
            '\t\t\t\t// 已可执行、或文件系统不允许改：交给 spawn 自己报错，别在这里吞掉真正的失败\n'
            '\t\t\t}\n'
            '\t\t\treturn rg;\n'
            '\t\t}\n'
            '\t\tconst dependency = (await import("@vscode/ripgrep")).rgPath;'
        ),
    },
}

fixed = 0
for r in data["rules"]:
    key = (r["file"].replace("node_modules/@deepseek-ai/", ""), r["seq"])
    if key in MANUAL:
        r["find"] = MANUAL[key]["find"]
        r["replace"] = MANUAL[key]["replace"]
        r["manual"] = True
        fixed += 1
        print("  已修正 %s #%d" % key)

data["manualFixes"] = fixed
P.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
print("\n手工修正 %d 条 -> %s" % (fixed, P))
