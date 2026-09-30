#!/usr/bin/env node
/**
 * 补丁锚点探测
 *
 * 目的：在**新版 payload** 上逐个验证「旧补丁的定位锚点」是否仍然存在，
 * 从而判断哪些补丁可以机械套用、哪些必须人工重新定位。
 *
 * 背景：这个 payload 的 Android 适配是靠"就地修改 node_modules 里的文件"实现的。
 * 升级版本时上游代码会变，锚点可能失效 —— 而失效的后果是**静默的**：
 * 补丁"应用成功"但打在错误的语义位置上，或者干脆没匹配到被跳过。
 * 所以在动手改之前先把锚点全部探一遍。
 *
 * 用法：node probe-anchors.mjs <payload根目录>
 */
import { readFileSync, existsSync } from 'node:fs';
import { join, resolve } from 'node:path';

const root = resolve(process.argv[2] ?? 'dsh-deploy-017');

/**
 * 每个补丁记录：
 *   file    —— 相对 payload 根
 *   id      —— 补丁标识
 *   anchor  —— 要在新版里找到的**原始文本**（0.1.5 原版里的样子）
 *   expect  —— 期望出现次数
 *   note    —— 人类可读的说明
 */
const PATCHES = [
  // ── dsh-attachment-local ──────────────────────────────
  {
    file: 'node_modules/@deepseek-ai/dsh-attachment-local/lib/index.js',
    id: 'fsync-boundary',
    anchor: 'await ensureDurableDirectory(home, parse(home).root);',
    expect: 1,
    note: '目录 fsync 上溯边界收到应用包目录（否则 EACCES 到 /data/user/0）',
  },
  {
    file: 'node_modules/@deepseek-ai/dsh-attachment-local/lib/index.js',
    id: 'alias-copyfile',
    anchor: 'await link(source, target);',
    expect: 1,
    note: '不可变对象挂别名：android 用 copyFile（rename 会移走源）',
  },
  {
    file: 'node_modules/@deepseek-ai/dsh-attachment-local/lib/index.js',
    id: 'commit-rename',
    anchor: 'await link(staged.path, target);',
    expect: 1,
    note: '提交暂存对象：android 用 rename',
  },
  {
    file: 'node_modules/@deepseek-ai/dsh-attachment-local/lib/index.js',
    id: 'cleanup-removeTemporary',
    anchor: 'await unlink(staged.path);',
    expect: 1,
    note: '修正 rename 之后裸 unlink 必 ENOENT → 改用 removeTemporary',
  },
  {
    file: 'node_modules/@deepseek-ai/dsh-attachment-local/lib/index.js',
    id: 'import-copyfile',
    anchor: 'import { chmod, link, mkdir, open, readFile, rename, rm, unlink, writeFile } from "node:fs/promises";',
    expect: 1,
    note: '补 copyFile 到 import（copyFile 方案需要它）',
  },

  // ── dsh-api-session-controller ────────────────────────
  {
    file: 'node_modules/@deepseek-ai/dsh-api-session-controller/lib/index.js',
    id: 'error-message',
    anchor: 'throw new RemoteError("session/agent-busy", "prompt rejected", { reason: String(error) });',
    expect: 1,
    note: '兜底 catch 带出真实原因（否则永远只看到"会话忙"）',
  },

  // ── dsh-session-persistence-jsonl ─────────────────────
  {
    file: 'node_modules/@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js',
    id: 'hardlink-1',
    // 注意是 internals.fs.link —— 不是裸 link()。原来写成裸调用导致探测假阴性。
    anchor: 'await internals.fs.link(staged, currentPath);',
    expect: 1,
    note: '会话日志发布：android 改 rename',
  },
  {
    file: 'node_modules/@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js',
    id: 'hardlink-2',
    anchor: 'await link(tmp, finalPath);',
    expect: 1,
    note: '会话日志发布（第二处）：android 改 rename',
  },
  {
    file: 'node_modules/@deepseek-ai/dsh-session-persistence-jsonl/lib/worker.cjs',
    id: 'hardlink-worker',
    anchor: 'await link(',
    expect: null, // 先只统计，人工确认
    note: 'worker 侧硬链接调用',
  },

  // ── dsh-fs-local ──────────────────────────────────────
  {
    file: 'node_modules/@deepseek-ai/dsh-fs-local/lib/index.js',
    id: 'create-if-absent',
    // 真实锚点是 linkFile 辅助函数调用，不是 link()
    anchor: 'await linkFile(tempPath, absolutePath);',
    expect: 1,
    note: '新建文件：android 下手工复现 EEXIST 语义后改用 rename',
  },

  // ── dsh-tool-fs-search ────────────────────────────────
  {
    file: 'node_modules/@deepseek-ai/dsh-tool-fs-search/lib/index.js',
    id: 'ripgrep-path',
    // 0.1.7 上游改成先赋给 const dependency 再返回（为处理 .asar），锚点随之更新
    anchor: 'const dependency = (await import("@vscode/ripgrep")).rgPath;',
    expect: 1,
    note: 'android 下按 linux-<arch> 解析 ripgrep 二进制',
  },

  // ── dsh-host-directory-picker-browse ──────────────────
  {
    file: 'node_modules/@deepseek-ai/dsh-host-directory-picker-browse/lib/index.js',
    id: 'picker-root',
    anchor: 'homedir()',
    expect: 1,
    note: '选择器起点允许被 DSH_PICKER_ROOT 覆盖',
  },

  // ── node-addon-system ─────────────────────────────────
  {
    file: 'node_modules/@deepseek-ai/node-addon-system/lib/flock.js',
    id: 'flock-platform',
    // 用整行判断而不是 "'linux'"（后者会同时命中 !== 和 === 两处）
    anchor: "if (platform !== 'linux' && platform !== 'darwin') {",
    expect: 1,
    note: 'android 按 linux 处理并放行 flock',
  },
];

console.log(`\n补丁锚点探测  ——  ${root}\n`);

let canApply = 0;
const needReview = [];

for (const p of PATCHES) {
  const full = join(root, p.file);
  const short = p.file.replace('node_modules/@deepseek-ai/', '');

  if (!existsSync(full)) {
    console.log(`  [文件缺失] ${short}  ::  ${p.id}`);
    needReview.push({ ...p, reason: '文件不存在' });
    continue;
  }

  const src = readFileSync(full, 'utf8');
  const count = src.split(p.anchor).length - 1;

  if (p.expect === null) {
    console.log(`  [统计] ${short}  ::  ${p.id}   (命中 ${count})`);
    console.log(`           ${p.note}`);
    continue;
  }

  if (count === p.expect) {
    console.log(`  [可套用] ${short}  ::  ${p.id}   (锚点命中 ${count})`);
    console.log(`           ${p.note}`);
    canApply++;
  } else {
    console.log(`  [需人工] ${short}  ::  ${p.id}   (期望 ${p.expect}，实际 ${count})`);
    console.log(`           ${p.note}`);
    console.log(`           锚点: ${p.anchor}`);
    needReview.push({ ...p, actual: count });
  }
}

console.log();
console.log(`可机械套用: ${canApply} / ${PATCHES.length}`);
if (needReview.length > 0) {
  console.log(`需人工重定位: ${needReview.length}`);
  for (const r of needReview) {
    console.log(`  · ${r.file.split('/').pop()} :: ${r.id}  (${r.reason ?? `命中 ${r.actual}`})`);
  }
}
console.log();
