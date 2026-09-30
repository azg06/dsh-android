#!/usr/bin/env node
/**
 * Android 移植补丁应用器
 *
 * 把 android-patches.json 里的规则打到目标 payload 上。
 *
 * 用法：
 *   node apply-android-fixes.mjs <payload根目录> [--dry-run]
 *
 * 退出码：
 *   0 = 全部规则已应用（或此前已应用）
 *   1 = 有规则锚点未命中 —— 说明上游结构变了，必须人工介入后再打包
 *
 * 设计原则（每一条都对应本项目踩过的坑）：
 *
 *   1. **幂等**：如果目标内容已经是 replace 形态，跳过。允许反复运行。
 *   2. **锚点唯一性断言**：find 在文件里必须恰好出现一次。出现 0 次说明结构变了；
 *      出现多次说明锚点不够独特 —— 两种情况都拒绝修改，而不是"猜一个改掉"。
 *   3. **失败即中止，绝不静默跳过**。本项目因为"改了但没生效"栽过六次
 *      （见 memory/2026-09-30.md 的清单），全部是静默失败。
 *   4. **每条规则留下可核对的痕迹**，输出里给出 apply / skip / fail 的数量。
 */
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { join, resolve } from 'node:path';

const args = process.argv.slice(2);
const dryRun = args.includes('--dry-run');
const root = resolve(args.find((a) => !a.startsWith('--')) ?? 'dsh-deploy-017');
const rulesPath = join(import.meta.dirname ?? '.', 'android-patches.json');

if (!existsSync(rulesPath)) {
  console.error(`[x] 找不到规则文件：${rulesPath}`);
  process.exit(1);
}

const { rules } = JSON.parse(readFileSync(rulesPath, 'utf8'));

console.log(`\nAndroid 补丁应用${dryRun ? '（dry-run，不写盘）' : ''}  ——  ${root}`);
console.log(`规则数：${rules.length}\n`);

// 按文件分组，避免同一文件反复读写
const byFile = new Map();
for (const r of rules) {
  if (!byFile.has(r.file)) byFile.set(r.file, []);
  byFile.get(r.file).push(r);
}

let applied = 0;
let skipped = 0;
const failures = [];

for (const [file, fileRules] of byFile) {
  const full = join(root, file);
  if (!existsSync(full)) {
    for (const r of fileRules) failures.push({ file, seq: r.seq, why: '目标文件不存在' });
    continue;
  }

  let src = readFileSync(full, 'utf8');
  let changed = false;
  const fileLog = [];

  for (const r of fileRules) {
    const find = r.find;
    const replace = r.replace;

    // ── 1. 已经应用过？（幂等检查）──────────────────────
    // 只看 replace 是否已存在即可，**不要**再要求 find 消失。
    //
    // 原因：插入型规则的 replace 里必然包含 find（"插入内容 + 原锚点行"），
    // 若额外要求 !src.includes(find)，这类规则永远判为"未应用"，会被反复插入 ——
    // 实测第二次运行时有 8 条重复应用，代码被插了两遍。
    //
    // 为什么"replace 存在"就足够可信：replace 里带着中文注释，
    // 上游原版不可能恰好含有同样的内容。
    if (replace.length > 0 && src.includes(replace)) {
      skipped++;
      fileLog.push(`  [跳过] #${r.seq} 已应用`);
      continue;
    }

    if (find.length === 0) {
      // ── 纯插入：用 after 作定位锚 ─────────────────────
      if (!r.after) {
        failures.push({ file, seq: r.seq, why: '纯插入规则缺少 after 锚点' });
        fileLog.push(`  [失败] #${r.seq} 纯插入但无 after`);
        continue;
      }
      const n = src.split(r.after).length - 1;
      if (n !== 1) {
        failures.push({ file, seq: r.seq, why: `after 锚点命中 ${n} 次（需 1 次）` });
        fileLog.push(`  [失败] #${r.seq} after 命中 ${n} 次`);
        continue;
      }
      if (dryRun) {
        applied++;
        fileLog.push(`  [将插入] #${r.seq}（${replace.split('\n').length} 行）`);
      } else {
        src = src.replace(r.after, `${r.after}\n${replace}`);
        changed = true;
        applied++;
        fileLog.push(`  [已插入] #${r.seq}（${replace.split('\n').length} 行）`);
      }
      continue;
    }

    // ── 替换：锚点必须唯一 ────────────────────────────
    const n = src.split(find).length - 1;
    if (n === 0) {
      failures.push({ file, seq: r.seq, why: '锚点未命中（上游结构已变）' });
      fileLog.push(`  [失败] #${r.seq} 锚点未命中`);
      continue;
    }
    if (n > 1) {
      failures.push({ file, seq: r.seq, why: `锚点命中 ${n} 次（不唯一，拒绝修改）` });
      fileLog.push(`  [失败] #${r.seq} 锚点命中 ${n} 次，不唯一`);
      continue;
    }

    if (dryRun) {
      applied++;
      fileLog.push(`  [将替换] #${r.seq}（${find.split('\n').length} → ${replace.split('\n').length} 行）`);
    } else {
      src = src.replace(find, replace);
      changed = true;
      applied++;
      fileLog.push(`  [已替换] #${r.seq}（${find.split('\n').length} → ${replace.split('\n').length} 行）`);
    }
  }

  console.log(file.replace('node_modules/@deepseek-ai/', ''));
  for (const l of fileLog) console.log(l);
  console.log();

  if (changed && !dryRun) {
    writeFileSync(full, src, 'utf8');
  }
}

console.log(`应用 ${applied} · 跳过 ${skipped} · 失败 ${failures.length}`);

if (failures.length > 0) {
  console.log('\n失败明细（必须人工处理后才能打包）：');
  for (const f of failures) {
    console.log(`  · ${f.file.replace('node_modules/@deepseek-ai/', '')} #${f.seq} —— ${f.why}`);
  }
  console.log();
  process.exit(1);
}

console.log('\n补丁应用完成。\n');
