/**
 * 探针：查清官方右栏（[data-slot="rightbar"]）在主界面常驻渲染什么。
 *
 * 背景：外壳层用「右栏里是否出现 section/pre/code」判断"用户是否选中了工具调用"，
 * 但用户新建工作区后整个页面被一层跟随主题的纯色盖住 —— 说明这个判据把常驻内容
 * 也当成了"有选中项"，导致全屏浮层被误打开。
 *
 * 用法：node probe-rightbar.mjs "<带 token 的 URL>"
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

const snap = async (label) => {
  const info = await page.evaluate(() => {
    const slot = document.querySelector('[data-slot="rightbar"]') || document.querySelector('[data-slot="details"]')
    if (!slot) return { found: false }
    const tagCount = {}
    slot.querySelectorAll('*').forEach((el) => {
      tagCount[el.tagName.toLowerCase()] = (tagCount[el.tagName.toLowerCase()] || 0) + 1
    })
    const html = slot.innerHTML || ''
    const text = (slot.textContent || '').trim()
    return {
      found: true,
      childCount: slot.childElementCount,
      tags: tagCount,
      hasSection: slot.querySelector('section') !== null,
      hasPre: slot.querySelector('pre') !== null,
      hasCode: slot.querySelector('code') !== null,
      textLen: text.length,
      textHead: text.slice(0, 160),
      htmlHead: html.slice(0, 300),
      detailsFlag: document.documentElement.getAttribute('data-dsh-details'),
    }
  })
  console.log('=== ' + label + ' ===')
  console.log(JSON.stringify(info, null, 2))
  console.log('')
}

await snap('主界面（未选工作区）')

// 复现用户路径：打开工作区选择器
const opened = await page.evaluate(() => {
  const btns = Array.from(document.querySelectorAll('button'))
  const t = btns.find((b) => /选择工作区|添加工作区|工作区/i.test(b.getAttribute('aria-label') || b.textContent || ''))
  if (t) { t.click(); return t.textContent || t.getAttribute('aria-label') }
  return null
})
console.log('点击的工作区入口:', opened)
await page.waitForTimeout(3000)
await snap('工作区选择器打开后')

await browser.close()
