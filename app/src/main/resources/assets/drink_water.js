/*!
 * drink_water.js — 打水页（pages/drinkwater/drinkwater）用户辅助脚本
 *  1. 自动确认“开始使用”弹窗（仅限你点击“开始使用”后的短时间窗口内，对弹出的那一个弹窗点一次）
 *  2. 长按 0.5s 结算找零（胶囊进度按钮，替代拖动滑块）
 *
 * 生命周期：宿主是 SPA 容器，WebView/Window 在多次进出打水页之间不会重建，
 * 所以脚本不做“一次性初始化”：常驻一个 MutationObserver，每次扫描都按当前 DOM 状态
 * 重新判断“是否在打水页 / 弹窗是否新打开 / 是否出现了新的 .sliders”，并按需重新挂载。
 * 重复注入时，先销毁旧实例再重建（Native 端的开关改动会立即生效，观察者不会叠加）。
 *
 * Native 端在注入本脚本之前设置（均可不设，取默认值）：
 *   window.__XL_AUTO_CONFIRM__ = true | false     自动确认开始弹窗，默认 true；
 *                                                 false：不注入压制样式、不处理弹窗，保留原版弹窗手动点
 *   window.__XL_SETTLE_MODE__  = 'hold' | 'off'   结算方式，默认 'hold'（长按 0.5s 胶囊）；
 *                                                 'off'：完全不改底部 DOM，保留原版拖拽滑块
 *   window.__XL_HOLD_THEME__   = 主题 id，见下方 THEME_META（classic / glass / loop / ratchet）
 *                                                 长按胶囊的外观主题，默认 'classic'；非法值按 'classic'（仅 hold 模式有效）
 */
(function () {
  'use strict';

  // ---------- 重复注入：先销毁旧实例 ----------
  try {
    if (window.__XL_DW__ && typeof window.__XL_DW__.dispose === 'function') window.__XL_DW__.dispose();
  } catch (e) {}

  var CONFIG = {
    holdMs: 500,             // 长按时长
    moveCancelPx: 12,        // 按住期间手指位移超过该值视为取消
    dragFrames: 8,           // 合成拖动分多少帧完成（rAF 驱动，非 setInterval）
    endStableFrames: 3,      // 到达终点后连续多少帧位置稳定，才发 touchend（等 onChange 推送完）
    endWaitMaxMs: 800,       // 终点等待上限，超时仍然发 touchend
    retryAfterMs: 2500,      // 结算手势发出后页面仍在（宿主没接受），多久后恢复长按以便重试
    // 支付宝小程序容器的 tap 由 touchstart/touchend 合成，默认派发一次 touch 序列；
    // 若日志提示“确认后弹窗仍可见”，改成 'click' 再试（二选一，绝不同时派发）
    confirmMode: 'touch',    // 'touch' | 'click'
    // 自动确认的安全范围（防止网站以后新增别的“确认”弹窗被误点）：
    //   ① 只有你亲手点了“开始使用”按钮，才在这个时间窗口内“武装”；窗口内弹出的第一个确认弹窗点一次，用完即失效
    //   ② 只在“待开始”阶段生效（“开始使用”按钮可见且 .sliders 未出现）
    //   ③ 弹窗正文必须匹配 confirmBodyPattern（每次自动确认都会在日志里打印弹窗正文，文案变了可据此调整）
    confirmArmMs: 5000,
    confirmBodyPattern: /开始使用/,   // 实测两种弹窗正文都含“开始使用”：“确认开始使用？”（联网机）/“…确认后开始使用。”（公共机）；设为 null 则不限制
    confirmVerifyMs: 1500,   // 确认后多久检查弹窗是否已关闭；没关则撤掉压制样式，让用户手动点
    debug: true
  };

  // 外部开关（同时兼容不带下划线的旧变量名 XL_AUTO_CONFIRM / XL_SETTLE_MODE）
  function pick(a, b) { return a !== undefined ? a : b; }
  var rawAuto = pick(window.__XL_AUTO_CONFIRM__, window.XL_AUTO_CONFIRM);
  CONFIG.autoConfirm = typeof rawAuto === 'boolean' ? rawAuto : true;
  CONFIG.mode = pick(window.__XL_SETTLE_MODE__, window.XL_SETTLE_MODE) === 'off' ? 'off' : 'hold';
  // 主题注册表：id 与样式里的 .xl-dw-theme-<id> 对应；name/desc 只用于设置页的预览卡片
  var THEME_META = [
    { id: 'classic', name: '经典', desc: '实体按键，蓝色填充' },
    { id: 'glass',   name: '玻璃', desc: '磨砂玻璃，流光扫过' },
    { id: 'loop',    name: '边界闭环', desc: '能量环沿轮廓闭合锁止' },
    { id: 'ratchet', name: '棘轮咬合', desc: '逐齿弹起，卡爪收紧，最终机械锁止' }
  ];
  var THEMES = THEME_META.map(function (m) { return m.id; });
  var IDLE_TEXT = '按住 0.5 秒结算找零';
  function normalizeTheme(t) {
    if (t === 'wave' || t === 'water') return 'loop';
    if (t === 'aurora' || t === 'neon' || t === 'retro') return 'ratchet';
    return THEMES.indexOf(t) !== -1 ? t : 'classic';
  }
  CONFIG.theme = normalizeTheme(window.__XL_HOLD_THEME__);

  var log = function () {
    if (CONFIG.debug && window.console) {
      var a = ['[XL-DW]'].concat([].slice.call(arguments));
      console.log.apply(console, a);
    }
  };

  // 两项功能都关：什么都不做（也不挂观察者）
  if (!CONFIG.autoConfirm && CONFIG.mode === 'off') {
    window.__XL_DW__ = { dispose: function () {} };
    log('两项功能均已关闭，脚本不做任何修改');
    return;
  }

  var disposed = false;

  // =====================================================================
  // 工具
  // =====================================================================
  function raf(fn) {
    return (window.requestAnimationFrame || function (f) { return setTimeout(f, 16); })(fn);
  }
  function caf(id) {
    (window.cancelAnimationFrame || clearTimeout)(id);
  }
  function now() {
    return (window.performance && performance.now) ? performance.now() : Date.now();
  }
  function throttleRaf(fn) {
    var pending = false;
    return function () {
      if (pending) return;
      pending = true;
      raf(function () { pending = false; fn(); });
    };
  }
  function isShown(el) {
    if (!el || !el.isConnected) return false;
    var r = el.getBoundingClientRect();
    if (r.width === 0 && r.height === 0) return false;
    // 沿祖先链检查：小程序组件的节点常驻 DOM，靠 display/visibility/opacity 切换显隐
    for (var n = el; n && n !== document.documentElement; n = n.parentElement) {
      var cs = getComputedStyle(n);
      if (cs.display === 'none' || cs.visibility === 'hidden' || cs.opacity === '0') return false;
    }
    return true;
  }

  // 合成 Touch 事件（tap / 拖动都靠它；不支持 Touch 构造时退化为普通 Event）
  function makeTouchEvent(type, target, x, y) {
    try {
      var touch = new Touch({ identifier: 1, target: target, clientX: x, clientY: y,
        pageX: x + scrollX, pageY: y + scrollY, screenX: x, screenY: y });
      var list = type === 'touchend' ? [] : [touch];
      return new TouchEvent(type, {
        bubbles: true, cancelable: true, composed: true,
        touches: list, targetTouches: list, changedTouches: [touch]
      });
    } catch (e) {
      var ev = new Event(type, { bubbles: true, cancelable: true, composed: true });
      var t = { identifier: 1, target: target, clientX: x, clientY: y, pageX: x, pageY: y, screenX: x, screenY: y };
      var arr = type === 'touchend' ? [] : [t];
      ev.touches = arr; ev.targetTouches = arr; ev.changedTouches = [t];
      return ev;
    }
  }

  // 一次 tap：touchstart + touchend（同一点）
  function dispatchTap(el) {
    var r = el.getBoundingClientRect();
    var x = r.left + r.width / 2, y = r.top + r.height / 2;
    el.dispatchEvent(makeTouchEvent('touchstart', el, x, y));
    el.dispatchEvent(makeTouchEvent('touchend', el, x, y));
  }

  // 可开关的 <style>：on 时注入，off 时移除（幂等）
  function toggleStyle(id, css) {
    var node = null;
    return {
      set: function (on) {
        if (on && !node) {
          node = document.createElement('style');
          node.id = id;
          node.textContent = css;
          (document.head || document.documentElement).appendChild(node);
        } else if (!on && node) {
          node.remove();
          node = null;
        }
      }
    };
  }

  // ---------- 样式 ----------
  // 主样式：胶囊自身的样式（只对 .xl-dw-* 生效，对页面无影响）
  var mainStyle = toggleStyle('xl-dw-style', [
    '.xl-dw-hold{position:relative;overflow:hidden;border-radius:999px;touch-action:none;',
    '  -webkit-user-select:none;user-select:none;-webkit-touch-callout:none;}',
    // 视觉：整条“实体按键”——凸起的渐变 + 顶部高光 + 底部内阴影，按住时整体下陷；文字始终居中。
    // 外观全部由 CSS 变量决定，主题 = 一组变量覆盖（.xl-dw-theme-*）。--p 为进度(0~1)，同时驱动填充与文字反色。
    // 填充用 clip-path 裁剪（而不是 scaleX），这样填充上的纹理（气泡/分格）不会随进度被拉伸。
    '.xl-dw-pill{--p:0;',
    '  --bg:linear-gradient(180deg,#f6f9ff,#e4edfb);--bd:rgba(16,130,255,.22);',
    '  --sh:0 6px 14px rgba(16,100,220,.18),inset 0 1px 0 rgba(255,255,255,.95),inset 0 -2px 4px rgba(16,100,220,.10);',
    '  --sh-on:0 1px 3px rgba(16,100,220,.22),inset 0 2px 5px rgba(16,100,220,.20);',
    '  --fill:linear-gradient(180deg,#46a0ff,#0f78f0);--fill-sh:inset 0 1px 0 rgba(255,255,255,.45),inset 0 -3px 6px rgba(0,60,160,.25);',
    '  --fill-done:linear-gradient(180deg,#44d98c,#19be6b);',
    '  --txt:#0a64d6;--txt-on:#fff;--txt-on-sh:0 1px 2px rgba(0,60,160,.35);',
    '  position:absolute;left:0;top:0;right:0;bottom:0;z-index:99;border-radius:999px;box-sizing:border-box;overflow:hidden;',
    '  background:var(--bg);border:1px solid var(--bd);box-shadow:var(--sh);',
    '  touch-action:none;cursor:pointer;outline:none;-webkit-tap-highlight-color:transparent;',
    '  transition:transform .12s ease-out,box-shadow .12s ease-out;}',
    '.xl-dw-pill.xl-dw-active{transform:translateY(1px) scale(.985);box-shadow:var(--sh-on);}',
    '.xl-dw-fill{position:absolute;left:0;top:0;bottom:0;width:100%;background:var(--fill);box-shadow:var(--fill-sh);',
    '  clip-path:inset(0 calc((1 - var(--p)) * 100%) 0 0);transition:clip-path .2s ease-out;}',
    '.xl-dw-label{position:absolute;left:0;top:0;right:0;bottom:0;display:flex;align-items:center;justify-content:center;',
    '  font-size:15px;font-weight:600;letter-spacing:.5px;color:var(--txt);pointer-events:none;white-space:nowrap;}',
    // 上层反色文字用与填充相同的裁剪，文字颜色随填充边缘逐像素反色
    '.xl-dw-label-on{color:var(--txt-on);text-shadow:var(--txt-on-sh);clip-path:inset(0 calc((1 - var(--p)) * 100%) 0 0);',
    '  transition:clip-path .2s ease-out;}',
    // 按住：进度完全跟手，不加过渡
    '.xl-dw-active .xl-dw-fill,.xl-dw-active .xl-dw-label-on{transition:none;}',
    '.xl-dw-pill.xl-dw-done .xl-dw-fill{background:var(--fill-done);}',
    '.xl-dw-pill.xl-dw-done{pointer-events:none;}',
    // —— 主题：glass 磨砂玻璃（呼应液态玻璃风格）：半透明高光底 + 流光扫过 ——
    '.xl-dw-theme-glass{--bg:linear-gradient(180deg,rgba(255,255,255,.95),rgba(200,224,255,.82));--bd:rgba(255,255,255,.95);',
    '  --sh:0 8px 22px rgba(16,100,220,.22),0 0 0 1px rgba(16,130,255,.18),inset 0 1px 0 #fff,inset 0 -1px 0 rgba(255,255,255,.5);',
    '  --sh-on:0 2px 8px rgba(16,100,220,.24),0 0 0 1px rgba(16,130,255,.22),inset 0 1px 0 #fff;',
    '  --fill:linear-gradient(180deg,rgba(90,176,255,.85),rgba(16,130,255,.92));--fill-sh:inset 0 1px 0 rgba(255,255,255,.7);',
    '  --fill-done:linear-gradient(180deg,rgba(90,220,150,.9),rgba(25,190,107,.95));-webkit-backdrop-filter:blur(10px);backdrop-filter:blur(10px);}',
    '.xl-dw-theme-glass .xl-dw-fill::after{content:"";position:absolute;left:0;top:0;right:0;bottom:0;',
    '  background:linear-gradient(105deg,transparent 35%,rgba(255,255,255,.55) 50%,transparent 65%);background-size:220% 100%;background-position:120% 0;}',
    '.xl-dw-theme-glass.xl-dw-active .xl-dw-fill::after{animation:xl-dw-sheen .5s linear forwards;}',
    '@keyframes xl-dw-sheen{from{background-position:120% 0}to{background-position:-20% 0}}',
    // —— 主题：loop / wave 边界闭环：能量环沿轮廓闭合锁止 ——
    '.xl-dw-theme-loop,.xl-dw-theme-wave{--edge:#dfff43;background:#111617;border:1px solid #394544;',
    '  box-shadow:inset 0 1px 0 #ffffff08,0 5px 0 #050707;overflow:visible;color:#f2f4ed;}',
    '.xl-dw-theme-loop .xl-dw-fill,.xl-dw-theme-wave .xl-dw-fill{inset:0;width:auto;height:auto;border-radius:inherit;',
    '  clip-path:none;background:conic-gradient(from -90deg,var(--edge) var(--angle,0deg),transparent 0);padding:2px;',
    '  -webkit-mask:linear-gradient(#000 0 0) content-box,linear-gradient(#000 0 0);-webkit-mask-composite:xor;',
    '  mask:linear-gradient(#000 0 0) content-box,linear-gradient(#000 0 0);mask-composite:exclude;',
    '  filter:drop-shadow(0 0 5px rgba(223,255,67,.65));pointer-events:none;}',
    '.xl-dw-shock{position:absolute;inset:0;border:2px solid var(--edge,#dfff43);border-radius:inherit;opacity:0;pointer-events:none;z-index:4;}',
    '.xl-dw-done .xl-dw-shock{animation:xl-dw-shock .42s ease-out forwards;}',
    '@keyframes xl-dw-shock{0%{opacity:.9;transform:scale(1)}100%{opacity:0;transform:scale(1.08,1.35)}}',
    '.xl-dw-theme-loop .xl-dw-label,.xl-dw-theme-wave .xl-dw-label{color:#edf3ed;text-shadow:0 1px 2px #000;}',
    '.xl-dw-theme-loop .xl-dw-label-on,.xl-dw-theme-wave .xl-dw-label-on,',
    '.xl-dw-theme-ratchet .xl-dw-label-on,.xl-dw-theme-aurora .xl-dw-label-on{clip-path:none;opacity:0;text-shadow:none;}',
    '.xl-dw-theme-loop.xl-dw-done .xl-dw-label:not(.xl-dw-label-on),.xl-dw-theme-wave.xl-dw-done .xl-dw-label:not(.xl-dw-label-on),',
    '.xl-dw-theme-ratchet.xl-dw-done .xl-dw-label:not(.xl-dw-label-on),.xl-dw-theme-aurora.xl-dw-done .xl-dw-label:not(.xl-dw-label-on){opacity:0;}',
    '.xl-dw-theme-loop.xl-dw-done .xl-dw-label-on,.xl-dw-theme-wave.xl-dw-done .xl-dw-label-on,',
    '.xl-dw-theme-ratchet.xl-dw-done .xl-dw-label-on,.xl-dw-theme-aurora.xl-dw-done .xl-dw-label-on{opacity:1;}',
    '.xl-dw-theme-loop.xl-dw-done .xl-dw-fill,.xl-dw-theme-wave.xl-dw-done .xl-dw-fill{',
    '  background:conic-gradient(from -90deg,var(--edge) 1turn,transparent 0);filter:drop-shadow(0 0 7px #dfff43);}',
    '.xl-dw-theme-loop.xl-dw-done,.xl-dw-theme-wave.xl-dw-done{background:#111917;border-color:#dfff43;',
    '  box-shadow:0 0 0 1px #dfff4325,0 0 18px #dfff432c,inset 0 0 18px #dfff430c;',
    '  animation:xl-dw-wave-lock .42s cubic-bezier(.2,.8,.2,1) both;}',
    '.xl-dw-theme-loop.xl-dw-done .xl-dw-label,.xl-dw-theme-wave.xl-dw-done .xl-dw-label{color:#f4ffd0;}',
    '.xl-dw-theme-loop.xl-dw-done .xl-dw-label-on,.xl-dw-theme-wave.xl-dw-done .xl-dw-label-on{color:#dfff43;}',
    '@keyframes xl-dw-wave-lock{0%{transform:translateY(1px) scale(.985)}45%{transform:translateY(-1px) scale(1.012)}100%{transform:translateY(0) scale(1)}}',
    // —— 主题：ratchet / aurora 棘轮咬合：逐齿弹起、卡爪收紧、完成回震 ——
    '.xl-dw-theme-ratchet,.xl-dw-theme-aurora{--tooth-hot:#ff4b2e;background:#ecebe5;color:#181a17;border:2px solid #ecebe5;',
    '  box-shadow:0 5px 0 #777970;overflow:hidden;}',
    '.xl-dw-theme-ratchet .xl-dw-fill,.xl-dw-theme-aurora .xl-dw-fill{display:none;}',
    '.xl-dw-teeth{position:absolute;left:10px;right:10px;bottom:6px;height:8px;display:flex;gap:3px;z-index:1;pointer-events:none;}',
    '.xl-dw-tooth{flex:1;min-width:0;background:#b8bab2;transform:scaleY(.28) translateY(2px);transform-origin:bottom;}',
    '.xl-dw-tooth.on{animation:xl-dw-tooth-kick .14s cubic-bezier(.2,1.8,.4,1) both;background:var(--tooth-hot);transform:scaleY(1);}',
    '@keyframes xl-dw-tooth-kick{0%{transform:scaleY(.25) translateY(3px)}65%{transform:scaleY(1.18) translateY(-2px)}100%{transform:scaleY(1) translateY(0)}}',
    '.xl-dw-theme-ratchet:before,.xl-dw-theme-ratchet:after,.xl-dw-theme-aurora:before,.xl-dw-theme-aurora:after{',
    '  content:"";position:absolute;top:13px;width:18px;height:24px;border-top:2px solid #b0b2aa;border-bottom:2px solid #b0b2aa;opacity:.9;z-index:2;',
    '  transition:width .12s,border-color .12s,transform .12s;}',
    '.xl-dw-theme-ratchet:before,.xl-dw-theme-aurora:before{left:13px;border-left:2px solid #b0b2aa;}',
    '.xl-dw-theme-ratchet:after,.xl-dw-theme-aurora:after{right:13px;border-right:2px solid #b0b2aa;}',
    '.xl-dw-theme-ratchet.xl-dw-active,.xl-dw-theme-aurora.xl-dw-active{transform:translateY(3px);box-shadow:0 2px 0 #777970;}',
    '.xl-dw-theme-ratchet.xl-dw-active:before,.xl-dw-theme-ratchet.xl-dw-active:after,',
    '.xl-dw-theme-aurora.xl-dw-active:before,.xl-dw-theme-aurora.xl-dw-active:after{width:26px;border-color:var(--tooth-hot);transform:scaleY(1.08);}',
    '.xl-dw-theme-ratchet .xl-dw-label,.xl-dw-theme-aurora .xl-dw-label{color:#272a25;transition:letter-spacing .1s,transform .1s;}',
    '.xl-dw-theme-ratchet.xl-dw-active .xl-dw-label,.xl-dw-theme-aurora.xl-dw-active .xl-dw-label{letter-spacing:1px;transform:translateY(-2px);}',
    '.xl-dw-theme-ratchet .xl-dw-label-on,.xl-dw-theme-aurora .xl-dw-label-on{color:#201b18;text-shadow:none;}',
    '.xl-dw-theme-ratchet.xl-dw-done,.xl-dw-theme-aurora.xl-dw-done{animation:xl-dw-lock-jolt .24s ease-out;background:#ecebe5;border-color:#343a32;',
    '  box-shadow:0 0 0 2px #343a3218,0 5px 0 #777970,0 0 16px #7b927c28;}',
    '.xl-dw-theme-ratchet.xl-dw-done:before,.xl-dw-theme-aurora.xl-dw-done:before{transform:translateX(4px);border-color:#201b18;}',
    '.xl-dw-theme-ratchet.xl-dw-done:after,.xl-dw-theme-aurora.xl-dw-done:after{transform:translateX(-4px);border-color:#201b18;}',
    '.xl-dw-theme-ratchet.xl-dw-done .xl-dw-tooth,.xl-dw-theme-aurora.xl-dw-done .xl-dw-tooth{background:#211c19;transform:scaleY(1);}',
    '.xl-dw-theme-ratchet.xl-dw-done .xl-dw-label,.xl-dw-theme-aurora.xl-dw-done .xl-dw-label{color:#201b18;}',
    '@keyframes xl-dw-lock-jolt{0%{transform:translateY(3px) scale(1)}35%{transform:translateY(1px) scale(1.015)}70%{transform:translateY(2px) scale(.995)}100%{transform:translateY(0) scale(1)}}',
    '.xl-dw-theme-ratchet.xl-dw-done .xl-dw-tooth,.xl-dw-theme-ratchet.xl-dw-done .xl-dw-tooth:nth-child(3n),.xl-dw-theme-ratchet.xl-dw-done .xl-dw-tooth:nth-child(4n),',
    '.xl-dw-theme-aurora.xl-dw-done .xl-dw-tooth,.xl-dw-theme-aurora.xl-dw-done .xl-dw-tooth:nth-child(3n),.xl-dw-theme-aurora.xl-dw-done .xl-dw-tooth:nth-child(4n){background:#343a32;}',
    '.xl-dw-theme-ratchet.xl-dw-done:before,.xl-dw-theme-ratchet.xl-dw-done:after,',
    '.xl-dw-theme-aurora.xl-dw-done:before,.xl-dw-theme-aurora.xl-dw-done:after{border-color:#343a32;}'
  ].join('\n'));

  // 弹窗静态压制：样式在弹窗节点插入之前就存在，首帧即不可见（JS 事后加 class 总会晚几帧）。
  // 用 clip-path 而不是 opacity/visibility：只影响绘制，节点的 opacity/visibility/尺寸仍反映宿主真实状态，
  // “弹窗是否真的打开”的检测不受影响。仅在打水页生效，离开后撤掉，不影响其他页面的弹窗。
  var popupHide = toggleStyle('xl-dw-popup-hide',
    '.l-message-container,.l-message-cover{clip-path:inset(100%) !important;pointer-events:none !important;}');

  // hold 模式：原生滑块子节点从首帧起淡出（保留尺寸，合成事件直接派发给它，不受影响）
  var holdHide = toggleStyle('xl-dw-hold-hide', '.sliders>*:not(.xl-dw-pill){opacity:0 !important;}');

  // =====================================================================
  // 预览模式：设置页的 WebView 里先设 window.__XL_DW_PREVIEW__ = true 再加载本脚本，
  // 只导出预览 API，不挂任何观察者、不碰宿主页面。预览与真实胶囊共用同一份 CSS 和 DOM 构建，所见即所得。
  //   __XL_DW_PREVIEW_API__.renderPicker(rootEl, selectedId, onPick(id, name))
  // =====================================================================
  if (window.__XL_DW_PREVIEW__) {
    mainStyle.set(true);
    window.__XL_DW_PREVIEW_API__ = { themes: THEME_META, mount: mountPreview, renderPicker: renderPicker };
    return;
  }

  // =====================================================================
  // 页面判定：以“可见的页面特征”为准（SPA 里 URL 不一定随页面变化）
  //   .sliders 可见（已开始使用），或“开始使用”主按钮可见（待开始）
  // =====================================================================
  function startBtnShown() {
    var btns = document.querySelectorAll('.a-button.btn');
    for (var i = 0; i < btns.length; i++) {
      if (/开始使用/.test(btns[i].textContent || '') && isShown(btns[i])) return true;
    }
    return false;
  }
  function slidersShown() {
    var s = document.querySelector('.sliders');
    return !!(s && isShown(s));
  }
  function onTargetPage() { return slidersShown() || startBtnShown(); }
  // “待开始”阶段：开始按钮可见且还没出现滑块。自动确认只在这个阶段生效
  function startPhase() { return !slidersShown() && startBtnShown(); }

  // =====================================================================
  // 功能 1：自动确认（点“开始使用”后武装 → 窗口内弹出的确认弹窗点一次 → 用完即失效）
  // =====================================================================
  var popup = { armedUntil: 0, armTimer: 0, fired: false, forceReveal: false };

  function isConfirmText(el) {
    return (el.textContent || '').replace(/\s+/g, '') === '确认';
  }

  function findConfirmBtn() {
    var cands = document.querySelectorAll('.l-message-ok, [data-clickable="true"]');
    for (var i = 0; i < cands.length; i++) {
      var el = cands[i];
      if (!isConfirmText(el)) continue;
      // 实测确认按钮是 .l-message-ok；data-clickable 只表示“绑了 tap”，所以非 .l-message-ok 的必须在弹窗容器内
      if (!el.classList.contains('l-message-ok') && !el.closest('.l-message-box, .l-message-mask, .l-message-container')) continue;
      if (!isShown(el)) continue;
      return el;
    }
    return null;
  }

  // 出现了别的弹窗（按钮不是“确认”，如报错提示）：别压制它，让用户看得到
  function otherPopupShown() {
    var oks = document.querySelectorAll('.l-message-ok');
    for (var k = 0; k < oks.length; k++) {
      if (!isConfirmText(oks[k]) && isShown(oks[k])) return true;
    }
    return false;
  }

  function popupBody(btn) {
    return btn.closest('.l-message-body, .l-message-container');
  }
  function bodyMatches(btn) {
    if (!CONFIG.confirmBodyPattern) return true;
    var body = popupBody(btn);
    return !!body && CONFIG.confirmBodyPattern.test(body.textContent || '');
  }

  function fireConfirm(btn) {
    popup.armedUntil = 0;     // 先“用掉”武装再派发：任何重入路径都不会二次触发
    popup.fired = true;
    var body = popupBody(btn);
    log('自动确认弹窗正文：', body ? (body.textContent || '').replace(/\s+/g, ' ').trim() : '(未取到)');
    try {
      if (CONFIG.confirmMode === 'click') btn.click();
      else dispatchTap(btn);
      log('已自动确认（' + CONFIG.confirmMode + '）');
    } catch (e) {
      log('确认派发失败', e);
    }
    setTimeout(function () {
      if (disposed || !findConfirmBtn()) return;
      // 弹窗没关：撤掉压制让用户能手动点，直到这次弹窗关闭
      popup.forceReveal = true;
      popupHide.set(false);
      log('警告：确认后弹窗仍可见；请把 CONFIG.confirmMode 改成 ' + (CONFIG.confirmMode === 'touch' ? "'click'" : "'touch'") + ' 再试');
    }, CONFIG.confirmVerifyMs);
  }

  function handlePopup(active) {
    if (popup.armedUntil && now() > popup.armedUntil) popup.armedUntil = 0;   // 武装过期
    var btn = startPhase() ? findConfirmBtn() : null;
    var open = !!btn;
    if (!open) { popup.forceReveal = false; popup.fired = false; }
    var armed = popup.armedUntil > 0;
    // 只有：已武装 + 待开始阶段 + 弹窗已打开 + 还没点过 + 正文符合（若配置了）才点
    if (armed && open && !popup.fired && bodyMatches(btn)) fireConfirm(btn);
    // 静态压制只在武装期间（以及点完到弹窗关闭之前）存在；别的弹窗（含非“确认”按钮的）一律不压制
    var foreign = active && otherPopupShown();
    popupHide.set(active && (popup.armedUntil > 0 || popup.fired) && !foreign && !popup.forceReveal);
  }

  // 用户亲手点了“开始使用”：武装，并同步压制（赶在弹窗首帧之前，不能等 rAF 扫描）
  function onUserTap(e) {
    if (disposed || !CONFIG.autoConfirm) return;
    var t = e.target;
    var b = t && t.closest && t.closest('.a-button.btn');
    if (!b || !/开始使用/.test(b.textContent || '')) return;
    popup.armedUntil = now() + CONFIG.confirmArmMs;
    popupHide.set(true);
    clearTimeout(popup.armTimer);
    popup.armTimer = setTimeout(schedule, CONFIG.confirmArmMs + 50);   // 到期后重扫一次，撤掉压制
    log('已武装自动确认', CONFIG.confirmArmMs + 'ms');
  }

  // =====================================================================
  // 功能 2：长按 0.5s 结算
  // =====================================================================
  function findThumb(root) {
    return root.querySelector('uni-movable-view, movable-view, .movable-view, [class*="movable-view"]');
  }

  // 合成一次完整拖动：touchstart -> 多帧 touchmove -> 等位置稳定 -> touchend
  function dispatchSettleDrag(sliders, done) {
    // 实测：#track 就是 movable-view（滑块本体），真正的轨道是外层 movable-area
    var thumb = findThumb(sliders) || sliders.querySelector('#track');
    if (!thumb) { log('未找到 movable-view(#track)'); done && done(false); return; }
    var area = sliders.querySelector('[class*="movable-area"]') || thumb.parentElement || sliders;

    var ar = area.getBoundingClientRect();
    var hr = thumb.getBoundingClientRect();
    var y = hr.top + hr.height / 2;
    var startX = hr.left + hr.width / 2;
    // 终点：轨道右端减去滑块半宽，确保到达最大位移
    var endX = ar.right - hr.width / 2;
    if (endX <= startX) endX = ar.right - 1;

    var frames = Math.max(2, CONFIG.dragFrames), i = 0;
    thumb.dispatchEvent(makeTouchEvent('touchstart', thumb, startX, y));

    (function step() {
      if (disposed) return;
      i++;
      var x = i >= frames ? endX : startX + (endX - startX) * (i / frames);   // 末帧强制到 endX
      thumb.dispatchEvent(makeTouchEvent('touchmove', thumb, x, y));
      if (i < frames) { raf(step); return; }
      waitAtEnd();
    })();

    // 实测：movable-view 的 onChange 是异步推给页面逻辑的，若末帧 touchmove 后立刻 touchend，
    // 页面会先收到 onEnd（此时它记录的 x 还没到最大值）而判定未完成并复位。
    // 所以停在终点，等滑块位置稳定（连续几帧在终点）后再发 touchend；rAF 轮询，有上限。
    function waitAtEnd() {
      var target = ar.right - hr.width;   // 滑块到达最大位移时的 left
      var t0 = now(), stable = 0;
      (function poll() {
        if (disposed) return;
        var atEnd = thumb.getBoundingClientRect().left >= target - 1;
        stable = atEnd ? stable + 1 : 0;
        if (stable >= CONFIG.endStableFrames || now() - t0 > CONFIG.endWaitMaxMs) {
          log('终点等待', Math.round(now() - t0) + 'ms', atEnd ? '已到终点' : '超时未到终点');
          thumb.dispatchEvent(makeTouchEvent('touchend', thumb, endX, y));
          done && done(true);
          return;
        }
        raf(poll);
      })();
    }
  }

  // 构建一个胶囊（真实结算按钮与设置页预览共用）
  function buildPill(theme) {
    var normTheme = theme;
    if (normTheme === 'wave' || normTheme === 'water') normTheme = 'loop';
    if (normTheme === 'aurora' || normTheme === 'neon' || normTheme === 'retro') normTheme = 'ratchet';
    if (THEMES.indexOf(normTheme) === -1) normTheme = 'classic';

    var pill = document.createElement('div');
    pill.className = 'xl-dw-pill xl-dw-theme-' + normTheme;
    var fillEl = document.createElement('div');
    fillEl.className = 'xl-dw-fill';
    pill.appendChild(fillEl);

    var teethList = [];
    if (normTheme === 'loop') {
      var shock = document.createElement('div');
      shock.className = 'xl-dw-shock';
      shock.setAttribute('aria-hidden', 'true');
      pill.appendChild(shock);
    } else if (normTheme === 'ratchet') {
      var teethWrap = document.createElement('div');
      teethWrap.className = 'xl-dw-teeth';
      teethWrap.setAttribute('aria-hidden', 'true');
      for (var i = 0; i < 12; i++) {
        var tooth = document.createElement('i');
        tooth.className = 'xl-dw-tooth';
        teethWrap.appendChild(tooth);
        teethList.push(tooth);
      }
      pill.appendChild(teethWrap);

      var shock4 = document.createElement('div');
      shock4.className = 'xl-dw-shock';
      shock4.setAttribute('aria-hidden', 'true');
      pill.appendChild(shock4);
    }

    var labelEl = document.createElement('span');
    labelEl.className = 'xl-dw-label';
    var labelOnEl = document.createElement('span');
    labelOnEl.className = 'xl-dw-label xl-dw-label-on';
    labelOnEl.setAttribute('aria-hidden', 'true');
    pill.appendChild(labelEl);
    pill.appendChild(labelOnEl);

    function setProgress(p) {
      pill.style.setProperty('--p', p);
      if (normTheme === 'loop') {
        pill.style.setProperty('--angle', (p * 360) + 'deg');
      } else if (normTheme === 'ratchet') {
        var count = Math.floor(p * teethList.length);
        for (var j = 0; j < teethList.length; j++) {
          if (j < count) teethList[j].classList.add('on');
          else teethList[j].classList.remove('on');
        }
      }
    }

    function reset() {
      pill.classList.remove('xl-dw-active', 'xl-dw-done');
      setProgress(0);
      if (normTheme === 'loop') {
        pill.style.setProperty('--angle', '0deg');
      } else if (normTheme === 'ratchet') {
        for (var j = 0; j < teethList.length; j++) {
          teethList[j].classList.remove('on');
        }
      }
    }

    return {
      pill: pill,
      theme: normTheme,
      setLabel: function (t) { labelEl.textContent = t; labelOnEl.textContent = t; },
      setProgress: setProgress,
      reset: reset
    };
  }

  // 预览：自动循环演示“待机 → 按住填充（真实 0.5s）→ 完成停留 → 复位”，状态切换与真实交互一致
  function mountPreview(container, theme) {
    var b = buildPill(theme);
    container.appendChild(b.pill);
    var running = false, timers = [], rafId = 0;
    function later(fn, ms) { timers.push(setTimeout(fn, ms)); }
    function reset() {
      b.reset();
      b.setLabel(IDLE_TEXT);
    }
    function cycle() {
      if (!running) return;
      reset();
      later(function () {                       // 待机停留 → 按下
        b.pill.classList.add('xl-dw-active');
        b.setLabel('保持按住…');
        var t0 = now();
        (function tick() {
          if (!running) return;
          var p = Math.min(1, (now() - t0) / CONFIG.holdMs);
          b.setProgress(p);
          if (p < 1) { rafId = raf(tick); return; }
          b.pill.classList.add('xl-dw-done');   // 与真实结算一致：完成时仍保持按下态
          b.setLabel('已完成');
          later(cycle, 1300);                   // 完成态停留 → 重新开始
        })();
      }, 900);
    }
    b.setLabel(IDLE_TEXT);
    return {
      start: function (delay) { if (running) return; running = true; later(cycle, delay || 0); },
      stop: function () { running = false; timers.forEach(clearTimeout); timers = []; caf(rafId); reset(); }
    };
  }

  // 注意：必须是函数声明（会被提升）。预览分支在脚本中途 return，放在它之后的 var 赋值不会执行
  function pickerCss() { return [
    'html,body{margin:0;background:transparent;font-family:-apple-system,"PingFang SC","Noto Sans CJK SC",sans-serif;',
    '  -webkit-tap-highlight-color:transparent;-webkit-user-select:none;user-select:none}',
    '.xlp-list{padding:2px 2px 6px}',
    '.xlp-card{display:flex;flex-direction:column;gap:11px;padding:12px;margin:0 0 12px;border-radius:16px;background:#fff;',
    '  border:1.5px solid #e5e7eb;box-sizing:border-box;transition:border-color .15s,background .15s}',
    '.xlp-card.on{border-color:#1082ff;background:#f5f9ff}',
    // 预览容器不用 .xl-dw-hold（它会 touch-action:none 挡住列表滚动）；胶囊本身不接收触摸，点击落在卡片上
    '.xlp-pv{position:relative;height:57px;border-radius:999px;overflow:hidden}',
    '.xlp-pv .xl-dw-pill{touch-action:auto;pointer-events:none}',
    '.xlp-meta{display:flex;align-items:center;justify-content:space-between;padding:0 2px}',
    '.xlp-name{font-size:14px;font-weight:700;color:#262626}',
    '.xlp-desc{font-size:11.5px;color:#8c8c8c;margin-top:2px}',
    '.xlp-chk{width:20px;height:20px;border-radius:50%;border:1.5px solid #d0d5dd;box-sizing:border-box;flex:none;position:relative}',
    '.xlp-card.on .xlp-chk{background:#1082ff;border-color:#1082ff}',
    '.xlp-card.on .xlp-chk::after{content:"";position:absolute;left:5.5px;top:2.5px;width:5px;height:9px;',
    '  border:solid #fff;border-width:0 2px 2px 0;transform:rotate(45deg)}'
  ].join('\n'); }

  // 设置页主题选择列表：由注册表驱动，新增主题无需改这里，也无需改 Native 端
  function renderPicker(root, selectedId, onPick) {
    var st = document.createElement('style');
    st.textContent = pickerCss();
    (document.head || document.documentElement).appendChild(st);
    var list = document.createElement('div');
    list.className = 'xlp-list';
    var cards = [], ctls = [];
    THEME_META.forEach(function (m, i) {
      var card = document.createElement('div');
      card.className = 'xlp-card' + (m.id === selectedId ? ' on' : '');
      var pv = document.createElement('div');
      pv.className = 'xlp-pv';
      var meta = document.createElement('div');
      meta.className = 'xlp-meta';
      var txt = document.createElement('div');
      var nm = document.createElement('div'); nm.className = 'xlp-name'; nm.textContent = m.name;
      var ds = document.createElement('div'); ds.className = 'xlp-desc'; ds.textContent = m.desc;
      txt.appendChild(nm); txt.appendChild(ds);
      var chk = document.createElement('div'); chk.className = 'xlp-chk';
      meta.appendChild(txt); meta.appendChild(chk);
      card.appendChild(pv); card.appendChild(meta);
      list.appendChild(card);
      cards.push(card);
      var ctl = mountPreview(pv, m.id);
      ctls.push(ctl);
      ctl.start(i * 260);                        // 错开起点，避免几张卡同步闪动
      card.addEventListener('click', function () {
        cards.forEach(function (c) { c.classList.remove('on'); });
        card.classList.add('on');
        try { if (onPick) onPick(m.id, m.name); } catch (e) {}
      });
    });
    root.appendChild(list);
    document.addEventListener('visibilitychange', function () {   // 页面不可见时暂停，省电
      ctls.forEach(function (c, i) { if (document.hidden) c.stop(); else c.start(i * 260); });
    });
  }

  var hold = null;   // 当前挂载的长按胶囊实例（对应当前这个 .sliders 节点）

  function createHold(el) {
    var inst = { el: el, settled: false, hiddenSeen: false };
    var idleText = IDLE_TEXT;

    el.classList.add('xl-dw-hold');
    var built = buildPill(CONFIG.theme);
    var pill = built.pill;
    pill.setAttribute('role', 'button');
    pill.setAttribute('tabindex', '0');
    pill.setAttribute('aria-label', '按住 0.5 秒结算找零');
    el.appendChild(pill);
    inst.pill = pill;

    var holding = false, t0 = 0, rafId = 0, sx = 0, sy = 0, retryTimer = 0;

    var setLabel = built.setLabel, setProgress = built.setProgress, resetPill = built.reset;
    function resetGesture() {
      holding = false;
      caf(rafId);
      resetPill();
      setLabel(idleText);
    }
    setLabel(idleText);

    function complete() {
      if (inst.settled) return;
      inst.settled = true;
      holding = false;
      setLabel('结算中…');
      pill.classList.add('xl-dw-done');
      dispatchSettleDrag(el, function () {
        setLabel('已完成');
        log('结算手势已派发');
        // 结算成功时页面会跳转/销毁；若页面仍在，说明宿主没接受，恢复长按以便重试，避免卡死
        retryTimer = setTimeout(function () {
          if (disposed || !el.isConnected || !isShown(el)) return;
          inst.settled = false;
          pill.classList.remove('xl-dw-done');
          resetGesture();
          log('结算后页面仍在，已恢复长按以便重试');
        }, CONFIG.retryAfterMs);
      });
    }

    function tick() {
      if (!holding) return;
      var p = Math.min(1, (now() - t0) / CONFIG.holdMs);
      setProgress(p);
      if (p >= 1) {
        holding = false;
        if (navigator.vibrate) try { navigator.vibrate(15); } catch (e) {}
        complete();
        return;
      }
      rafId = raf(tick);
    }

    function begin(x, y) {
      if (holding || inst.settled) return;
      holding = true; sx = x; sy = y; t0 = now();
      pill.classList.add('xl-dw-active');
      setLabel('保持按住…');
      rafId = raf(tick);
    }

    // 长按手势：rAF + 时间戳驱动，不用 setInterval
    pill.addEventListener('pointerdown', function (e) {
      e.preventDefault();
      try { pill.setPointerCapture(e.pointerId); } catch (err) {}
      begin(e.clientX, e.clientY);
    });
    pill.addEventListener('pointermove', function (e) {
      if (!holding) return;
      var dx = e.clientX - sx, dy = e.clientY - sy;
      if (dx * dx + dy * dy > CONFIG.moveCancelPx * CONFIG.moveCancelPx) resetGesture();
    });
    ['pointerup', 'pointercancel', 'lostpointercapture'].forEach(function (n) {
      pill.addEventListener(n, function () { if (holding) resetGesture(); });
    });
    pill.addEventListener('contextmenu', function (e) { e.preventDefault(); });
    // 无障碍：键盘 Enter/Space 按住
    pill.addEventListener('keydown', function (e) {
      if ((e.key === 'Enter' || e.key === ' ') && !e.repeat) { e.preventDefault(); begin(0, 0); }
    });
    pill.addEventListener('keyup', function (e) {
      if ((e.key === 'Enter' || e.key === ' ') && holding) resetGesture();
    });

    inst.complete = complete;
    // 同一个 .sliders 节点隐藏后又重新出现（页面被复用）：重置为全新状态
    inst.revive = function () {
      clearTimeout(retryTimer);
      inst.settled = false;
      pill.classList.remove('xl-dw-done');
      resetGesture();
    };
    inst.destroy = function () {
      clearTimeout(retryTimer);
      caf(rafId);
      if (pill.parentNode) pill.parentNode.removeChild(pill);
      el.classList.remove('xl-dw-hold');
    };
    return inst;
  }

  function findShownSliders() {
    var list = document.querySelectorAll('.sliders');
    for (var i = 0; i < list.length; i++) if (isShown(list[i])) return list[i];
    return null;
  }

  function handleSliders(active) {
    holdHide.set(active);
    // 旧节点已被销毁（离开页面/页面实例被替换）：丢弃旧实例
    if (hold && !hold.el.isConnected) { hold.destroy(); hold = null; }
    var el = active ? findShownSliders() : null;
    if (!el) { if (hold) hold.hiddenSeen = true; return; }
    // 出现了新的 .sliders 节点（进入下一台水机）：销毁旧的，重新挂载，settled 随新实例重置
    if (hold && hold.el !== el) { hold.destroy(); hold = null; }
    if (!hold || !hold.pill.isConnected) {      // 首次挂载，或胶囊被宿主重渲染抹掉
      if (hold) hold.destroy();
      hold = createHold(el);
      log('长按结算按钮已挂载');
      return;
    }
    if (hold.hiddenSeen) { hold.hiddenSeen = false; hold.revive(); log('同一节点重新出现，已重置'); }
  }

  // =====================================================================
  // 调度：常驻观察 + rAF 节流扫描
  // =====================================================================
  function scan() {
    if (disposed) return;
    var active = onTargetPage();
    if (CONFIG.autoConfirm) handlePopup(active);
    else popupHide.set(false);
    if (CONFIG.mode === 'hold') handleSliders(active);
  }
  var schedule = throttleRaf(scan);

  var observer = new MutationObserver(function (records) {
    // 忽略自己胶囊内部的变动（进度条每帧都会改 style），避免自触发
    for (var i = 0; i < records.length; i++) {
      var t = records[i].target;
      if (!(t && t.closest && t.closest('.xl-dw-pill'))) { schedule(); return; }
    }
  });

  var winEvents = ['hashchange', 'popstate', 'pageshow'];
  function onVisible() { if (!document.hidden) schedule(); }

  function start() {
    mainStyle.set(true);
    observer.observe(document.body || document.documentElement, {
      childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'style', 'hidden']
    });
    winEvents.forEach(function (n) { window.addEventListener(n, schedule); });
    document.addEventListener('visibilitychange', onVisible);
    if (CONFIG.autoConfirm) {
      document.addEventListener('touchend', onUserTap, true);   // 捕获阶段：先于页面自己的处理
      document.addEventListener('click', onUserTap, true);
    }
    scan();
    log('已启动', 'autoConfirm=' + CONFIG.autoConfirm, 'mode=' + CONFIG.mode, 'theme=' + CONFIG.theme);
  }

  function dispose() {
    disposed = true;
    observer.disconnect();
    winEvents.forEach(function (n) { window.removeEventListener(n, schedule); });
    document.removeEventListener('visibilitychange', onVisible);
    document.removeEventListener('touchend', onUserTap, true);
    document.removeEventListener('click', onUserTap, true);
    clearTimeout(popup.armTimer);
    if (hold) { hold.destroy(); hold = null; }
    popupHide.set(false);
    holdHide.set(false);
    mainStyle.set(false);
  }

  window.__XL_DW__ = {
    dispose: dispose,
    scan: scan,
    complete: function () { if (hold) hold.complete(); else log('当前没有已挂载的长按胶囊'); }
  };
  window.__XL_DW_DEBUG__ = window.__XL_DW__;   // 兼容旧调试入口

  start();
})();

/* =====================================================================
 * 调试（devtools 控制台）：
 *   __XL_DW__.complete()   手动触发一次结算手势（需已挂载长按胶囊）
 *   __XL_DW__.scan()       立即重新扫描一次当前 DOM
 *   __XL_DW__.dispose()    销毁本实例（移除样式、胶囊与观察者）
 * ===================================================================== */
