/**
 * 探针：查清官方设置面板的关闭机制。
 *
 * 背景：面板被本层改成整屏后，用户点不到原来那块"面板外侧遮罩"，
 * 而实测它既不响应 Escape、面板内也没有带"关闭"字样的按钮。
 * 这里把面板的父级链、兄弟节点、全部可点击控件列出来，找到真正的出口。
 *
 * 用法：node probe-close.mjs "<带 token 的 URL>"
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

await page.evaluate(() => {
  const btns = Array.from(document.querySelectorAll('button'))
  const t = btns.find((b) => /设置|settings/i.test(b.getAttribute('aria-label') || b.textContent || ''))
  if (t) t.click()
})
await page.waitForTimeout(3000)

const out = await page.evaluate(() => {
  const p = document.querySelector('[data-dsh-settings]')
  if (!p) return { error: 'not found' }
  const chain = []
  let el = p
  for (let i = 0; i < 6 && el; i++) {
    const r = el.getBoundingClientRect()
    chain.push({
      tag: el.tagName.toLowerCase(),
      cls: typeof el.className === 'string' ? el.className.slice(0, 50) : '',
      rect: [Math.round(r.left), Math.round(r.top), Math.round(r.width), Math.round(r.height)],
      attrs: Array.from(el.attributes).filter((a) => a.name.startsWith('data-') || a.name.startsWith('aria-')).map((a) => a.name + '=' + a.value).slice(0, 5),
    })
    el = el.parentElement
  }
  // 面板内全部控件
  const ctls = []
  p.querySelectorAll('button,[role="button"],a').forEach((b) => {
    const r = b.getBoundingClientRect()
    ctls.push({
      tag: b.tagName.toLowerCase(),
      aria: b.getAttribute('aria-label') || '',
      text: (b.textContent || '').trim().slice(0, 24),
      rect: [Math.round(r.left), Math.round(r.top), Math.round(r.width), Math.round(r.height)],
    })
  })
  // 面板的兄弟节点（"点外侧关闭"的遮罩通常在这）
  const sibs = []
  if (p.parentElement) {
    Array.from(p.parentElement.children).forEach((s) => {
      const r = s.getBoundingClientRect()
      sibs.push({
        tag: s.tagName.toLowerCase(),
        cls: typeof s.className === 'string' ? s.className.slice(0, 40) : '',
        rect: [Math.round(r.left), Math.round(r.top), Math.round(r.width), Math.round(r.height)],
      })
    })
  }
  return { chain, ctls, sibs }
})

console.log(JSON.stringify(out, null, 2))
await browser.close()
