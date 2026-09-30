#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
补丁规则生成器（Python 版）

输入：上游原版包（从 npm pack 解出）+ 已打 Android 适配的 payload
输出：android-patches.json —— 机器可执行的补丁规则

为什么用 difflib 而不是 git diff：
  · git diff --no-index 在"有差异"时退出码为 1，且在本沙箱里 spawn 会 EBUSY；
  · difflib 能直接给出 opcode，**包括纯插入**（find 为空、只有 replace 内容），
    而 git diff 对这种情形需要额外解析 hunk 头。
  · 规则里的 find/replace 都是磁盘真实字节，不用手工转录 —— 手工抄 13 处必出错，
    而"补丁应用成功但内容不精确"的后果是静默的。

用法：python gen-patch-rules.py
"""
import difflib
import json
import pathlib
import sys

ORIG_ROOT = pathlib.Path(r"E:\工作目录\DSH android\_orig-015")
MINE_ROOT = pathlib.Path(r"E:\工作目录\DSH android\dsh-deploy-015\node_modules\@deepseek-ai")
OUT = pathlib.Path(r"E:\工作目录\DSH android\android-patches.json")

TARGETS = [
    ("deepseek-ai-dsh-attachment-local-0.1.5-rc.2",           "dsh-attachment-local",           ["lib/index.js"]),
    ("deepseek-ai-dsh-api-session-controller-0.1.5-rc.2",     "dsh-api-session-controller",     ["lib/index.js"]),
    ("deepseek-ai-dsh-session-persistence-jsonl-0.1.5-rc.2",  "dsh-session-persistence-jsonl",  ["lib/index.js", "lib/worker.cjs"]),
    ("deepseek-ai-dsh-fs-local-0.1.5-rc.2",                   "dsh-fs-local",                   ["lib/index.js"]),
    ("deepseek-ai-dsh-tool-fs-search-0.1.5-rc.2",             "dsh-tool-fs-search",             ["lib/index.js"]),
    ("deepseek-ai-dsh-host-directory-picker-browse-0.1.5-rc.2", "dsh-host-directory-picker-browse", ["lib/index.js"]),
    ("deepseek-ai-node-addon-system-0.1.2",                   "node-addon-system",              ["lib/flock.js"]),
]

rules = []
for orig_dir, pkg, files in TARGETS:
    for rel in files:
        a = ORIG_ROOT / orig_dir / "package" / rel
        b = MINE_ROOT / pkg / rel
        relkey = "node_modules/@deepseek-ai/%s/%s" % (pkg, rel)
        if not a.exists():
            print("  [跳过] 原版缺失 %s" % relkey)
            continue
        if not b.exists():
            print("  [跳过] 改后缺失 %s" % relkey)
            continue

        al = a.read_text(encoding="utf-8").splitlines()
        bl = b.read_text(encoding="utf-8").splitlines()
        sm = difflib.SequenceMatcher(None, al, bl, autojunk=False)

        count = 0
        for tag, i1, i2, j1, j2 in sm.get_opcodes():
            if tag == "equal":
                continue
            count += 1
            find_lines = al[i1:i2]
            repl_lines = bl[j1:j2]

            # 纯插入（find 为空）转成替换：
            #   用**紧接着的下一行**（未被改动的那行）作为 find，
            #   替换成「要插入的内容 + 那一行」。
            #
            # 为什么不用"前一行"作锚：前一行往往是 `if (...) try {` 这类
            # 在文件里重复几百次的语句，锚点毫无独特性 —— 实测在
            # session-persistence 里命中 495 次、worker.cjs 里 1407 次。
            # 下一行同理可能重复，但它更常是具体的调用语句；配合应用器的
            # "必须恰好命中一次"断言，不合格就报错而不是乱改。
            if not find_lines and i2 < len(al):
                find_lines = [al[i2]]
                repl_lines = list(repl_lines) + [al[i2]]

            rules.append({
                "file": relkey,
                "seq": count,
                "tag": tag,
                "find": "\n".join(find_lines),
                "replace": "\n".join(repl_lines),
            })
        print("  %-58s %d 处" % (relkey, count))

OUT.write_text(json.dumps({
    "note": "由 gen-patch-rules.py 自动生成。find/replace 为磁盘真实字节，请勿手工编辑。",
    "base": "0.1.5-rc.2 原版 -> 已打 Android 适配的 payload",
    "count": len(rules),
    "rules": rules,
}, ensure_ascii=False, indent=2), encoding="utf-8")

print("\n共 %d 条规则 -> %s" % (len(rules), OUT))
