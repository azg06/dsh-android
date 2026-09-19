/**
 * 交互探针：把外壳层的关键交互路径跑一遍并断言。
 *
 * 覆盖用户实际踩到的坑：
 *   · 抽屉"开了就再也打不开"
 *   · 设置面板进了出不来
 *
 * 用法：node probe-interact.mjs "<带 token 的 URL>"
 */
import { createRequire } from 'node:module'
import { existsSync } from 'node:fs'

const PW = [
  'E:/DSH/deepseek-harness/node_modules/.pnpm/playwright-core@1.61.1/node_modules/playwright-core',
  'E:/工作目录/DSH android/deepseek-harness-mobile/node_modules/.pnpm/playwright-core@1.61.1/node_modules/playwright-core',
]
const pwDir = PW.find((p) => existsSync(p + '/package.json'))
if (!pwDir) throw new Error('找不到 playwright-core')
const require = createRequire(pwDir + '/package.json')
const { chromium } = require(pwDir)

const url = process.argv[2]
if (!url) throw new Error('用法: node probe-interact.mjs "<URL>"')

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage({
  viewport: { width: 390, height: 844 },
  deviceScaleFactor: 2, isMobile: true, hasTouch: true,
})

const errors = []
page.on('pageerror', (e) => errors.push(String(e).slice(0, 200)))

await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 60000 })
await page.waitForTimeout(10000)

const DISMISS = ['继续', '稍后配置', '稍后', '跳过', '知道了', '完成']
for (let i = 0; i < 12; i++) {
  let clicked = false
  for (const w of DISMISS) {
    const b = page.locator('button', { hasText: new RegExp('^\\s*' + w + '\\s*$') }).first()
    if (await b.count() === 0 || !await b.isVisible().catch(() => false)) continue
    await b.click({ timeout: 2500 }).catch(() => {})
    clicked = true
    await page.waitForTimeout(1500)
    break
  }
  if (!clicked) break
}
await page.waitForTimeout(2500)

const info = () => page.evaluate(() => window.__dshMobileShellInfo?.() ?? null)
const results = []
const check = (n, ok, d = '') => results.push([ok, n, d])

const s0 = await info()
check('外壳层已加载', s0 !== null && s0.resolved === true, JSON.stringify(s0))
check('初始抽屉关闭', s0?.sidebar === 'closed', String(s0?.sidebar))

// --- 抽屉：开 → 关 → 再开（"开了就再也打不开"就出在这一步）---
await page.click('#dsh-m-topbar [data-act="sidebar"]').catch(() => {})
await page.waitForTimeout(1400)
let s = await info()
check('① 点菜单 → 抽屉打开', s?.sidebar === 'open', String(s?.sidebar))

await page.mouse.click(372, 500)
await page.waitForTimeout(1400)
s = await info()
check('② 点遮罩 → 抽屉关闭', s?.sidebar === 'closed', String(s?.sidebar))

await page.click('#dsh-m-topbar [data-act="sidebar"]').catch(() => {})
await page.waitForTimeout(1400)
s = await info()
check('③ 再次点菜单 → 仍能打开', s?.sidebar === 'open', String(s?.sidebar))

await page.mouse.click(372, 500)
await page.waitForTimeout(1200)

// --- 设置面板：打开 → 关闭 ---
await page.evaluate(() => {
  const btns = Array.from(document.querySelectorAll('button'))
  const t = btns.find((b) => /设置|settings/i.test(b.getAttribute('aria-label') || b.textContent || ''))
  if (t) t.click()
})
await page.waitForTimeout(3000)

const panel = await page.evaluate(() => {
  const p = document.querySelector('[data-dsh-settings]')
  if (!p) return null
  const r = p.getBoundingClientRect()
  const close = p.querySelector('[data-dsh-close]')
  const cr = close ? close.getBoundingClientRect() : null
  return {
    w: Math.round(r.width),
    h: Math.round(r.height),
    hasClose: !!close,
    closeRect: cr ? [Math.round(cr.left), Math.round(cr.top), Math.round(cr.width), Math.round(cr.height)] : null,
    navH: (() => { const n = p.querySelector('nav'); return n ? Math.round(n.getBoundingClientRect().height) : -1 })(),
  }
})
check('④ 设置面板已标记且全屏', panel !== null && panel.w >= 380, JSON.stringify(panel))
check('⑤ 设置面板有关闭按钮', panel?.hasClose === true, JSON.stringify(panel?.closeRect))
check('⑥ 顶部导航已横向化(高度<80)', (panel?.navH ?? 999) < 80, 'navH=' + panel?.navH)

await page.click('[data-dsh-settings] > [data-dsh-close]').catch(() => {})
await page.waitForTimeout(2500)
const stillOpen = await page.evaluate(() => !!document.querySelector('[data-dsh-settings]'))
check('⑦ 点关闭按钮 → 面板关闭', stillOpen === false)

// 复现用户实际路径：从设置返回之后，再拉一次抽屉（原报告是这一步点不开）
await page.waitForTimeout(1500)
await page.click('#dsh-m-topbar [data-act="sidebar"]').catch(() => {})
await page.waitForTimeout(1500)
const s9 = await info()
check('⑨ 设置关闭后 → 抽屉仍可打开', s9?.sidebar === 'open', String(s9?.sidebar))
await page.mouse.click(372, 500)
await page.waitForTimeout(1000)

check('⑧ 无页面错误', errors.length === 0, errors.join(' | '))

await browser.close()

console.log('')
console.log('  移动端交互探针')
console.log('  ' + '-'.repeat(58))
for (const [ok, name, detail] of results) {
  console.log(`  ${ok ? '\u2713' : '\u2717'} ${name.padEnd(28)} ${ok ? '' : detail}`)
}
const failed = results.filter((r) => !r[0]).length
console.log('  ' + '-'.repeat(58))
console.log(`  通过 ${results.length - failed}/${results.length}`)
console.log('')
process.exit(failed === 0 ? 0 : 1)
