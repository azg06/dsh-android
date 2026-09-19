/**
 * 探针：在手机视口下打开官方设置面板，抓取它的真实结构并截图。
 *
 * 用途：设置面板在窄屏下会"挤成一坨"，但它的类名是 CSS Module 哈希，
 * 只能靠实测拿到结构与尺寸，再写针对性的窄屏修正。
 *
 * 用法：node probe-panel.mjs "<带 token 的 URL>" [输出 png]
 */
import { createRequire } from 'node:module'
import { existsSync, mkdirSync } from 'node:fs'

const PW = [
  'E:/DSH/deepseek-harness/node_modules/.pnpm/playwright-core@1.61.1/node_modules/playwright-core',
  'E:/工作目录/DSH android/deepseek-harness-mobile/node_modules/.pnpm/playwright-core@1.61.1/node_modules/playwright-core',
]
const pwDir = PW.find((p) => existsSync(p + '/package.json'))
if (!pwDir) throw new Error('找不到 playwright-core')
const require = createRequire(pwDir + '/package.json')
const { chromium } = require(pwDir)

const url = process.argv[2]
const shot = process.argv[3] ?? 'E:/工作目录/DSH android/_probe/settings.png'
if (!url) throw new Error('用法: node probe-panel.mjs "<URL>" [png]')

const outDir = shot.substring(0, shot.lastIndexOf('/'))
if (outDir && !existsSync(outDir)) mkdirSync(outDir, { recursive: true })

const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage({
  viewport: { width: 390, height: 844 },
  deviceScaleFactor: 2, isMobile: true, hasTouch: true,
})

await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 60000 })
await page.waitForTimeout(10000)

// 跳过首次运行引导，否则遮罩吞掉所有点击
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
await page.waitForTimeout(2000)

// 打开设置
const opened = await page.evaluate(() => {
  const btns = Array.from(document.querySelectorAll('button'))
  const target = btns.find((b) => /设置|settings/i.test(b.getAttribute('aria-label') || b.textContent || ''))
  if (target) { target.click(); return true }
  return false
})
await page.waitForTimeout(3000)

const report = await page.evaluate(() => {
  const describe = (el, depth) => {
    if (!el || depth > 4) return null
    const cs = getComputedStyle(el)
    const r = el.getBoundingClientRect()
    return {
      tag: el.tagName.toLowerCase(),
      cls: typeof el.className === 'string' ? el.className.slice(0, 60) : '',
      rect: [Math.round(r.left), Math.round(r.top), Math.round(r.width), Math.round(r.height)],
      display: cs.display,
      flexDir: cs.flexDirection,
      overflow: [cs.overflowX, cs.overflowY],
      text: el.children.length === 0 ? (el.textContent || '').trim().slice(0, 40) : '',
      children: Array.from(el.children).slice(0, 12).map((c) => describe(c, depth + 1)),
    }
  }
  const panels = Array.from(document.querySelectorAll('[role="dialog"],[aria-modal="true"]'))
  return {
    viewport: [window.innerWidth, window.innerHeight],
    panelCount: panels.length,
    panels: panels.slice(0, 2).map((p) => describe(p, 0)),
  }
})

report.opened = opened
console.log(JSON.stringify(report, null, 2))

try {
  await page.screenshot({ path: shot })
  console.log('screenshot ->', shot)
} catch (e) {
  console.log('screenshot failed:', String(e).slice(0, 100))
}

await browser.close()
