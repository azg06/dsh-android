/**
 * 探针：列出页面上所有"覆盖视口 + 有背景色"的定位元素。
 *
 * 用途：用户报"新建工作区后整页变成跟随主题的纯黑/纯白"。
 * 这类现象一定来自某个铺满视口、带不透明背景的 fixed/absolute 元素，
 * 这里把候选全部列出来（含 visibility / opacity / z-index），逐一排查。
 *
 * 用法：node probe-overlay.mjs "<带 token 的 URL>"
 */
import { createRequire } from 'node:module'
import { existsSync } from 'node:fs'

const PW = [
  'E:/DSH/deepseek-harness/node_modules/.pnpm/playwright-core@1.61.1/node_modules/playwright-core',
]
const pwDir = PW.find((p) => existsSync(p + '/package.json'))
if (!pwDir) throw new Error('找不到 playwright-core')
const require = createRequire(pwDir + '/package.json')
const { chromium } = require(pwDir)

const url = process.argv[2]
const browser = await chromium.launch({ channel: 'msedge', headless: true })
const page = await browser.newPage({
  viewport: { width: 390, height: 844 },
  deviceScaleFactor: 2, isMobile: true, hasTouch: true,
})

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

const scan = () => page.evaluate(() => {
  const vw = window.innerWidth
  const vh = window.innerHeight
  const out = []
  document.querySelectorAll('*').forEach((el) => {
    const cs = getComputedStyle(el)
    if (cs.position !== 'fixed' && cs.position !== 'absolute') return
    const r = el.getBoundingClientRect()
    if (r.width < vw * 0.75 || r.height < vh * 0.5) return
    const bg = cs.backgroundColor
    if (!bg || bg === 'rgba(0, 0, 0, 0)' || bg === 'transparent') return
    out.push({
      tag: el.tagName.toLowerCase(),
      cls: (typeof el.className === 'string' ? el.className : '').slice(0, 46),
      rect: [Math.round(r.left), Math.round(r.top), Math.round(r.width), Math.round(r.height)],
      bg,
      z: cs.zIndex,
      vis: cs.visibility,
      op: cs.opacity,
      dsh: el.getAttribute('data-dsh-col') || el.id || '',
    })
  })
  return { vw, vh, count: out.length, items: out }
})

console.log('=== 主界面 ===')
console.log(JSON.stringify(await scan(), null, 2))

await browser.close()
