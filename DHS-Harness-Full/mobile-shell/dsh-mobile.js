/* =============================================================================
 * DeepSeek Harness · Android 移动端外壳层
 * -----------------------------------------------------------------------------
 * 把官方桌面三列 Shell 重新解释成手机形态：
 *
 *   侧边栏 → 左侧抽屉      右栏(工具详情) → 全屏浮层      顶栏 → 工作区 + 会话标题
 *
 * 三条实测结论，改动前务必先读：
 *
 *  1. 定位只用官方 slot 机制产出的 data-slot="<name>" —— 它是框架契约，跨版本稳定。
 *     列元素 = slot 元素的父元素。早期版本列名叫 conversation/details，
 *     0.1.5 改成了 main/rightbar，所以每个位置都做候选名回退。
 *
 *  2. 侧栏与右栏改成 position:fixed 后会脱离 grid 流，此时在流的子元素只剩中间列，
 *     自动放置算法会把它塞进第 1 条轨道（宽度 0）→ 整页空白。
 *     必须显式把中间列钉在 grid-column: 2 / 3。
 *
 *  3. 右栏在手机宽度下官方恒算出 0 宽，data-*-collapsed 永远是 true，
 *     所以不能用它判断"用户是否想看详情"，改用面板内容 + 用户手动开合。
 * ========================================================================== */
(function () {
  'use strict';

  if (window.__dshMobileShell) return;
  window.__dshMobileShell = true;

  /** 手机形态的视口上限。官方 SIDEBAR_AUTO_COLLAPSE=1024 是"窄桌面"，不是手机。 */
  var MOBILE_MAX = 820;

  var root = document.documentElement;
  var cols = { frame: null, sidebar: null, center: null, right: null };
  var ui = { topbar: null, scrim: null };
  var lastWorkspace = null;
  var detailsClosedByUser = false;
  /** 遮罩只服务于"用户从顶栏主动拉开的抽屉"，官方自行展开侧栏时不加遮罩。 */
  var scrimActive = false;

  var mq = window.matchMedia('(max-width: ' + MOBILE_MAX + 'px)');
  var q = function (sel) { return document.querySelector(sel); };

  // ---------------------------------------------------------------- 定位

  function resolveShell() {
    var sidebarSlot = q('[data-slot="sidebar"]');
    var centerSlot = q('[data-slot="main"]') || q('[data-slot="conversation"]');
    if (!sidebarSlot || !centerSlot) return false;

    var rootSlot = q('[data-slot="root"]');
    var frame = rootSlot
      ? rootSlot.firstElementChild
      : (sidebarSlot.parentElement ? sidebarSlot.parentElement.parentElement : null);
    if (!frame) return false;

    var rightSlot = q('[data-slot="rightbar"]') || q('[data-slot="details"]');

    cols.frame = frame;
    cols.sidebar = sidebarSlot.parentElement;
    cols.center = centerSlot.parentElement;
    cols.right = rightSlot ? rightSlot.parentElement : null;
    if (!cols.sidebar || !cols.center) return false;

    frame.setAttribute('data-dsh-col', 'frame');
    cols.sidebar.setAttribute('data-dsh-col', 'sidebar');
    cols.center.setAttribute('data-dsh-col', 'center');
    if (cols.right) cols.right.setAttribute('data-dsh-col', 'rightbar');

    // 拖拽分隔条在移动端无意义
    var overlay = q('[data-slot="shell.overlay"]');
    if (overlay && overlay.parentElement === frame) {
      var kids = frame.children;
      var after = false;
      for (var i = 0; i < kids.length; i++) {
        if (kids[i] === overlay.parentElement) { after = true; continue; }
        if (after) kids[i].setAttribute('data-dsh-col', 'handle');
      }
    }
    return true;
  }

  // ---------------------------------------------------------------- 顶栏

  function icon(path, size) {
    return '<svg viewBox="0 0 24 24" width="' + size + '" height="' + size + '" fill="none" ' +
      'stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" ' +
      'aria-hidden="true">' + path + '</svg>';
  }

  function buildChrome() {
    if (ui.topbar && ui.topbar.isConnected) return;

    ui.scrim = document.createElement('div');
    ui.scrim.id = 'dsh-m-scrim';
    ui.scrim.addEventListener('click', closeSidebar);

    ui.topbar = document.createElement('div');
    ui.topbar.id = 'dsh-m-topbar';
    // 顶栏只保留「打开导航」与当前会话标题。
    // 工作区指示、工具详情、新建会话三个元素已按需求移除：
    //   · 工作区那行依赖从侧栏 DOM 推断当前工作区，推断不出时只能显示"未指派"，
    //     反而误导；而且它占了顶栏最宝贵的一行空间。
    //   · 详情与新建会话在侧栏抽屉里都有，顶栏重复放纯属占位。
    ui.topbar.innerHTML =
      '<button type="button" class="dsh-m-btn" data-act="sidebar" aria-label="打开导航">'
        + icon('<path d="M3.5 6.5h17M3.5 12h17M3.5 17.5h17"/>', 21) + '</button>'
      + '<div class="dsh-m-meta">'
      + '  <div class="dsh-m-title"></div>'
      + '</div>';

    ui.topbar.addEventListener('click', function (e) {
      var btn = e.target.closest ? e.target.closest('[data-act]') : null;
      if (!btn) return;
      var act = btn.getAttribute('data-act');
      if (act === 'sidebar') {
        // 当前折叠 → 这次点击是"展开"，才需要遮罩
        scrimActive = sidebarCollapsed();
        toggleSidebar();
      }
    });

    document.body.appendChild(ui.scrim);
    document.body.appendChild(ui.topbar);
  }

  // ---------------------------------------------------------------- 通用点击

  function clickByAria(re, scope) {
    var btns = (scope || document).querySelectorAll('button');
    for (var i = 0; i < btns.length; i++) {
      var label = btns[i].getAttribute('aria-label') || btns[i].textContent || '';
      if (re.test(label)) { btns[i].click(); return true; }
    }
    return false;
  }

  // ---------------------------------------------------------------- 侧栏抽屉

  function sidebarCollapsed() {
    return !cols.frame || cols.frame.hasAttribute('data-sidebar-collapsed');
  }

  function syncSidebar() {
    if (!cols.frame) return;
    root.setAttribute('data-dsh-sidebar', sidebarCollapsed() ? 'closed' : 'open');
  }

  /**
   * 开合按钮。优先按无障碍标签匹配：展开态下 logoRow 里还有「品牌 = 新建会话」，
   * 只按位置取最靠上的会误点它（表现为点遮罩关不掉抽屉）。
   */
  function sidebarToggle() {
    if (!cols.sidebar) return null;
    var btns = cols.sidebar.querySelectorAll('button');
    var i;
    for (i = 0; i < btns.length; i++) {
      var label = btns[i].getAttribute('aria-label') || '';
      if (/侧边栏|sidebar|收起|展开|collapse|expand/i.test(label)) return btns[i];
    }
    var best = null, bestTop = Infinity;
    for (i = 0; i < btns.length; i++) {
      var r = btns[i].getBoundingClientRect();
      if (r.width === 0 && r.height === 0) continue;
      if (r.top < bestTop && r.top < 80) { bestTop = r.top; best = btns[i]; }
    }
    return best;
  }

  function openSidebar() {
    if (sidebarCollapsed()) {
      var b = sidebarToggle();
      if (b) b.click();
    }
  }

  function closeSidebar() {
    scrimActive = false;
    if (!sidebarCollapsed()) {
      var b = sidebarToggle();
      if (b) b.click();
    }
  }

  function toggleSidebar() {
    var b = sidebarToggle();
    if (b) b.click();
  }

  // ---------------------------------------------------------------- 右栏浮层

  /** 有选中项时官方才渲染结构化的输入/输出区；这些是语义标签，不受哈希类名影响。 */
  function detailsHasContent() {
    var slot = q('[data-slot="rightbar"]') || q('[data-slot="details"]');
    return slot ? slot.querySelector('section, pre, code, [data-diff], [data-terminal]') !== null : false;
  }

  /**
   * 右栏浮层保持关闭。
   *
   * 曾经尝试"右栏一有内容就自动弹出详情浮层"，但那个判据不可靠：重启后会话里出现
   * 任何内容都会命中，于是每次启动都弹出一片纯色浮层（手机上它的内容区是空的），
   * 用户看到的就是"每次重启都冒出一个黑框，只能点叉号关掉"。
   * 官方在窄屏下本就把右栏轨道算成 0 宽，保持原状比强行弹出更稳。
   */
  function syncDetails() {
    root.setAttribute('data-dsh-details', 'closed');
  }

  function toggleDetails() {
    var isOpen = root.getAttribute('data-dsh-details') === 'open';
    if (isOpen) {
      detailsClosedByUser = true;
      root.setAttribute('data-dsh-details', 'closed');
      var slot = q('[data-slot="rightbar"]') || q('[data-slot="details"]');
      if (slot) {
        var btn = slot.querySelector('button');
        if (btn) btn.click();
      }
    } else {
      detailsClosedByUser = false;
      root.setAttribute('data-dsh-details', 'open');
    }
  }

  function hookDetailsClose() {
    var slot = q('[data-slot="rightbar"]') || q('[data-slot="details"]');
    if (!slot || slot.__dshHooked) return;
    slot.__dshHooked = true;
    slot.addEventListener('click', function (e) {
      var btn = e.target.closest ? e.target.closest('button') : null;
      if (btn) {
        detailsClosedByUser = true;
        root.setAttribute('data-dsh-details', 'closed');
      }
    }, true);
  }

  // ---------------------------------------------------------------- 导出落盘

  /**
   * 接管页面里的 blob/data 下载，落到手机的公共 Download 目录。
   *
   * 官方的"导出日志"通过 blob: URL 触发下载，而 Android WebView 的 DownloadListener
   * 收不到这类下载 —— 用户在手机上点它没有任何反应。这里在捕获阶段接管：
   * 读出内容 → 转 base64 → 交给原生侧写入 Download。
   */
  function hookDownloads() {
    if (document.__dshDownloadHooked) return;
    document.__dshDownloadHooked = true;

    document.addEventListener('click', function (e) {
      var a = e.target && e.target.closest ? e.target.closest('a[download]') : null;
      if (!a) return;
      var href = a.href || '';
      if (href.indexOf('blob:') !== 0 && href.indexOf('data:') !== 0) return;
      if (!window.DSHDownload || !window.DSHDownload.save) return;

      e.preventDefault();
      e.stopPropagation();

      var name = a.getAttribute('download') || 'dsh-export';
      fetch(href)
        .then(function (r) { return r.blob(); })
        .then(function (blob) {
          var reader = new FileReader();
          reader.onload = function () {
            var s = String(reader.result);
            var comma = s.indexOf(',');
            window.DSHDownload.save(name, comma >= 0 ? s.substring(comma + 1) : s);
          };
          reader.readAsDataURL(blob);
        })
        .catch(function () {
          // 读不出来就只能放弃：原生 WebView 本来也处理不了 blob 下载，
          // 放行只会得到"点了没反应"，不如让用户知道失败了
          console.warn('[dsh] 导出内容读取失败');
        });
    }, true);
  }

  // ---------------------------------------------------------------- 设置面板

  /**
   * 标记官方的设置对话框。
   *
   * 它的结构是 panel(flex-row) 内含 <nav>：宽屏下是"左导航 + 右内容"。
   * 手机上面板总宽只有 ~334px，导航固定占 188px，内容区只剩 146px —— 就是"挤成一坨"。
   * 这里打上标记交给 CSS 翻成"顶部横向导航 + 全宽内容"。
   * 只标记真的带直接子 <nav> 的对话框，避免误伤别的弹窗。
   */
  function markSettingsPanels() {
    var dialogs = document.querySelectorAll('[role="dialog"], [aria-modal="true"]');
    for (var i = 0; i < dialogs.length; i++) {
      var d = dialogs[i];
      var nav = d.querySelector(':scope > nav');
      // 只认真正的设置面板：它的导航第一项是"通用设置"。
      // 仅凭"有 nav"太宽 —— 官方的工作区浏览器等对话框也带 nav，被本规则接管后会变成
      // 整屏而内容区为空，用户看到的是一片跟随主题的纯色、且退不出去。
      var isSettings = !!nav && /通用设置|general/i.test(nav.textContent || '');
      // 不额外注入关闭按钮：官方设置面板自带一个"关闭"（无 aria-label、文字为"关闭"），
      // 之前多注入一个的结果是界面上出现两个叉号。
      if (isSettings) {
        d.setAttribute('data-dsh-settings', '');
      } else {
        d.removeAttribute('data-dsh-settings');
      }
    }
  }

  /**
   * 给全屏面板补一个关闭按钮。
   *
   * 面板被改成整屏后，官方原本"点面板外侧遮罩关闭"这条路就被盖住了；
   * 而它自己那套关闭入口在导航栏里，窄屏下被本层隐藏标题时一并没了 —— 结果是
   * 用户进了设置就出不来。这里补一个固定右上角的关闭按钮，行为上优先点官方的
   * 关闭控件，找不到就派发 Escape（官方对话框普遍响应它）。
   */
  function ensurePanelClose(panel) {
    if (panel.querySelector('[data-dsh-close]')) return;
    panel.appendChild(makeCloseButton(function () {
      closePanel(panel);
    }));
  }

  /** 统一的关闭按钮。 */
  function makeCloseButton(onClick) {
    var btn = document.createElement('button');
    btn.setAttribute('type', 'button');
    btn.setAttribute('data-dsh-close', '');
    btn.setAttribute('aria-label', '关闭');
    btn.innerHTML = '<svg viewBox="0 0 24 24" width="20" height="20" fill="none" ' +
      'stroke="currentColor" stroke-width="1.9" stroke-linecap="round" ' +
      'aria-hidden="true"><path d="M6 6l12 12M18 6L6 18"/></svg>';
    btn.addEventListener('click', function (e) {
      e.preventDefault();
      e.stopPropagation();
      onClick();
    });
    return btn;
  }

  /**
   * 右栏浮层的关闭出口。
   * 它的 z-index(70) 高于顶栏(40)，打开时会把顶栏整个盖住 —— 若只提供顶栏按钮，
   * 用户进去就出不来了。必须在浮层内部给一个明确出口。
   */
  function ensureDetailsClose() {
    if (!cols.right) return;
    if (root.getAttribute('data-dsh-details') !== 'open') return;
    if (cols.right.querySelector('[data-dsh-close]')) return;
    cols.right.appendChild(makeCloseButton(function () {
      detailsClosedByUser = true;
      root.setAttribute('data-dsh-details', 'closed');
    }));
  }

  /**
   * 关闭一个面板。按可靠性依次尝试三条通路：
   *   1) 面板内带"关闭/返回"语义的按钮
   *   2) 面板的父级遮罩 —— 官方惯例是点面板外侧关闭，而面板被本层改成整屏后
   *      用户已经点不到那块遮罩了，这里代它派发一次
   *   3) Escape（部分对话框支持）
   * 之所以要三条，是因为实测官方设置面板对前两条都不响应 Escape，且没有带
   * "关闭"字样的按钮 —— 只走一条会出现"进了设置出不来"。
   */
  function closePanel(panel) {
    var btns = panel.querySelectorAll('button');
    var closeBtn = panel.querySelector('[data-dsh-close]');
    for (var i = 0; i < btns.length; i++) {
      if (btns[i] === closeBtn) continue;
      // 官方这个关闭按钮没有 aria-label，只有可见文字"关闭" —— 两者都要看
      var label = (btns[i].getAttribute('aria-label') || '')
        + ' ' + (btns[i].textContent || '');
      if (/关闭|close|返回|back/i.test(label)) {
        btns[i].click();
        return;
      }
    }

    var parent = panel.parentElement;
    if (parent && parent !== document.body && parent !== document.documentElement) {
      parent.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true }));
      parent.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, cancelable: true }));
      parent.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }));
      return;
    }

    ['keydown', 'keyup'].forEach(function (type) {
      document.dispatchEvent(new KeyboardEvent(type, {
        key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true,
      }));
    });
  }

  // ---------------------------------------------------------------- 顶栏配色

  /**
   * 让顶栏跟随官方当前主题。
   *
   * 本层原先用 CSS 变量兜底上色，但那些变量在浅色模式下并不成立（顶栏会变成深色，
   * 与标题、图标糊在一起）。直接读官方内容区实际算出来的背景色/前景色最可靠 ——
   * 主题由官方管理，包括它自己切换深浅色时也能跟着变。
   */
  function applyTopbarTheme() {
    if (!ui.topbar) return;
    var ref = cols.center || document.body;
    var bg = '';
    var fg = '';
    var node = ref;
    // 往上找第一个真正有背景色的祖先（内容列本身常常是透明的）
    while (node && node !== document.documentElement) {
      var cs = getComputedStyle(node);
      var c = cs.backgroundColor;
      if (c && c !== 'rgba(0, 0, 0, 0)' && c !== 'transparent') {
        bg = c;
        fg = cs.color;
        break;
      }
      node = node.parentElement;
    }
    if (!bg) {
      bg = getComputedStyle(document.body).backgroundColor;
    }
    if (bg && bg !== 'rgba(0, 0, 0, 0)') ui.topbar.style.background = bg;
    // 前景不沿用官方元素的 color —— 它未必和背景成对（浅色模式下可能读到深底+深字，
    // 结果顶栏一片糊）。直接按背景亮度选黑或白，保证任何主题下都看得清。
    var text = readableOn(bg);
    if (text) ui.topbar.style.color = text;
  }

  /** 根据背景色亮度返回可读的前景色。 */
  function readableOn(bg) {
    var m = /rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)/.exec(bg || '');
    if (!m) return '';
    var lum = (0.299 * Number(m[1]) + 0.587 * Number(m[2]) + 0.114 * Number(m[3])) / 255;
    return lum > 0.6 ? '#0f1115' : '#f5f6f7';
  }

  // ---------------------------------------------------------------- 顶栏信息

  function renderMeta() {
    if (!ui.topbar) return;
    var titleEl = ui.topbar.querySelector('.dsh-m-title');
    if (titleEl) {
      titleEl.textContent = (document.title || '')
        .replace(/\s*[-·|]\s*DeepSeek Harness\s*$/i, '').trim();
    }
  }

  // ---------------------------------------------------------------- 同步

  var scheduled = false;
  function schedule() {
    if (scheduled) return;
    scheduled = true;
    var raf = window.requestAnimationFrame || function (f) { return window.setTimeout(f, 16); };
    raf(function () {
      scheduled = false;
      apply();
    });
  }

  function apply() {
    var mobile = mq.matches;

    if (!resolveShell()) {
      // 官方 UI 还没挂载：保持顶栏不可见，别让用户看到一个空壳
      if (ui.topbar) ui.topbar.style.display = 'none';
      return;
    }

    if (!mobile) {
      root.removeAttribute('data-dsh-mobile');
      root.removeAttribute('data-dsh-sidebar');
      root.removeAttribute('data-dsh-details');
      root.removeAttribute('data-dsh-modal');
      if (ui.topbar) ui.topbar.style.display = 'none';
      if (ui.scrim) ui.scrim.style.display = 'none';
      return;
    }

    root.setAttribute('data-dsh-mobile', '');
    buildChrome();
    ui.topbar.style.display = '';
    ui.scrim.style.display = '';

    // 官方引导/对话框带全屏遮罩并接管点击，此时顶栏只留信息、不抢交互，
    // 否则用户会以为"按钮点了没反应"（其实是被弹窗吞掉了）。
    if (q('[role="dialog"], [aria-modal="true"]')) root.setAttribute('data-dsh-modal', '');
    else root.removeAttribute('data-dsh-modal');

    syncSidebar();
    syncDetails();
    markSettingsPanels();
    applyTopbarTheme();
    hookDownloads();
    renderMeta();

    // 官方把侧栏收起来时，遮罩意图一并复位，避免下次展开时残留
    if (sidebarCollapsed()) scrimActive = false;
    root.setAttribute('data-dsh-scrim', scrimActive ? 'on' : 'off');
  }

  var observer = new MutationObserver(schedule);
  function observe() {
    if (!document.body) { window.setTimeout(observe, 50); return; }
    observer.observe(document.body, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ['data-sidebar-collapsed', 'data-rightbar-collapsed', 'style', 'class'],
    });
    schedule();
  }

  if (mq.addEventListener) mq.addEventListener('change', schedule);
  else if (mq.addListener) mq.addListener(schedule);
  window.addEventListener('resize', schedule, { passive: true });
  window.addEventListener('orientationchange', schedule, { passive: true });

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', observe);
  else observe();

  /** 供 Android 侧与自动化探针读取。 */
  window.__dshMobileShellInfo = function () {
    return {
      mobile: mq.matches,
      resolved: !!cols.frame,
      sidebar: root.getAttribute('data-dsh-sidebar'),
      details: root.getAttribute('data-dsh-details'),
      workspace: root.getAttribute('data-dsh-workspace'),
      workspaceText: ui.topbar
        ? (ui.topbar.querySelector('.dsh-m-workspace-text') || {}).textContent
        : null,
      grid: cols.frame ? window.getComputedStyle(cols.frame).gridTemplateColumns : null,
    };
  };
})();
