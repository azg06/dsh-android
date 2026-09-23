#!/usr/bin/env node
/**
 * DSH Android 移植自检
 *
 * 背景：这个移植是靠"就地替换 node_modules 里的若干文件"实现的。过去没有补丁清单，
 * 全靠注释里的"实测"维护，结果连续踩了两次同类问题：
 *   · v0.4.9  改错了文件（改了 lib/types/commands.js，而运行时加载的是 lib/index.js bundle）
 *   · v0.4.11 改了 link→rename 却漏了配套的 unlink 清理，导致附件仍然全线失败
 * 两次都不是"想不到"，而是"没有机械化手段确认改动真的在位"。
 *
 * 所以：**打包前跑这个脚本**。任何一条不达标就退出码 1，构建应当中止。
 *
 * 用法：
 *   node verify-android-port.mjs [payload目录]     默认 dsh-deploy-015
 *   node verify-android-port.mjs --zip <payload.zip>   直接校验打包产物（推荐）
 */
import { readFileSync, existsSync, statSync, readdirSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { execFileSync } from 'node:child_process';

const args = process.argv.slice(2);
const zipIdx = args.indexOf('--zip');
const zipPath = zipIdx >= 0 ? args[zipIdx + 1] : null;
const payloadRoot = resolve(
  zipIdx >= 0 ? (args[zipIdx + 2] ?? 'dsh-deploy-015') : (args[0] ?? 'dsh-deploy-015'),
);

/**
 * 每条检查项：
 *   rel      —— 相对 payload 根的文件路径
 *   label    —— 人类可读的说明
 *   must     —— 必须存在的标记（数组，逐条计次）
 *   mustNot  —— 必须消失的标记
 *   min      —— must 中每项要求的最小出现次数（可选，默认 1）
 */
const CHECKS = [
  {
    rel: 'node_modules/@deepseek-ai/dsh-attachment-local/lib/index.js',
    label: '附件保存 · 硬链接改 rename/copyFile',
    must: [
      ['process.platform === "android"', 3, '三处 android 分支（别名 copyFile / 提交 rename / 耐久边界）'],
    ],
  },
  {
    rel: 'node_modules/@deepseek-ai/dsh-attachment-local/lib/index.js',
    label: '附件保存 · rename 之后不得裸 unlink（关键！）',
    must: [['await removeTemporary(staged.path);', 1, '用容错清理替代裸 unlink']],
    mustNot: ['await unlink(staged.path);'],
  },
  {
    rel: 'node_modules/@deepseek-ai/dsh-attachment-local/lib/index.js',
    label: '附件保存 · 目录 fsync 边界不得越界到 /data/user/0',
    must: [['dirname(dirname(home))', 1, 'android 下上溯边界收到应用包目录']],
  },
  {
    rel: 'node_modules/@deepseek-ai/dsh-attachment-local/lib/index.js',
    label: '图片处理 · WASM 兜底可用',
    must: [['sharp-wasm32', 0, '（占位，见下方单独断言）']],
  },
  {
    rel: 'node_modules/@deepseek-ai/dsh-api-session-controller/lib/index.js',
    label: '错误信息 · 兜底 catch 带出真实原因',
    must: [['"prompt rejected: "', 1, '把原始异常写入 message']],
  },
  {
    rel: 'node_modules/@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js',
    label: '会话持久化 · 硬链接改 rename',
    must: [['process.platform === "android"', 1, 'android 分支']],
    mustNot: [],
  },
  {
    rel: 'node_modules/@deepseek-ai/dsh-session-persistence-jsonl/lib/worker.cjs',
    label: '会话持久化（worker） · 硬链接改 rename',
    must: [['process.platform', 1, 'android 分支']],
  },
  {
    rel: 'node_modules/@deepseek-ai/dsh-fs-local/lib/index.js',
    label: '文件工具 · 新建走 rename 而非 link',
    // 注意：这里用的是局部变量 `platform`，不是 `process.platform` ——
    // 标记不能绑死变量名，否则检查项本身会假阳性。
    must: [['=== "android"', 1, 'android 分支'], ['lstat(absolutePath)', 1, '手工复现 EEXIST 语义']],
  },
  {
    rel: 'node_modules/@deepseek-ai/dsh-tool-fs-search/lib/index.js',
    label: 'glob/grep · ripgrep 按 android 重解析路径',
    must: [['process.platform === "android"', 1, 'android 分支']],
  },
  {
    rel: 'node_modules/@deepseek-ai/dsh-host-directory-picker-browse/lib/index.js',
    label: '工作区选择器 · 起点可被 DSH_PICKER_ROOT 覆盖',
    must: [['DSH_PICKER_ROOT', 1, '环境变量覆盖起点']],
  },
  {
    rel: 'node_modules/@deepseek-ai/node-addon-system/lib/flock.js',
    label: '文件锁 · android 按 linux 处理并放行',
    must: [["'android'", 2, '平台别名 + 放行分支']],
  },
];

// 单文件读取（支持 --zip 直读产物）
function readTarget(zip, root, rel) {
  if (zip) {
    const out = execFileSync('node', ['-e', `
      const {execFileSync}=require('child_process');
      const z=require('zlib');
      `], { encoding: 'utf8' });
    return null; // zip 模式在下方统一处理
  }
  const p = join(root, rel);
  return existsSync(p) ? readFileSync(p, 'utf8') : null;
}

// ── zip 模式：一次性解出所需条目 ─────────────────────────────
let zipEntries = null;
if (zipPath) {
  if (!existsSync(zipPath)) {
    console.error(`[x] payload.zip 不存在：${zipPath}`);
    process.exit(1);
  }
  const py = process.env.DSH_PYTHON ?? 'python';
  const script = `
import zipfile, json, sys
z = zipfile.ZipFile(sys.argv[1])
want = json.loads(sys.argv[2])
out = {}
for w in want:
    hit = [n for n in z.namelist() if n.endswith(w)]
    out[w] = z.read(hit[0]).decode('utf-8','replace') if hit else None
print(json.dumps(out))
`;
  try {
    zipEntries = JSON.parse(
      execFileSync(py, ['-c', script, zipPath, JSON.stringify(CHECKS.map((c) => c.rel))], {
        encoding: 'utf8',
        maxBuffer: 64 * 1024 * 1024,
      }),
    );
  } catch (e) {
    console.error('[x] 解 zip 失败（需要 python 可用）：', e.message);
    process.exit(1);
  }
}

// ── 逐条核对 ─────────────────────────────────────────────────
let failed = 0;
const skipped = new Set();

console.log(`\nDSH Android 移植自检  ——  ${zipPath ?? payloadRoot}\n`);

for (const c of CHECKS) {
  if (skipped.has(c.rel)) continue;

  let src = zipPath ? zipEntries[c.rel] : readTarget(null, payloadRoot, c.rel);
  if (src === null || src === undefined) {
    console.log(`  [x] ${c.label}\n      文件缺失：${c.rel}`);
    failed++;
    continue;
  }

  const problems = [];
  for (const [needle, minCount, why] of c.must) {
    if (minCount === 0) continue; // 占位项
    const n = src.split(needle).length - 1;
    if (n < minCount) problems.push(`缺少「${needle}」(${why})：期望 ≥${minCount}，实际 ${n}`);
  }
  for (const needle of c.mustNot ?? []) {
    const n = src.split(needle).length - 1;
    if (n > 0) problems.push(`不应存在「${needle}」：实际出现 ${n} 次`);
  }

  if (problems.length === 0) {
    console.log(`  [ok] ${c.label}`);
  } else {
    console.log(`  [x] ${c.label}`);
    for (const p of problems) console.log(`      · ${p}`);
    failed++;
  }
}

// ── 单独断言：WASM 兜底包必须在且原生 linux 包已清 ────────────
if (!zipPath) {
  const wasm = join(payloadRoot, 'node_modules/@img/sharp-wasm32');
  const nativeLinux = join(payloadRoot, 'node_modules/@img/sharp-libvips-linux-arm64');
  if (!existsSync(wasm)) {
    console.log('  [x] 图片处理 · @img/sharp-wasm32 缺失（Android 唯一可用后端）');
    failed++;
  } else {
    console.log('  [ok] 图片处理 · WASM 兜底包在位');
  }
  if (existsSync(nativeLinux)) {
    console.log('  [i] 图片处理 · 原生 linux 包仍在（不影响功能，但白占 35MB）');
  }
}

// ── runtime 侧检查 ───────────────────────────────────────────
// android-runtime 是 Termux bootstrap，它自带一整套 Android 集成命令
// （am / pkg / termux-open / termux-wake-lock / termux-backup …）。
// 这些命令依赖 Termux 自己的 Java 组件（TermuxService / TermuxOpenReceiver / am socket），
// 而本应用**没有移植**这些组件 —— 于是它们全部"存在、--help 正常、实际静默失败"。
//
// 留着它们的危害不是"没用"，而是**误导**：模型看到命令存在会以为可用，
// 失败又是静默的（rc=0、零输出），它会基于错误前提继续往下做。
// 一个不存在的命令，比一个假装能用的命令安全。
const runtimeDir = join(payloadRoot, '..', 'android-runtime');
const runtimeBin = join(runtimeDir, 'files/usr/bin');

const FORBIDDEN_IN_RUNTIME = [
  ['termux-reset', '含 rm -rf，路径错位下行为不可预测'],
  ['termux-fix-shebang', '会把用户脚本 shebang 改成不存在的解释器（数据破坏）'],
  ['am', '依赖 app_process + termux-am，实测 SIGABRT'],
  ['pkg', '依赖未打包的 apt/dpkg 后端，必然失败'],
  ['termux-am', '依赖 am socket，组件不存在'],
  ['termux-am-socket', '同上'],
  ['termux-backup', '指向错误前缀'],
  ['termux-restore', 'tar 解包目标路径错位，可能半损坏'],
  ['termux-open', '依赖 TermuxOpenReceiver，组件不存在'],
  ['termux-open-url', '同上'],
  ['termux-wake-lock', '依赖 TermuxService，实测 SIGABRT'],
  ['termux-wake-unlock', '同上'],
  ['termux-reload-settings', '同上'],
  ['termux-setup-storage', '同上'],
  ['termux-setup-package-manager', '依赖 pkg'],
  ['termux-change-repo', '依赖 dialog + 包管理'],
  ['termux-info', '依赖 pkg'],
];

if (!zipPath && existsSync(runtimeBin)) {
  console.log('runtime 侧检查\n');
  for (const [name, why] of FORBIDDEN_IN_RUNTIME) {
    if (existsSync(join(runtimeBin, name))) {
      console.log(`  [x] runtime 不应包含 ${name} —— ${why}`);
      failed++;
    }
  }

  // etc/ 全域（含 profile.d/）：
  //   · 活代码里不得残留 Termux 前缀
  //   · .sh 的 shebang 不得指向不存在的解释器
  //
  // 注意 profile.d/ 必须一起查：profile 的路径修好后，这些脚本会**第一次真正被执行**，
  // 原先"因为路径错所以从未加载"的文件会突然生效 —— 修复动作本身会引入新风险。
  // （实测就抓到一个 init-termux-properties.sh，路径全错且每次都会 mkdir 失败报错。）
  const etcDir = join(runtimeDir, 'files/usr/etc');
  const walk = (dir) => {
    const out = [];
    for (const e of readdirSync(dir, { withFileTypes: true })) {
      const full = join(dir, e.name);
      if (e.isDirectory()) out.push(...walk(full));
      else out.push(full);
    }
    return out;
  };
  if (existsSync(etcDir)) {
    for (const full of walk(etcDir)) {
      const rel = full.slice(runtimeDir.length + 1).replace(/\\/g, '/');
      const body = readFileSync(full, 'utf8');
      const live = body
        .split('\n')
        .filter((line) => !/^\s*#/.test(line))
        .join('\n');
      const n = live.split('/data/data/com.termux').length - 1;
      if (n > 0) {
        console.log(`  [x] ${rel} 活代码残留 ${n} 处 /data/data/com.termux`);
        failed++;
      }
      if (rel.endsWith('.sh')) {
        const first = body.split('\n')[0];
        if (first.startsWith('#!') && first.includes('com.termux')) {
          console.log(`  [x] ${rel} 的 shebang 指向不存在的解释器：${first}`);
          failed++;
        }
      }
    }
  }

  if (failed === 0) console.log('  [ok] runtime 侧检查通过');
  console.log();
}

// ── assets 投放链检查 ────────────────────────────────────────
// app/assets/AGENTS.md 是模型"知道控制桥存在"的唯一途径：dsh 读工作区根目录的
// AGENTS.md 作为 workspace instructions，而该文件由 HarnessProcess 在启动时从
// assets 投放进工作区。这条链上有两个环节曾经断过，且**都不会报错**：
//   ① aapt2 link 少了 -A，assets 被静默丢弃 → 运行时 FileNotFoundException
//   ② 描述里的目录名与 build/assets（装 zip 的中间目录）撞车，容易改错地方
if (!zipPath) {
  const projectRoot = join(payloadRoot, '..', 'DHS-Harness-Full');
  const agentMd = join(projectRoot, 'app/assets/AGENTS.md');
  const buildScript = join(projectRoot, 'build-apk.ps1');

  console.log('assets 投放链检查\n');

  if (!existsSync(agentMd)) {
    console.log('  [x] 缺少 app/assets/AGENTS.md —— 模型将看不到控制桥的用法说明');
    failed++;
  } else {
    const body = readFileSync(agentMd, 'utf8');
    // 说明里必须出现桥的关键信息，否则等于没写
    for (const [needle, why] of [
      ['DSH_ANDROID_BRIDGE_TOKEN', 'token 来源'],
      ['DSH_ANDROID_BRIDGE_URL', '桥地址'],
      ['/device/open', '打开应用端点'],
      ['/device/apps', '查包名端点'],
    ]) {
      if (!body.includes(needle)) {
        console.log(`  [x] AGENTS.md 未提及 ${needle}（${why}）`);
        failed++;
      }
    }
  }

  if (existsSync(buildScript)) {
    const script = readFileSync(buildScript, 'utf8');
    if (!/'-A'\s*,\s*\$appAssets|-A \$appAssets/.test(script)) {
      console.log("  [x] build-apk.ps1 的 aapt2 link 缺少 -A（assets 会被静默丢弃）");
      failed++;
    }
  }

  // 投放代码必须在位
  const hp = join(projectRoot, 'app/src/com/dsh/harness/HarnessProcess.java');
  if (existsSync(hp) && !readFileSync(hp, 'utf8').includes('deployAgentInstructions')) {
    console.log('  [x] HarnessProcess 未调用 deployAgentInstructions（说明不会被投放）');
    failed++;
  }

  if (failed === 0) console.log('  [ok] assets 投放链完整');
  console.log();
}

console.log();
if (failed > 0) {
  console.error(`移植自检未通过：${failed} 项不合格 —— 构建应当中止。\n`);
  process.exit(1);
}
console.log('移植自检全部通过。\n');
