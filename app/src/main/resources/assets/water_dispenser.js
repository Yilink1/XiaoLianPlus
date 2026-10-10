/*
 * XiaoLianPlus · 饮水机增强脚本 v2.6.0 (架构闭环与原生并列视图隔离版)
 *
 * 核心架构七大铁律：
 * 1. AlipayJSBridge 网关级守卫：Hook 通信底座，点星后 800ms 内丢弃任何由手势衍生的 selectDevice 消息，100% 物理绝缘穿透。
 * 2. window.__xl_global_last_touch_time 全局幽灵点击锁：彻底杜绝卡片重排后浏览器 300ms 延迟合成 click 砸向替补卡片的连带触发 Bug。
 * 3. 动态无闭包取名：点击星标时现场动态向上查找 DOM 提取 nameOf(card)，绝不闭包绑定，切楼层/楼栋零串号。
 * 4. 并行独立全收藏视图 (#xl-fav-native-view)：全收藏模式下完全隐藏原版列表，在独立并列容器内渲染收藏机位，严禁向 React 列表强插假卡片，彻底根除无限克隆与 React 协调死循环。
 * 5. 楼栋标题 100% 原生纯净保留：绝不改动 .building DOM 节点，完全由官方小程序原生接管楼栋展示与切换。
 * 6. 扫码悬浮盾与安全留白：强制 .scan-tab 为 z-index: 99999，并在列表末尾注入 order: 999999 的 120px 隔离块，彻底防止卡片与悬浮球重叠。
 * 7. 像素级原生卡片与排版：左侧还原官方基准线 (padding-left: unset, margin-right: unset)，右侧微调 10px 避让星标，图标 flex-shrink: 0 锁死比例。
 */
(function () {
  'use strict';

  if (window.__XL_WD_V26__) {
    if (typeof window.__xl_refresh__ === 'function') window.__xl_refresh__();
    return;
  }
  window.__XL_WD_V26__ = true;
  window.__XL_WATER_DISPENSER_INJECTED__ = true;

  console.log('[XiaoLianPlus] Water dispenser v2.6.0 loaded.');

  // 调试条开关。约定：Java 侧把模块设置里的「调试条」开关写进 window.__XL_CONFIG__.debugHud（布尔），脚本注入前设置好即可。
  // FORCE_DEBUG 仅在排查期强制开启，排查结束改成 false，之后完全由模块开关控制。
  const FORCE_DEBUG = false;
  const XL_DEBUG = FORCE_DEBUG || !!(window.__XL_CONFIG__ && window.__XL_CONFIG__.debugHud);
  let xlLog = function () {};
  let xlOnToggle = function () {};

  // ───────── 1. 网关级通信拦截守卫 (100% 物理绝缘穿透) ─────────
  function hookBridge() {
    if (window.__xl_bridge_hooked) return;
    if (window.AlipayJSBridge && window.AlipayJSBridge.call) {
      const origBridgeCall = window.AlipayJSBridge.call;
      window.AlipayJSBridge.call = function (func, param, cb) {
        if (func === 'postMessage' && param && param.data) {
          try {
            const str = typeof param.data === 'string' ? param.data : JSON.stringify(param.data);
            if (XL_DEBUG) {
              const m = str.match(/"a"\s*:\s*\[\s*"([^"]+)"/);
              xlLog('bridge> ' + (m ? m[1] : str.slice(0, 40)));
            }
            if (str.includes('"toSelectBuilding"')) {
              // 补发后 1.2 秒内，渲染层迟到的原始消息丢弃，避免重复打开选楼页
              if (!window.__xl_replaying && Date.now() - (window.__xl_replay_time || 0) < 1200) {
                xlLog('drop late toSelectBuilding');
                return;
              }
              window.__xl_sb_time = Date.now();
              try { window.__xl_sb_param = JSON.parse(JSON.stringify(param)); } catch (e) {}
            }
            if (str.includes('"selectDevice"')) {
              const timeDiff = Date.now() - (window.__xl_last_star_tap_time || 0);
              if (timeDiff < 800) {
                console.log('[XiaoLianPlus] 🛡️ 网关拦截成功，彻底丢弃穿透请求！距离点星:', timeDiff, 'ms');
                return;
              }
            }
          } catch (e) {}
        }
        return origBridgeCall.apply(this, arguments);
      };
      window.__xl_bridge_hooked = true;
      console.log('[XiaoLianPlus] 🛡️ AlipayJSBridge 网关守卫已就绪');
    }
  }
  hookBridge();
  document.addEventListener('AlipayJSBridgeReady', hookBridge);
  const bridgeTimer = setInterval(() => {
    hookBridge();
    if (window.__xl_bridge_hooked) clearInterval(bridgeTimer);
  }, 100);

  // ───────── 2. 存储与基础工具 ─────────
  const FAV_KEY = 'xl_fav_list';
  const ALIAS_KEY = 'xl_alias_map_v4';
  const RECORD_CACHE_KEY = 'xl_device_records_cache';

  const getFavs = () => { try { return JSON.parse(localStorage.getItem(FAV_KEY) || '[]'); } catch (e) { return []; } };
  const setFavs = (l) => localStorage.setItem(FAV_KEY, JSON.stringify(l));
  const getAliases = () => { try { return JSON.parse(localStorage.getItem(ALIAS_KEY) || '{}'); } catch (e) { return {}; } };
  const setAliases = (m) => localStorage.setItem(ALIAS_KEY, JSON.stringify(m));
  const getCachedRecords = () => { try { return JSON.parse(localStorage.getItem(RECORD_CACHE_KEY) || '{}'); } catch (e) { return {}; } };
  const saveCachedRecords = (c) => localStorage.setItem(RECORD_CACHE_KEY, JSON.stringify(c));

  // 特征隔离：真饮水机卡片必须具备 .single-left 和 .single-right
  function isDispenserCard(el) {
    return !!(el && el.classList && el.classList.contains('single') &&
      el.querySelector('.single-left') && el.querySelector('.single-right'));
  }

  // 读取原机位名（剔除备注 span）
  function nameOf(card) {
    if (!card) return '';
    const rawAttr = card.getAttribute('data-raw');
    if (rawAttr) return rawAttr;
    const t = card.querySelector('.single-title');
    if (!t) return '';
    const c = t.cloneNode(true);
    c.querySelectorAll('.xl-alias').forEach((x) => x.remove());
    return (c.textContent || '').trim().split('\n')[0].trim();
  }

  // 全局防幽灵点击与点星时间戳
  window.__xl_global_last_touch_time = 0;
  window.__xl_last_star_tap_time = 0;

  // ───────── 3. 样式注入 ─────────
  function ensureStyle() {
    if (document.getElementById('xl-custom-style')) return;
    const st = document.createElement('style');
    st.id = 'xl-custom-style';
    st.textContent = `
      .xl-aliased { font-size: 0 !important; }
      .xl-aliased .xl-alias {
        font-size: 0.32rem !important;
        line-height: 19.66px !important;
        font-weight: 500 !important;
        display: inline-flex !important;
        align-items: center !important;
      }
      .xl-alias-main {
        font-size: 0.32rem !important;
        line-height: 19.66px !important;
        font-weight: 500 !important;
      }
      .xl-alias-raw {
        font-size: 0.24rem !important;
        color: #8c8c8c !important;
        font-weight: normal !important;
        margin-left: 0.12rem !important;
        line-height: 19.66px !important;
      }

      /* 扫码悬浮盾：强制提升至最高图层，永远浮在最上层，绝不被卡片或星标盖过 */
      .scan-tab {
        z-index: 99999 !important;
      }

      /* 像素级原生卡片与排版：左侧还原官方基准线，右侧微调 10px 避让星标 */
      div.single {
        padding-left: unset !important;
        padding-right: 10px !important;
      }
      .single-left .img {
        margin-right: unset !important;
        flex-shrink: 0 !important;
      }
      .single-right {
        gap: 2px !important;
      }

      /* 星标系统：经典饱满尺寸，z-index: 1 绝不盖过扫码球；order: 9999 永远锁死在最右 */
      .xl-card-star {
        width: 44px;
        height: 44px;
        display: inline-flex;
        align-items: center;
        justify-content: center;
        font-size: 26px !important;
        line-height: 1;
        cursor: pointer;
        user-select: none;
        -webkit-user-select: none;
        -webkit-tap-highlight-color: transparent;
        touch-action: manipulation;
        transition: transform 0.15s ease, color 0.15s ease;
        z-index: 1 !important;
        flex-shrink: 0;
        order: 9999 !important;
        margin-left: 2px !important;
      }
      .xl-card-star:active {
        transform: scale(1.3);
      }
      .xl-card-star.is-fav {
        color: #f5a623 !important;
      }
      .xl-card-star.not-fav {
        color: #c0c4cc !important;
      }

      /* 顶部【★ 全部收藏】胶囊按钮 */
      #xl-top-fav-btn {
        position: absolute; z-index: 100;
        background: rgba(255, 255, 255, 0.94);
        box-shadow: 0 2px 10px rgba(0, 0, 0, 0.16);
        border-radius: 14px; padding: 0 12px; height: 28px;
        font-size: 13px; font-weight: 600; color: #1082ff;
        display: none; align-items: center; gap: 5px;
        cursor: pointer; -webkit-tap-highlight-color: transparent;
        backdrop-filter: blur(6px); -webkit-backdrop-filter: blur(6px);
      }
      #xl-top-fav-btn span.badge {
        background: #1082ff; color: #fff; font-size: 11px; padding: 1px 6px; border-radius: 10px; line-height: 1.2;
      }

      /* 全收藏模式：隐藏原版列表，显示并行独立全收藏视图 */
      body.xl-in-fav-mode .scroll-container:not(#xl-fav-native-view),
      body.xl-in-fav-mode .mescroll-wxs-content > *:not(#xl-fav-native-view),
      body.xl-in-fav-mode div.single.xl-native-card {
        display: none !important;
      }
      #xl-fav-native-view {
        display: flex;
        flex-direction: column;
        width: 100%;
        height: auto;
        box-sizing: border-box;
      }

      /* 全收藏模式：左上角楼栋名「覆盖层」方案
         原生 .building 节点只做 opacity:0（不改 pointer-events / visibility / font-size / display），
         由独立的悬浮层显示「★ 全部收藏」并吞掉点击。退出时仅隐藏悬浮层 + 恢复 opacity，
         原生节点的命中状态全程未被改动，因此能立即点击。 */
      body.xl-in-fav-mode .xl-building-hidden {
        opacity: 0 !important;
      }
      #xl-fav-title-cover {
        position: fixed;
        z-index: 100;
        display: none;
        align-items: center;
        color: #ffffff;
        font-weight: bold;
        letter-spacing: 0.5px;
        white-space: nowrap;
        pointer-events: auto;
        -webkit-tap-highlight-color: transparent;
      }
    `;
    (document.head || document.documentElement).appendChild(st);
  }

  // ───────── 4. 底部安全避让隔离块 ─────────
  function ensureBottomSpacer(targetContainer) {
    const container = targetContainer || document.querySelector('.scroll-container') || document.querySelector('#xl-fav-native-view')?.parentElement;
    if (container && !container.querySelector('#xl-scroll-bottom-spacer')) {
      const spacer = document.createElement('div');
      spacer.id = 'xl-scroll-bottom-spacer';
      spacer.style.cssText = 'height: 64px !important; width: 100% !important; flex-shrink: 0 !important; pointer-events: none !important; order: 999999 !important;';
      container.appendChild(spacer);
    }
  }

  // ───────── 5. 备注名追加 ─────────
  function applyAlias(card, raw, data) {
    const t = card.querySelector('.single-title');
    if (!t) return;
    let span = Array.from(t.children).find((c) => c.classList && c.classList.contains('xl-alias'));
    const hasColor = !!(data && data.color && data.color !== 'default');
    const hasAlias = !!(data && data.name);
    if (!hasColor && !hasAlias) {
      if (span) span.remove();
      if (t.classList.contains('xl-aliased')) t.classList.remove('xl-aliased');
      return;
    }
    const sig = [data.name || '', data.color || '', data.showRaw ? 1 : 0, raw].join('|');
    if (!span) { span = document.createElement('span'); span.className = 'xl-alias'; t.appendChild(span); }
    if (span.dataset.sig !== sig) {
      span.dataset.sig = sig;
      span.textContent = '';
      const main = document.createElement('span');
      main.className = 'xl-alias-main';
      main.textContent = hasAlias ? data.name : raw;
      if (hasColor) { main.style.color = data.color; main.style.fontWeight = '600'; }
      span.appendChild(main);
      if (hasAlias && data.showRaw) {
        const r = document.createElement('span');
        r.className = 'xl-alias-raw';
        r.textContent = '(' + raw + ')';
        span.appendChild(r);
      }
    }
    if (!t.classList.contains('xl-aliased')) t.classList.add('xl-aliased');
  }

  // ───────── 6. 卡片长按备注 ─────────
  function bindCardLongPress(card, customRaw) {
    if (card.dataset.xlBound === '1') return;
    card.dataset.xlBound = '1';
    let timer = null, isLong = false, sx = 0, sy = 0;

    card.addEventListener('touchstart', (e) => {
      const t = e.touches && e.touches[0];
      if (!t) return;
      sx = t.clientX; sy = t.clientY; isLong = false;
      clearTimeout(timer);
      timer = setTimeout(() => {
        isLong = true;
        const raw = customRaw || card.getAttribute('data-raw') || nameOf(card);
        if (raw && typeof window.__xl_openSheet__ === 'function') window.__xl_openSheet__(raw);
      }, 380);
    }, { capture: true, passive: true });

    card.addEventListener('touchmove', (e) => {
      const t = e.touches && e.touches[0];
      if (!t) return;
      if (Math.hypot(t.clientX - sx, t.clientY - sy) > 12) {
        clearTimeout(timer);
      }
    }, { capture: true, passive: true });

    card.addEventListener('touchend', (e) => {
      clearTimeout(timer);
      if (isLong) {
        e.stopPropagation();
        e.stopImmediatePropagation();
        if (e.cancelable) e.preventDefault();
      }
    }, { capture: true, passive: false });
  }

  // ───────── 7. 原生内嵌星标系统（全局幽灵锁 + 动态取词无死锁） ─────────
  function attachInlineStar(card) {
    const sr = card.querySelector('.single-right');
    if (!sr) return;

    let star = sr.querySelector('.xl-card-star');
    if (!star || !star.dataset.xlBound) {
      if (star) star.remove();
      star = document.createElement('div');
      star.className = 'xl-card-star';
      star.dataset.xlBound = '1';

      let sx = 0, sy = 0, st = 0, moved = false;

      star.addEventListener('touchstart', (e) => {
        e.stopPropagation();
        e.stopImmediatePropagation();
        const t = e.touches[0];
        if (!t) return;
        sx = t.clientX; sy = t.clientY; st = Date.now(); moved = false;
        window.__xl_last_star_tap_time = Date.now();
        window.__xl_global_last_touch_time = Date.now();
      }, { capture: true, passive: true });

      star.addEventListener('touchmove', (e) => {
        const t = e.touches[0];
        if (!t) return;
        if (Math.hypot(t.clientX - sx, t.clientY - sy) > 10) moved = true;
      }, { passive: true });

      star.addEventListener('touchend', (e) => {
        e.stopPropagation();
        e.stopImmediatePropagation();
        if (e.cancelable) e.preventDefault();
        if (!moved && Date.now() - st < 500) {
          window.__xl_last_star_tap_time = Date.now();
          window.__xl_global_last_touch_time = Date.now();
          onStarTrigger(e, star);
        }
      }, { capture: true, passive: false });

      star.addEventListener('click', (e) => {
        e.stopPropagation();
        e.stopImmediatePropagation();
        if (e.cancelable) e.preventDefault();
        if (Date.now() - (window.__xl_global_last_touch_time || 0) < 800) {
          return;
        }
        window.__xl_last_star_tap_time = Date.now();
        onStarTrigger(e, star);
      }, { capture: true });

      sr.appendChild(star);
    }

    const currentRaw = nameOf(card);
    const favs = getFavs();
    const isFav = favs.includes(currentRaw);
    star.textContent = isFav ? '★' : '☆';
    star.className = 'xl-card-star ' + (isFav ? 'is-fav' : 'not-fav');
  }

  function onStarTrigger(e, star) {
    const card = star.closest('div.single');
    const raw = nameOf(card);
    if (!raw) return;
    toggleFav(raw, star);
  }

  function toggleFav(raw, star) {
    let list = getFavs();
    const has = list.includes(raw);
    list = has ? list.filter(x => x !== raw) : list.concat(raw);
    setFavs(list);

    star.textContent = !has ? '★' : '☆';
    star.className = 'xl-card-star ' + (!has ? 'is-fav' : 'not-fav');
    console.log('[XiaoLianPlus] 水机【' + raw + '】收藏状态变更为:', !has ? '★ 已收藏' : '☆ 未收藏');

    pass();
  }

  // ───────── 8. 机位参数采集与秒级直达打水 ─────────
  function harvestRecords() {
    const cache = getCachedRecords();
    let changed = false;
    document.querySelectorAll('div.single').forEach(card => {
      if (card.closest('#xl-fav-native-view')) return;
      const rk = Object.keys(card).find(k => k.startsWith('__reactFiber') || k.startsWith('__reactInternalInstance'));
      const fiber = card[rk];
      const rec = fiber?.memoizedProps?.['data-record'] || fiber?.return?.memoizedProps?.['data-record'] || card.dataset.record;
      if (rec && rec.deviceName) {
        if (!cache[rec.deviceName] || cache[rec.deviceName].id !== rec.id) {
          cache[rec.deviceName] = rec;
          changed = true;
        }
      }
    });
    if (changed) saveCachedRecords(cache);
  }

  function jumpToDrinkWater(record) {
    if (!window.AlipayJSBridge) {
      alert('底层通讯通道未就绪，请稍后重试');
      return;
    }
    window.AlipayJSBridge.call('postMessage', {
      type: 'messagePort',
      msgPortId: 2,
      data: JSON.stringify({
        data: {
          c: 'page',
          m: 'onRenderEvent',
          a: ['selectDevice', {
            type: 'tap',
            target: { dataset: { record: record } },
            currentTarget: { dataset: { record: record } }
          }]
        }
      })
    });
  }

  // ───────── 9. 并行独立全收藏视图渲染 (#xl-fav-native-view) ─────────
  let isFavMode = false;

  function renderFavNativeView(templateCard) {
    const origScroll = document.querySelector('.scroll-container:not(#xl-fav-native-view)')
      || (templateCard && templateCard.parentElement);
    if (!origScroll || !origScroll.parentElement) return;

    let favView = document.getElementById('xl-fav-native-view');
    if (!favView) {
      favView = document.createElement('div');
      favView.id = 'xl-fav-native-view';
      favView.className = 'scroll-container xl-fav-parallel-container';
      origScroll.parentElement.appendChild(favView);
    }

    const favs = getFavs();
    const records = getCachedRecords();
    const aliases = getAliases();

    favView.innerHTML = '';

    if (favs.length === 0) {
      const emptyTip = document.createElement('div');
      emptyTip.style.cssText = 'text-align: center; color: #8c8c8c; font-size: 14px; padding: 60px 20px;';
      emptyTip.innerHTML = '暂无收藏的水机<br><span style="font-size:12px;color:#bfbfbf;margin-top:8px;display:inline-block;">点击卡片右侧 ☆ 星标即可加入收藏</span>';
      favView.appendChild(emptyTip);
    } else {
      favs.forEach((rawName) => {
        const card = templateCard.cloneNode(true);
        // 清除旧克隆节点残留，确保全新绑定
        card.querySelectorAll('.xl-card-star, .xl-alias').forEach(n => n.remove());
        delete card.dataset.xlBound;
        card.classList.remove('xl-native-card');

        card.setAttribute('data-raw', rawName);
        card.setAttribute('data-xl', '1');
        card.style.display = 'flex';

        // 标题与备注
        const titleEl = card.querySelector('.single-title');
        if (titleEl) {
          titleEl.textContent = rawName;
        }
        applyAlias(card, rawName, aliases[rawName]);

        // 卡片点击直达打水 (守卫防线：严格屏蔽点星命中与幽灵延迟)
        card.onclick = (e) => {
          if (e.target && e.target.closest && e.target.closest('.xl-card-star')) {
            e.stopPropagation();
            return;
          }
          if (Date.now() - (window.__xl_last_star_tap_time || 0) < 800) return;
          if (Date.now() - (window.__xl_global_last_touch_time || 0) < 800) return;

          const rec = records[rawName];
          if (rec) {
            jumpToDrinkWater(rec);
          } else {
            alert(`【${rawName}】是跨楼栋水机，由于首次使用尚未缓存机位参数，请先切换到该楼栋浏览一次，后续即可永久免切换直达！`);
          }
        };

        // 长按设置备注
        bindCardLongPress(card, rawName);

        // 绑定星标 (带事件全新绑定)
        attachInlineStar(card);

        favView.appendChild(card);
      });
    }

    // 独立容器末尾安全留白
    ensureBottomSpacer(favView);
  }

  // ───────── 9.5 左上角楼栋名覆盖层（全收藏模式显示「★ 全部收藏」，不可点击） ─────────
  function updateBuildingCover() {
    const building = document.querySelector('.header-title .building')
      || document.querySelector('.building')
      || document.querySelector('.container-head_box_location');
    let cover = document.getElementById('xl-fav-title-cover');

    // 非全收藏模式：恢复原生节点，隐藏覆盖层
    if (!isFavMode || !building) {
      document.querySelectorAll('.xl-building-hidden').forEach(n => n.classList.remove('xl-building-hidden'));
      if (cover) cover.style.display = 'none';
      return;
    }

    building.classList.add('xl-building-hidden');

    if (!cover) {
      cover = document.createElement('div');
      cover.id = 'xl-fav-title-cover';
      cover.textContent = '★ 全部收藏';
      const swallow = (e) => {
        e.stopPropagation();
        if (e.cancelable) e.preventDefault();
      };
      ['click', 'touchstart', 'touchend'].forEach(t => cover.addEventListener(t, swallow, { capture: true, passive: false }));
      document.body.appendChild(cover);
    }

    const rect = building.getBoundingClientRect();
    if (rect.width === 0 && rect.height === 0) {
      cover.style.display = 'none';
      return;
    }
    const cs = getComputedStyle(building);
    cover.style.left = rect.left + 'px';
    cover.style.top = rect.top + 'px';
    cover.style.height = rect.height + 'px';
    cover.style.minWidth = rect.width + 'px';
    cover.style.fontSize = '0.4rem';
    cover.style.lineHeight = rect.height + 'px';
    cover.style.fontFamily = cs.fontFamily;
    cover.style.display = 'flex';
  }

  // ───────── 9.6 楼栋名点击兜底 ─────────
  // 实测现象：点「✕ 全部水机」后约 1 秒内点楼栋名，页面能收到 touchstart/touchend/click，
  // 但渲染层不再向逻辑层发 toSelectBuilding（正常点击是 click 后 1ms 内就发）。
  // 根因在小程序渲染层，脚本侧无法直接修；这里检测「点击落在楼栋名上、200ms 内却没有 toSelectBuilding 发出」，
  // 用最近一次真实消息（没有则按 jumpToDrinkWater 同款格式构造）补发一次。
  function getBuildingEl() {
    return document.querySelector('.header-title .building')
      || document.querySelector('.building')
      || document.querySelector('.container-head_box_location');
  }

  function replayToSelectBuilding() {
    if (!window.AlipayJSBridge) return;
    let param = null;
    try { param = window.__xl_sb_param ? JSON.parse(JSON.stringify(window.__xl_sb_param)) : null; } catch (e) {}
    if (!param) {
      param = {
        type: 'messagePort',
        msgPortId: 2,
        data: JSON.stringify({
          data: {
            c: 'page',
            m: 'onRenderEvent',
            a: ['toSelectBuilding', { type: 'tap', target: { dataset: {} }, currentTarget: { dataset: {} } }]
          }
        })
      };
    }
    window.__xl_replay_time = Date.now();
    window.__xl_replaying = true;
    try { window.AlipayJSBridge.call('postMessage', param); } finally { window.__xl_replaying = false; }
    xlLog('replay toSelectBuilding');
  }

  document.addEventListener('click', (e) => {
    if (isFavMode) return; // 全收藏模式下标题是不可点击的覆盖层
    const b = getBuildingEl();
    if (!b) return;
    const r = b.getBoundingClientRect();
    const pad = 12;
    const inside = (e.target && e.target.closest && e.target.closest('.building'))
      || (e.clientX >= r.left - pad && e.clientX <= r.right + pad && e.clientY >= r.top - pad && e.clientY <= r.bottom + pad);
    if (!inside) return;
    const clickedAt = Date.now();
    setTimeout(() => {
      if ((window.__xl_sb_time || 0) >= clickedAt) return; // 渲染层已正常发出
      replayToSelectBuilding();
    }, 200);
  }, true);

  // ───────── 10. 顶部【★ 全部收藏】胶囊按钮 ─────────
  function updateTopFavButton() {
    const pwdBtn = document.querySelector('.l-button.setPassBtn') || document.querySelector('.setPassBtn') || document.querySelector('.set_password');
    let btn = document.getElementById('xl-top-fav-btn');
    if (!pwdBtn) {
      if (btn) btn.style.display = 'none';
      return;
    }

    if (!btn) {
      btn = document.createElement('div');
      btn.id = 'xl-top-fav-btn';
      document.body.appendChild(btn);

      btn.addEventListener('click', (e) => {
        e.stopPropagation();
        isFavMode = !isFavMode;
        pass();
        xlOnToggle();
      });
    }

    const rect = pwdBtn.getBoundingClientRect();
    const favCount = getFavs().length;

    if (isFavMode) {
      btn.innerHTML = `<span>✕ 全部水机</span><span class="badge" style="background:#52c41a">${favCount}</span>`;
    } else {
      btn.innerHTML = `<span>★ 全部收藏</span><span class="badge">${favCount}</span>`;
    }

    // 联网饮水机向下平移 34px 对齐服务时间行；公共饮水机无服务时间行，上移一点点（约 12px）避让下方卡片
    const isPublicRoom = location.href.includes('publicRoom') || location.hash.includes('publicRoom');
    const offsetY = isPublicRoom ? 12 : 34;
    btn.style.top = (rect.bottom + window.scrollY + offsetY) + 'px';
    btn.style.right = (document.documentElement.clientWidth - rect.right) + 'px';
    btn.style.display = rect.width > 0 ? 'flex' : 'none';
  }

  // ───────── 11. 底部抽屉面板 (改备注与高亮色，5 种经典配色) ─────────
  let sheetInitialized = false;
  let activeTargetRawName = '';
  let selectedColor = 'default';

  function initSheet() {
    if (sheetInitialized || document.getElementById('modern-sheet-root')) return;
    sheetInitialized = true;

    const sheetRoot = document.createElement('div');
    sheetRoot.id = 'modern-sheet-root';
    sheetRoot.innerHTML = `
      <div id="sheet-backdrop" style="
        position: fixed; top: 0; left: 0; width: 100vw; height: 100vh;
        background: rgba(0, 0, 0, 0.45); z-index: 999998;
        opacity: 0; pointer-events: none; transition: opacity 0.25s ease;
      "></div>
      <div id="sheet-panel" style="
        position: fixed; left: 0; right: 0; bottom: -100%; max-height: 80vh; overflow-y: auto;
        background: #ffffff; border-radius: 20px 20px 0 0;
        box-shadow: 0 -8px 30px rgba(0,0,0,0.18); z-index: 999999;
        padding: 16px 20px 32px 20px; font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
        transition: bottom 0.28s cubic-bezier(0.16, 1, 0.3, 1);
      ">
        <div style="width: 36px; height: 4px; background: #e0e0e0; border-radius: 2px; margin: 0 auto 16px auto;"></div>
        <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px;">
          <span style="font-size: 16px; font-weight: 700; color: #1f1f1f;">饮水机个性化设置</span>
          <span id="sheet-raw-tag" style="font-size: 11px; color: #8c8c8c; max-width: 150px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;"></span>
        </div>
        <div style="margin-bottom: 18px;">
          <div style="font-size: 12px; color: #595959; margin-bottom: 6px; font-weight: 500;">自定义备注 (选填，留空则保持原名)</div>
          <input id="sheet-input-alias" type="text" style="
            width: 100%; height: 42px; padding: 0 12px; border-radius: 10px;
            border: 1px solid #d9d9d9; font-size: 14px; outline: none; box-sizing: border-box;
          " />
        </div>
        <div id="box-toggle-raw" style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 18px; padding: 4px 0;">
          <div>
            <div style="font-size: 13px; color: #262626; font-weight: 500;">显示原名括号</div>
            <div style="font-size: 11px; color: #8c8c8c;">仅在设置了备注名时生效</div>
          </div>
          <label style="position: relative; display: inline-block; width: 44px; height: 24px;">
            <input id="sheet-toggle-raw" type="checkbox" style="opacity: 0; width: 0; height: 0;" />
            <span id="sheet-toggle-slider" style="
              position: absolute; cursor: pointer; top: 0; left: 0; right: 0; bottom: 0;
              background-color: #ccc; transition: .25s; border-radius: 24px;
            "></span>
          </label>
        </div>
        <div style="margin-bottom: 24px;">
          <div style="font-size: 12px; color: #595959; margin-bottom: 10px; font-weight: 500;">文字高亮色</div>
          <div id="sheet-palette" style="display: flex; gap: 12px; align-items: center;"></div>
        </div>
        <button id="sheet-save-btn" style="
          width: 100%; height: 44px; background: #1677ff; color: #ffffff;
          border: none; border-radius: 12px; font-size: 15px; font-weight: 600; cursor: pointer;
        ">保存设置</button>
      </div>
    `;
    (document.body || document.documentElement).appendChild(sheetRoot);

    const styleEl = document.createElement('style');
    styleEl.textContent = `
      #sheet-toggle-raw:checked + #sheet-toggle-slider { background-color: #1677ff; }
      #sheet-toggle-raw:checked + #sheet-toggle-slider:before { transform: translateX(20px); }
      #sheet-toggle-slider:before {
        position: absolute; content: ""; height: 18px; width: 18px; left: 3px; bottom: 3px;
        background-color: white; transition: .25s; border-radius: 50%;
      }
    `;
    document.head.appendChild(styleEl);

    const colors = [
      { name: '默认', val: 'default', bg: '#f2f3f5', border: '#d9d9d9' },
      { name: '科技蓝', val: '#1677ff', bg: '#1677ff' },
      { name: '极光绿', val: '#52c41a', bg: '#52c41a' },
      { name: '火山橙', val: '#fa541c', bg: '#fa541c' },
      { name: '极客紫', val: '#722ed1', bg: '#722ed1' }
    ];

    const paletteContainer = sheetRoot.querySelector('#sheet-palette');

    function updatePaletteUI() {
      paletteContainer.querySelectorAll('.color-dot').forEach(dot => {
        const val = dot.dataset.val;
        const isSelected = val === selectedColor;

        if (isSelected) {
          dot.style.outline = '3px solid #1f1f1f';
          dot.style.outlineOffset = '2px';
        } else {
          dot.style.outline = 'none';
        }

        if (val === 'default') {
          dot.innerHTML = `<span style="font-size: 11px; font-weight: ${isSelected ? '700' : 'normal'}; color: ${isSelected ? '#1f1f1f' : '#8c8c8c'};">默认</span>`;
        } else {
          dot.innerHTML = isSelected ? '<span style="color:#fff; font-size: 13px; font-weight:bold; line-height: 1;">✓</span>' : '';
        }
      });
    }

    paletteContainer.innerHTML = colors.map(c => `
      <div class="color-dot" data-val="${c.val}" style="
        width: 36px; height: 36px; border-radius: 50%; background: ${c.bg};
        ${c.border ? `border: 1px solid ${c.border};` : ''}
        cursor: pointer; display: flex; align-items: center; justify-content: center;
        box-sizing: border-box; transition: transform 0.15s ease;
        -webkit-tap-highlight-color: transparent;
      "></div>
    `).join('');

    paletteContainer.querySelectorAll('.color-dot').forEach(dot => {
      const handleSelect = (e) => {
        e.stopPropagation();
        e.preventDefault();
        selectedColor = dot.dataset.val;
        updatePaletteUI();
      };
      dot.addEventListener('click', handleSelect);
      dot.addEventListener('touchend', handleSelect);
    });

    const backdrop = sheetRoot.querySelector('#sheet-backdrop');
    const panel = sheetRoot.querySelector('#sheet-panel');
    const rawTag = sheetRoot.querySelector('#sheet-raw-tag');
    const inputAlias = sheetRoot.querySelector('#sheet-input-alias');
    const toggleRaw = sheetRoot.querySelector('#sheet-toggle-raw');
    const saveBtn = sheetRoot.querySelector('#sheet-save-btn');

    window.__xl_openSheet__ = function (raw) {
      activeTargetRawName = raw;
      rawTag.textContent = raw;
      const aliases = getAliases();
      const cur = aliases[raw] || {};
      inputAlias.value = cur.name || '';
      inputAlias.placeholder = raw;
      toggleRaw.checked = cur.showRaw !== false;
      selectedColor = cur.color || 'default';

      updatePaletteUI();

      backdrop.style.pointerEvents = 'auto';
      backdrop.style.opacity = '1';
      panel.style.transition = 'bottom 0.28s cubic-bezier(0.16, 1, 0.3, 1), transform 0.2s ease';
      panel.style.transform = 'translateY(0)';
      panel.style.bottom = '0';
    };

    function closeSheet() {
      backdrop.style.opacity = '0';
      backdrop.style.pointerEvents = 'none';
      panel.style.transition = 'bottom 0.24s ease, transform 0.24s ease';
      panel.style.transform = 'translateY(100%)';
      setTimeout(() => {
        panel.style.bottom = '-100%';
        panel.style.transform = '';
      }, 250);
    }

    backdrop.onclick = () => closeSheet();

    // 下滑手势滑动关闭 (Swipe-down to dismiss)
    let touchStartY = 0;
    let currentTranslateY = 0;
    let isDragging = false;

    panel.addEventListener('touchstart', (e) => {
      if (e.target === inputAlias) return; // 避免打字光标冲突
      touchStartY = e.touches[0].clientY;
      currentTranslateY = 0;
      isDragging = true;
      panel.style.transition = 'none';
    }, { passive: true });

    panel.addEventListener('touchmove', (e) => {
      if (!isDragging) return;
      const deltaY = e.touches[0].clientY - touchStartY;
      if (deltaY > 0) {
        currentTranslateY = deltaY;
        panel.style.transform = `translateY(${deltaY}px)`;
        const fade = Math.max(0, 1 - deltaY / 260);
        backdrop.style.opacity = `${fade}`;
      }
    }, { passive: true });

    const handleTouchEnd = () => {
      if (!isDragging) return;
      isDragging = false;
      panel.style.transition = 'transform 0.25s cubic-bezier(0.2, 0, 0, 1), bottom 0.25s ease';
      if (currentTranslateY > 75) {
        closeSheet();
      } else {
        panel.style.transform = 'translateY(0)';
        backdrop.style.opacity = '1';
      }
      currentTranslateY = 0;
    };

    panel.addEventListener('touchend', handleTouchEnd);
    panel.addEventListener('touchcancel', handleTouchEnd);

    saveBtn.onclick = () => {
      const aliases = getAliases();
      const newName = inputAlias.value.trim();

      if (!newName && selectedColor === 'default') {
        delete aliases[activeTargetRawName];
      } else {
        aliases[activeTargetRawName] = {
          name: newName,
          color: selectedColor,
          showRaw: toggleRaw.checked
        };
      }

      setAliases(aliases);
      pass();
      closeSheet();
    };
  }

  // ───────── 12. 主流程渲染 (防抖保护与全流程协同) ─────────
  let passing = false;
  let favModeChecked = false;

  function pass() {
    if (passing) return;
    passing = true;
    const passStart = XL_DEBUG ? performance.now() : 0;
    try {
      ensureStyle();
      initSheet();
      harvestRecords();

      // 1. 抓取真实饮水机卡片（排除全收藏克隆视图，支持所有合法列表容器）
      const nativeCards = Array.from(document.querySelectorAll('div.single'))
        .filter(c => !c.closest('#xl-fav-native-view') && isDispenserCard(c));

      if (nativeCards.length > 0) {
        const favs = getFavs(), aliases = getAliases();

        // 默认进入全部收藏策略检查（首屏只在有收藏且配置开启时自动激活一次）
        if (!favModeChecked) {
          favModeChecked = true;
          const cfg = window.__XL_CONFIG__ || {};
          if (cfg.defaultAllFav && favs.length > 0) {
            isFavMode = true;
          }
        }

        // 刷新原生卡片的基础能力（长按备注、星标绑定、别名）
        nativeCards.forEach((card, i) => {
          const raw = nameOf(card);
          if (card.dataset.xl !== '1') card.setAttribute('data-xl', '1');
          card.classList.add('xl-native-card');
          bindCardLongPress(card);
          applyAlias(card, raw, aliases[raw]);
          attachInlineStar(card);

          // 原生列表置顶已收藏水机
          const fi = favs.indexOf(raw);
          card.style.order = String(fi >= 0 ? (-1000 + fi) : i);
        });

        const nativeScroll = nativeCards[0]?.closest('.scroll-container') || nativeCards[0]?.parentElement;
        if (nativeScroll) {
          ensureBottomSpacer(nativeScroll);
          const d = getComputedStyle(nativeScroll).display;
          if (d === 'block' || d === 'flow-root') {
            nativeScroll.style.display = 'flex';
            nativeScroll.style.flexDirection = 'column';
          }
        }

        // 模式切换处理
        let favView = document.getElementById('xl-fav-native-view');
        if (isFavMode) {
          document.body.classList.add('xl-in-fav-mode');
          renderFavNativeView(nativeCards[0]);
          if (favView) favView.style.display = 'flex';
        } else {
          document.body.classList.remove('xl-in-fav-mode');
          if (favView) favView.style.display = 'none';
        }

        updateTopFavButton();
        updateBuildingCover();
      } else {
        const topBtn = document.getElementById('xl-top-fav-btn');
        if (topBtn) topBtn.style.display = 'none';
        const cv = document.getElementById('xl-fav-title-cover');
        if (cv) cv.style.display = 'none';
        document.querySelectorAll('.xl-building-hidden').forEach(n => n.classList.remove('xl-building-hidden'));
      }
    } catch (e) {
      console.warn('[XiaoLianPlus] pass error', e);
    } finally {
      passing = false;
      if (XL_DEBUG) {
        const cost = performance.now() - passStart;
        if (cost > 20) xlLog('pass ' + Math.round(cost) + 'ms fav=' + isFavMode);
      }
    }
  }
  window.__xl_refresh__ = pass;

  // 极速同帧调度：放弃 100ms 假等待，利用 requestAnimationFrame 在浏览器绘制同帧立即完成置顶与挂星
  let passFrame = null;
  const fastPass = () => {
    if (passFrame) cancelAnimationFrame(passFrame);
    passFrame = requestAnimationFrame(() => {
      passFrame = null;
      pass();
    });
  };

  const observer = new MutationObserver((muts) => {
    // 忽略我们自己注入的根节点变动
    const isOnlyOurs = muts.every(m => {
      const t = m.target;
      return t && t.closest && t.closest('#xl-custom-style,#xl-scroll-bottom-spacer,#xl-top-fav-btn,#modern-sheet-root,#xl-fav-native-view,#xl-fav-title-cover,#xl-debug-hud');
    });
    if (!isOnlyOurs) {
      fastPass();
    }
  });

  const rootTarget = document.body || document.documentElement;
  if (rootTarget) {
    observer.observe(rootTarget, { childList: true, subtree: true });
  }
  // ───────── 13. 调试条（屏幕左下角；pointer-events:none，不影响任何点击） ─────────
  if (XL_DEBUG) {
    const t0 = Date.now();
    const rows = [];
    let hud = null;
    const stamp = () => ((Date.now() - t0) / 1000).toFixed(2);
    const desc = (el) => {
      if (!el || !el.tagName) return String(el);
      let s = el.tagName.toLowerCase();
      if (el.id) s += '#' + el.id;
      const cls = el.classList ? Array.from(el.classList).slice(0, 2).join('.') : '';
      return cls ? s + '.' + cls : s;
    };
    xlLog = function (msg) {
      rows.push(stamp() + ' ' + msg);
      if (rows.length > 16) rows.shift();
      if (!hud || !hud.isConnected) {
        hud = document.createElement('div');
        hud.id = 'xl-debug-hud';
        hud.style.cssText = 'position:fixed;left:0;bottom:0;z-index:2147483647;max-width:100vw;'
          + 'background:rgba(0,0,0,.72);color:#9f9;font:10px/1.35 monospace;padding:3px 5px;'
          + 'pointer-events:none;white-space:pre-wrap;word-break:break-all;';
        (document.body || document.documentElement).appendChild(hud);
      }
      hud.textContent = rows.join('\n');
    };
    const findBuilding = () => document.querySelector('.header-title .building')
      || document.querySelector('.building')
      || document.querySelector('.container-head_box_location');
    // 探针：楼栋名中心点此刻命中的是谁、pointer-events 与 opacity 是什么
    const probe = (tag) => {
      const b = findBuilding();
      if (!b) { xlLog(tag + ' building=null'); return; }
      const r = b.getBoundingClientRect();
      const hit = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
      const cs = getComputedStyle(b);
      xlLog(tag + ' hit=' + desc(hit) + ' ok=' + (hit === b || b.contains(hit)) + ' pe=' + cs.pointerEvents + ' op=' + cs.opacity);
    };
    xlOnToggle = function () {
      xlLog('fav mode -> ' + isFavMode);
      [0, 150, 400, 700, 1000, 1500].forEach((d) => setTimeout(() => probe((isFavMode ? 'in' : 'out') + '+' + d), d));
    };
    ['touchstart', 'touchend', 'click'].forEach((type) => {
      document.addEventListener(type, (e) => {
        const p = (e.changedTouches && e.changedTouches[0]) || e;
        xlLog(type + ' ' + desc(e.target) + (type === 'touchstart' ? ' @' + Math.round(p.clientX) + ',' + Math.round(p.clientY) : ''));
      }, true);
    });
    try {
      new PerformanceObserver((l) => l.getEntries().forEach((e) => xlLog('longtask ' + Math.round(e.duration) + 'ms')))
        .observe({ entryTypes: ['longtask'] });
    } catch (e) {}
    xlLog('debug on');
  }

  setInterval(pass, 1500);
  pass();
})();
