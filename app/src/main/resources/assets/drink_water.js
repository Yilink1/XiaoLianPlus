/*!
 * drink_water.js — 打水页（pages/drinkwater/drinkwater）用户辅助脚本
 *  1. 自动确认“开始使用”弹窗（每次弹窗打开只触发一次）
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
    confirmMinGapMs: 1500,   // 两次自动确认之间的最小间隔（防高频连点）
    confirmVerifyMs: 1500,   // 确认后多久检查弹窗是否已关闭；没关则撤掉压制样式，让用户手动点
    debug: true
  };

  // 外部开关（同时兼容不带下划线的旧变量名 XL_AUTO_CONFIRM / XL_SETTLE_MODE）
  function pick(a, b) { return a !== undefined ? a : b; }
  var rawAuto = pick(window.__XL_AUTO_CONFIRM__, window.XL_AUTO_CONFIRM);
  CONFIG.autoConfirm = typeof rawAuto === 'boolean' ? rawAuto : true;
  CONFIG.mode = pick(window.__XL_SETTLE_MODE__, window.XL_SETTLE_MODE) === 'off' ? 'off' : 'hold';

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
    // 配色取自页面自身：主色 #1082ff、浅底 #ecf5ff、成功色 #19be6b；进度 --p 同时驱动填充/光标/文字反色
    '.xl-dw-pill{--p:0;position:absolute;left:0;top:0;right:0;bottom:0;z-index:99;border-radius:999px;',
    '  background:#ecf5ff;border:1px solid rgba(16,130,255,.28);box-sizing:border-box;',
    '  box-shadow:0 2px 10px rgba(16,130,255,.18);overflow:hidden;',
    '  touch-action:none;cursor:pointer;outline:none;-webkit-tap-highlight-color:transparent;',
    '  transition:transform .15s ease-out,box-shadow .15s ease-out;}',
    '.xl-dw-pill.xl-dw-active{transform:scale(.985);box-shadow:0 1px 5px rgba(16,130,255,.3);}',
    '.xl-dw-fill{position:absolute;left:0;top:0;bottom:0;width:100%;transform-origin:left center;',
    '  transform:scaleX(var(--p));background:linear-gradient(90deg,#1082ff,#0a6be0);will-change:transform;',
    '  transition:transform .2s ease-out;}',
    '.xl-dw-knob{position:absolute;top:22%;bottom:22%;width:4px;border-radius:2px;background:rgba(255,255,255,.85);',
    '  left:calc(var(--p) * 100% - 10px);opacity:calc(var(--p) * 8);transition:left .2s ease-out,opacity .2s;pointer-events:none;}',
    '.xl-dw-label{position:absolute;left:0;top:0;right:0;bottom:0;display:flex;align-items:center;justify-content:center;',
    '  font-size:15px;font-weight:600;letter-spacing:.5px;color:#0a64d6;pointer-events:none;white-space:nowrap;}',
    // 上层白字用同一个进度裁剪，文字颜色随填充边缘逐像素反色
    '.xl-dw-label-on{color:#fff;clip-path:inset(0 calc((1 - var(--p)) * 100%) 0 0);transition:clip-path .2s ease-out;}',
    '.xl-dw-active .xl-dw-fill,.xl-dw-active .xl-dw-knob,.xl-dw-active .xl-dw-label-on{transition:none;}',
    '.xl-dw-pill.xl-dw-done .xl-dw-fill{background:#19be6b;}',
    '.xl-dw-pill.xl-dw-done{pointer-events:none;}'
  ].join('\n'));

  // 弹窗静态压制：样式在弹窗节点插入之前就存在，首帧即不可见（JS 事后加 class 总会晚几帧）。
  // 用 clip-path 而不是 opacity/visibility：只影响绘制，节点的 opacity/visibility/尺寸仍反映宿主真实状态，
  // “弹窗是否真的打开”的检测不受影响。仅在打水页生效，离开后撤掉，不影响其他页面的弹窗。
  var popupHide = toggleStyle('xl-dw-popup-hide',
    '.l-message-container,.l-message-cover{clip-path:inset(100%) !important;pointer-events:none !important;}');

  // hold 模式：原生滑块子节点从首帧起淡出（保留尺寸，合成事件直接派发给它，不受影响）
  var holdHide = toggleStyle('xl-dw-hold-hide', '.sliders>*:not(.xl-dw-pill){opacity:0 !important;}');

  // =====================================================================
  // 页面判定：以“可见的页面特征”为准（SPA 里 URL 不一定随页面变化）
  //   .sliders 可见（已开始使用），或“开始使用”主按钮可见（待开始）
  // =====================================================================
  function onTargetPage() {
    var s = document.querySelector('.sliders');
    if (s && isShown(s)) return true;
    var btns = document.querySelectorAll('.a-button.btn');
    for (var i = 0; i < btns.length; i++) {
      if (/开始使用/.test(btns[i].textContent || '') && isShown(btns[i])) return true;
    }
    return false;
  }

  // =====================================================================
  // 功能 1：自动确认（按“弹窗从关到开”的边沿触发，每次打开只一次）
  // =====================================================================
  var popup = { wasOpen: false, lastFire: -Infinity, lastBtn: null, forceReveal: false };

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

  function fireConfirm(btn) {
    popup.lastFire = now();   // 先记账再派发：任何重入路径都不会二次触发
    popup.lastBtn = btn;
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
    var btn = active ? findConfirmBtn() : null;
    var open = !!btn;
    if (!open) popup.forceReveal = false;
    // 防连点间隔只约束“同一个按钮节点”的反复开关（宿主重渲染抖动）；新页面实例的新节点不受限
    if (open && !popup.wasOpen && (btn !== popup.lastBtn || now() - popup.lastFire > CONFIG.confirmMinGapMs)) fireConfirm(btn);
    popup.wasOpen = open;   // 弹窗关闭后 wasOpen 复位，下一次打开（含下一台水机）会再触发
    var foreign = active && otherPopupShown();
    popupHide.set(active && !foreign && !popup.forceReveal);
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

  var hold = null;   // 当前挂载的长按胶囊实例（对应当前这个 .sliders 节点）

  function createHold(el) {
    var inst = { el: el, settled: false, hiddenSeen: false };
    var idleText = '按住 0.5 秒结算找零';
    var tip = el.querySelector('.tips text');
    var tipText = tip && (tip.textContent || '').trim();
    if (tipText && tipText.length <= 12) idleText = tipText;   // 过长则用默认文案

    el.classList.add('xl-dw-hold');
    var pill = document.createElement('div');
    pill.className = 'xl-dw-pill';
    pill.setAttribute('role', 'button');
    pill.setAttribute('tabindex', '0');
    pill.setAttribute('aria-label', '按住 0.5 秒结算找零');
    var fillEl = document.createElement('div');
    fillEl.className = 'xl-dw-fill';
    var knobEl = document.createElement('div');
    knobEl.className = 'xl-dw-knob';
    var labelEl = document.createElement('span');
    labelEl.className = 'xl-dw-label';
    var labelOnEl = document.createElement('span');
    labelOnEl.className = 'xl-dw-label xl-dw-label-on';
    labelOnEl.setAttribute('aria-hidden', 'true');
    pill.appendChild(fillEl);
    pill.appendChild(knobEl);
    pill.appendChild(labelEl);
    pill.appendChild(labelOnEl);
    el.appendChild(pill);
    inst.pill = pill;

    var holding = false, t0 = 0, rafId = 0, sx = 0, sy = 0, retryTimer = 0;

    function setLabel(t) { labelEl.textContent = t; labelOnEl.textContent = t; }
    function setProgress(p) { pill.style.setProperty('--p', p); }
    function resetGesture() {
      holding = false;
      caf(rafId);
      pill.classList.remove('xl-dw-active');
      setProgress(0);
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
    scan();
    log('已启动', 'autoConfirm=' + CONFIG.autoConfirm, 'mode=' + CONFIG.mode);
  }

  function dispose() {
    disposed = true;
    observer.disconnect();
    winEvents.forEach(function (n) { window.removeEventListener(n, schedule); });
    document.removeEventListener('visibilitychange', onVisible);
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
