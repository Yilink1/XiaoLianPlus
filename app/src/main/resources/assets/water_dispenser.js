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

  // ───────── 1. 网关级通信拦截守卫 (100% 物理绝缘穿透) ─────────
  function hookBridge() {
    if (window.__xl_bridge_hooked) return;
    if (window.AlipayJSBridge && window.AlipayJSBridge.call) {
      const origBridgeCall = window.AlipayJSBridge.call;
      window.AlipayJSBridge.call = function (func, param, cb) {
        if (func === 'postMessage' && param && param.data) {
          try {
            const str = typeof param.data === 'string' ? param.data : JSON.stringify(param.data);
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
      div.single[data-xl="1"] { flex-shrink: 0 !important; }
      .xl-aliased { font-size: 0 !important; }

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
      body.xl-in-fav-mode .scroll-container:not(#xl-fav-native-view) {
        display: none !important;
      }
      #xl-fav-native-view {
        display: flex;
        flex-direction: column;
        width: 100%;
        height: auto;
        box-sizing: border-box;
      }
    `;
    (document.head || document.documentElement).appendChild(st);
  }

  // ───────── 4. 底部安全避让隔离块 ─────────
  function ensureBottomSpacer(targetContainer) {
    const container = targetContainer || document.querySelector('.scroll-container');
    if (container && !container.querySelector('#xl-scroll-bottom-spacer')) {
      const spacer = document.createElement('div');
      spacer.id = 'xl-scroll-bottom-spacer';
      spacer.style.cssText = 'height: 120px !important; width: 100% !important; flex-shrink: 0 !important; pointer-events: none !important; order: 999999 !important;';
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
    if (!t.dataset.xlFs && !t.classList.contains('xl-aliased')) {
      t.dataset.xlFs = String(parseFloat(getComputedStyle(t).fontSize) || 14);
    }
    const fs = parseFloat(t.dataset.xlFs) || 14;
    const sig = [data.name || '', data.color || '', data.showRaw ? 1 : 0, fs, raw].join('|');
    if (!span) { span = document.createElement('span'); span.className = 'xl-alias'; t.appendChild(span); }
    if (span.dataset.sig !== sig) {
      span.dataset.sig = sig;
      span.textContent = '';
      const main = document.createElement('span');
      main.textContent = hasAlias ? data.name : raw;
      main.style.fontSize = fs + 'px';
      if (hasColor) { main.style.color = data.color; main.style.fontWeight = '600'; }
      span.appendChild(main);
      if (hasAlias && data.showRaw) {
        const r = document.createElement('span');
        r.textContent = '(' + raw + ')';
        r.style.cssText = 'font-size:11px;color:#8c8c8c;font-weight:normal;margin-left:6px;';
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
    document.querySelectorAll('.scroll-container:not(#xl-fav-native-view) div.single').forEach(card => {
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
    const origScroll = document.querySelector('.scroll-container:not(#xl-fav-native-view)');
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

  // ───────── 10. 顶部【★ 全部收藏】胶囊按钮 ─────────
  function updateTopFavButton() {
    const pwdBtn = document.querySelector('.l-button.setPassBtn') || document.querySelector('.setPassBtn');
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
      });
    }

    const rect = pwdBtn.getBoundingClientRect();
    const favCount = getFavs().length;

    if (isFavMode) {
      btn.innerHTML = `<span>✕ 全部水机</span><span class="badge" style="background:#52c41a">${favCount}</span>`;
    } else {
      btn.innerHTML = `<span>★ 全部收藏</span><span class="badge">${favCount}</span>`;
    }

    // 精确向下平移约 34px：避开第二行商家名称，对齐第三行服务时间右侧纯净区域
    btn.style.top = (rect.bottom + window.scrollY + 34) + 'px';
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
          <div style="font-size: 12px; color: #595959; margin-bottom: 8px; font-weight: 500;">字体高亮颜色</div>
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
      .color-dot {
        width: 28px; height: 28px; border-radius: 50%; cursor: pointer;
        display: flex; align-items: center; justify-content: center;
        border: 2px solid transparent; transition: transform 0.15s ease;
      }
      .color-dot.active { border-color: #1677ff; transform: scale(1.15); }
      .color-dot-inner { width: 18px; height: 18px; border-radius: 50%; }
    `;
    document.head.appendChild(styleEl);

    const colors = [
      { key: 'default', color: '#8c8c8c' },
      { key: '#ff4d4f', color: '#ff4d4f' },
      { key: '#1677ff', color: '#1677ff' },
      { key: '#52c41a', color: '#52c41a' },
      { key: '#722ed1', color: '#722ed1' },
      { key: '#fa8c16', color: '#fa8c16' }
    ];

    const palette = sheetRoot.querySelector('#sheet-palette');
    colors.forEach(c => {
      const dot = document.createElement('div');
      dot.className = 'color-dot' + (c.key === 'default' ? ' active' : '');
      dot.dataset.color = c.key;
      dot.innerHTML = `<div class="color-dot-inner" style="background:${c.color};"></div>`;
      dot.onclick = () => {
        sheetRoot.querySelectorAll('.color-dot').forEach(d => d.classList.remove('active'));
        dot.classList.add('active');
        selectedColor = c.key;
      };
      palette.appendChild(dot);
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
      toggleRaw.checked = cur.showRaw !== false;
      selectedColor = cur.color || 'default';

      sheetRoot.querySelectorAll('.color-dot').forEach(d => {
        d.classList.toggle('active', d.dataset.color === selectedColor);
      });

      backdrop.style.pointerEvents = 'auto';
      backdrop.style.opacity = '1';
      panel.style.bottom = '0';
    };

    function closeSheet() {
      backdrop.style.opacity = '0';
      backdrop.style.pointerEvents = 'none';
      panel.style.bottom = '-100%';
    }

    backdrop.onclick = () => closeSheet();

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
  function pass() {
    if (passing) return;
    passing = true;
    try {
      ensureStyle();
      initSheet();
      harvestRecords();

      const nativeCards = Array.from(document.querySelectorAll('.scroll-container:not(#xl-fav-native-view) div.single'))
        .filter(c => isDispenserCard(c));

      if (nativeCards.length > 0) {
        const favs = getFavs(), aliases = getAliases();

        // 1. 刷新原生卡片的基础能力（长按备注、星标绑定、别名）
        nativeCards.forEach((card, i) => {
          const raw = nameOf(card);
          if (card.dataset.xl !== '1') card.setAttribute('data-xl', '1');
          bindCardLongPress(card);
          applyAlias(card, raw, aliases[raw]);
          attachInlineStar(card);

          // 原生列表置顶已收藏水机
          const fi = favs.indexOf(raw);
          card.style.order = String(fi >= 0 ? (-1000 + fi) : i);
        });

        const nativeScroll = nativeCards[0]?.closest('.scroll-container');
        if (nativeScroll) {
          ensureBottomSpacer(nativeScroll);
          const d = getComputedStyle(nativeScroll).display;
          if (d === 'block' || d === 'flow-root') {
            nativeScroll.style.display = 'flex';
            nativeScroll.style.flexDirection = 'column';
          }
        }

        // 2. 模式切换处理
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
      } else {
        const topBtn = document.getElementById('xl-top-fav-btn');
        if (topBtn) topBtn.style.display = 'none';
      }
    } catch (e) {
      console.warn('[XiaoLianPlus] pass error', e);
    } finally {
      passing = false;
    }
  }
  window.__xl_refresh__ = pass;

  // 关键防抖保护：避免在 React Fiber 挂载节点时同步介入 DOM
  let debTimer = null;
  const debouncedPass = () => {
    clearTimeout(debTimer);
    debTimer = setTimeout(pass, 100);
  };

  const observer = new MutationObserver((muts) => {
    // 忽略我们自己注入的根节点变动
    const isOnlyOurs = muts.every(m => {
      const t = m.target;
      return t && t.closest && t.closest('#xl-custom-style,#xl-scroll-bottom-spacer,#xl-top-fav-btn,#modern-sheet-root,#xl-fav-native-view');
    });
    if (!isOnlyOurs) {
      debouncedPass();
    }
  });

  const rootTarget = document.body || document.documentElement;
  if (rootTarget) {
    observer.observe(rootTarget, { childList: true, subtree: true });
  }
  setInterval(pass, 1500);
  pass();
})();


// ============================================================
// 打水页面 (pages/drinkwater/drinkwater) 体验优化全量实现
// 包含：自动跳过二次确认弹窗、长按 0.5s 平滑结算找零、页面敏感数据脱敏
// ============================================================
(function initDrinkWaterOptimizer() {
  if (window.__XL_DW_OPTIMIZER_INIT__) return;
  window.__XL_DW_OPTIMIZER_INIT__ = true;

  const config = window.__XL_CONFIG__ || { autoConfirm: true, holdToSettle: true, desensitize: false };
  const HOLD_DURATION = 500; // 长按触发阈值 (ms)
  let pressTimer = null;
  let isTriggered = false;

  // 1. 全局 CSS 注入：根据开关独立生效对应样式
  const styleId = 'xl-drinkwater-style';
  let style = document.getElementById(styleId);
  if (!style) {
    style = document.createElement('style');
    style.id = styleId;
    const container = document.head || document.documentElement || document.body;
    if (container) container.appendChild(style);
  }

  let cssRules = '';
  if (config.autoConfirm) {
    cssRules += `
      /* 关键修复：绝不能用 display: none 彻底销毁布局尺寸！改用 opacity: 0 保持物理尺寸可用 */
      .l-message-box, .l-message-mask, [class*="message-container"] {
        opacity: 0 !important;
        pointer-events: none !important;
      }
      .l-message-box .l-message-ok, .l-message-box [data-clickable="true"] {
        pointer-events: auto !important;
      }
    `;
  }
  if (config.holdToSettle) {
    cssRules += `
      /* 隐藏原版滑块球体，保持手势容器尺寸 */
      .sliders movable-area {
        opacity: 0 !important;
      }
      /* 改造为胶囊长按按钮 */
      .sliders {
        position: relative !important;
        cursor: pointer !important;
        user-select: none !important;
        -webkit-user-select: none !important;
        overflow: hidden !important;
      }
      /* 平滑填充进度条 */
      .hold-progress-bar {
        position: absolute;
        left: 0;
        top: 0;
        bottom: 0;
        width: 0%;
        background: #1082FF;
        transition: width ${HOLD_DURATION}ms linear;
        z-index: 11;
        pointer-events: none;
      }
      .sliders.is-pressing .hold-progress-bar {
        width: 100%;
      }
      .sliders .tips {
        z-index: 20 !important;
      }
      .sliders.is-pressing .tips text, .sliders.is-pressing .tips {
        color: #FFFFFF !important;
      }
    `;
  }
  style.textContent = cssRules;

  // 2. 自动点击“确认开始”弹窗
  function handleAutoConfirm() {
    if (!config.autoConfirm) return;
    const btns = Array.from(document.querySelectorAll('.l-message-ok, [data-clickable="true"]'));
    const okBtn = btns.find(el => el.innerText && el.innerText.trim() === '确认');
    if (!okBtn) return;

    const rect = okBtn.getBoundingClientRect();
    const x = rect.left + (rect.width > 0 ? rect.width / 2 : 100);
    const y = rect.top + (rect.height > 0 ? rect.height / 2 : 100);
    try {
      const touch = new Touch({ identifier: Date.now() % 100000, target: okBtn, clientX: x, clientY: y, pageX: x, pageY: y });
      ['touchstart', 'touchend'].forEach(type => {
        okBtn.dispatchEvent(new TouchEvent(type, {
          bubbles: true,
          cancelable: true,
          touches: type === 'touchend' ? [] : [touch],
          targetTouches: type === 'touchend' ? [] : [touch],
          changedTouches: [touch]
        }));
      });
    } catch (ignored) {}
    try { okBtn.click(); } catch (e) {}
  }

  // 3. 动态计算宽度并平滑推满滑块
  function smoothSlideAndSettle(track) {
    const rect = track.getBoundingClientRect();
    const startX = rect.left + rect.width / 2;
    const startY = rect.top + rect.height / 2;
    
    // 关键修复：动态获取外层容器宽度，覆盖 1080p/1.5K/2K 全部分辨率
    const container = track.closest('.sliders') || document.querySelector('.sliders');
    const containerW = container ? container.getBoundingClientRect().width : 320;
    const dragDistance = Math.max(280, containerW - 40);
    const targetX = startX + dragDistance;
    const steps = 6;
    const stepDist = (targetX - startX) / steps;

    function makeTouch(x) {
      return new Touch({ identifier: 999, target: track, clientX: x, clientY: startY, pageX: x, pageY: startY });
    }

    try {
      track.dispatchEvent(new TouchEvent('touchstart', {
        bubbles: true,
        cancelable: true,
        touches: [makeTouch(startX)],
        targetTouches: [makeTouch(startX)],
        changedTouches: [makeTouch(startX)]
      }));

      let currentX = startX;
      let count = 0;
      const timer = setInterval(() => {
        count++;
        currentX += stepDist;
        track.dispatchEvent(new TouchEvent('touchmove', {
          bubbles: true,
          cancelable: true,
          touches: [makeTouch(currentX)],
          targetTouches: [makeTouch(currentX)],
          changedTouches: [makeTouch(currentX)]
        }));

        if (count >= steps) {
          clearInterval(timer);
          setTimeout(() => {
            track.dispatchEvent(new TouchEvent('touchend', {
              bubbles: true,
              cancelable: true,
              touches: [],
              targetTouches: [],
              changedTouches: [makeTouch(targetX)]
            }));
          }, 50);
        }
      }, 16);
    } catch (ignored) {}
  }

  // 4. 接管滑块长按事件
  function setupHoldToSettle() {
    if (!config.holdToSettle) return;
    const container = document.querySelector('.sliders');
    if (!container || container.dataset.bindDone) return;
    container.dataset.bindDone = 'true';

    let bar = container.querySelector('.hold-progress-bar');
    if (!bar) {
      bar = document.createElement('div');
      bar.className = 'hold-progress-bar';
      container.appendChild(bar);
    }

    const tips = container.querySelector('.tips text') || container.querySelector('.tips');
    if (tips) tips.innerText = '按住 0.5 秒 结算找零';

    function startPress(e) {
      if (isTriggered) return;
      if (e && e.cancelable) e.preventDefault();
      container.classList.add('is-pressing');

      pressTimer = setTimeout(() => {
        isTriggered = true;
        container.classList.remove('is-pressing');
        if (tips) tips.innerText = '正在结算中...';

        const track = document.querySelector('#track');
        if (track) smoothSlideAndSettle(track);
      }, HOLD_DURATION);
    }

    function cancelPress() {
      if (isTriggered) return;
      clearTimeout(pressTimer);
      container.classList.remove('is-pressing');
    }

    container.addEventListener('touchstart', startPress, { passive: false });
    container.addEventListener('touchend', cancelPress);
    container.addEventListener('touchcancel', cancelPress);
    container.addEventListener('mousedown', startPress);
    container.addEventListener('mouseup', cancelPress);
  }

  // 5. 敏感文本源头脱敏
  function desensitizeUI() {
    if (!config.desensitize) return;
    const buildingEl = document.querySelector('.header-title .building, .a-view.building');
    if (buildingEl && !buildingEl.innerText.includes('虚拟演示')) {
      buildingEl.innerText = '虚拟演示设备 01';
    }
  }

  function runDrinkWater() {
    handleAutoConfirm();
    setupHoldToSettle();
    desensitizeUI();
  }

  runDrinkWater();
  let dwTimer = null;
  const dwObserver = new MutationObserver(() => {
    clearTimeout(dwTimer);
    dwTimer = setTimeout(runDrinkWater, 80);
  });
  const target = document.body || document.documentElement;
  if (target) {
    dwObserver.observe(target, { childList: true, subtree: true });
  }
})();
