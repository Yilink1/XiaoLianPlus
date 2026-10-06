(function() {
  if (window.__XL_WATER_DISPENSER_INJECTED__) {
    if (typeof window.__xl_refresh__ === 'function') {
      window.__xl_refresh__();
    }
    return;
  }
  window.__XL_WATER_DISPENSER_INJECTED__ = true;
  window.__XL_FAV_HOOKED__ = true;

  console.log('[XiaoLianPlus] Water dispenser script injected.');

  const FAV_KEY = 'xl_fav_list';
  const ALIAS_KEY = 'xl_alias_map_v4';

  const getFavs = () => {
    try { return JSON.parse(localStorage.getItem(FAV_KEY) || '[]'); } catch { return []; }
  };
  const setFavs = (list) => localStorage.setItem(FAV_KEY, JSON.stringify(list));

  const getAliases = () => {
    try { return JSON.parse(localStorage.getItem(ALIAS_KEY) || '{}'); } catch { return {}; }
  };
  const setAliases = (map) => localStorage.setItem(ALIAS_KEY, JSON.stringify(map));

  function resort() {
    const favs = getFavs();
    const currentWrappers = Array.from(document.querySelectorAll('.card-wrapper-box'));
    if (!currentWrappers.length) return;
    const parent = currentWrappers[0].parentElement;
    if (!parent) return;

    const favItems = [];
    const normalItems = [];

    favs.forEach(rawName => {
      const match = currentWrappers.find(w => w.dataset.rawName === rawName);
      if (match) favItems.push(match);
    });

    currentWrappers.forEach(w => {
      if (!favItems.includes(w)) normalItems.push(w);
    });

    normalItems.sort((a, b) => {
      const idxA = parseInt(a.querySelector('div.single')?.dataset.origIdx || 0, 10);
      const idxB = parseInt(b.querySelector('div.single')?.dataset.origIdx || 0, 10);
      return idxA - idxB;
    });

    [...favItems, ...normalItems].forEach(node => parent.appendChild(node));
  }

  function refreshAllTitles() {
    const aliases = getAliases();
    const wrappers = document.querySelectorAll('.card-wrapper-box');

    wrappers.forEach(w => {
      const titleEl = w.querySelector('.single-title') || w.querySelector('div.single');
      if (!titleEl) return;
      const rawName = w.dataset.rawName;
      if (!rawName) return;
      const data = aliases[rawName];

      if (data && (data.name || data.color !== 'default')) {
        const hasColor = data.color && data.color !== 'default';
        const colorStyle = hasColor ? `color: ${data.color}; font-weight: 600;` : '';

        if (data.name) {
          let rawHtml = '';
          if (data.showRaw) {
            rawHtml = `<span style="font-size: 11px; color: #8c8c8c; font-weight: normal; margin-left: 6px;">(${rawName})</span>`;
          }
          titleEl.innerHTML = `<span style="${colorStyle}">${data.name}</span>${rawHtml}`;
        } else {
          titleEl.innerHTML = `<span style="${colorStyle}">${rawName}</span>`;
        }
      } else {
        titleEl.innerText = rawName;
        titleEl.style.color = '';
        titleEl.style.fontWeight = '';
      }
    });
  }

  // 确保底部抽屉面板只初始化一次
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
        position: fixed; left: 0; right: 0; bottom: -100%;
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
          width: 100%; height: 46px; border: none; border-radius: 12px;
          background: #1677ff; color: #fff; font-size: 15px; font-weight: 600;
          cursor: pointer; box-shadow: 0 4px 12px rgba(22, 119, 255, 0.35);
        ">保存设置</button>
      </div>
    `;
    document.body.appendChild(sheetRoot);

    const styleEl = document.createElement('style');
    styleEl.innerHTML = `
      .card-wrapper-box {
        transition: transform 0.25s cubic-bezier(0.2, 0, 0, 1), opacity 0.2s ease;
      }
      #sheet-toggle-slider:before {
        position: absolute; content: ""; height: 18px; width: 18px; left: 3px; bottom: 3px;
        background-color: white; transition: .25s; border-radius: 50%;
      }
      #sheet-toggle-raw:checked + #sheet-toggle-slider { background-color: #1677ff; }
      #sheet-toggle-raw:checked + #sheet-toggle-slider:before { transform: translateX(20px); }
    `;
    document.head.appendChild(styleEl);

    const colors = [
      { name: '默认', val: 'default', bg: '#f2f3f5', border: '#d9d9d9' },
      { name: '科技蓝', val: '#1677ff', bg: '#1677ff' },
      { name: '极光绿', val: '#52c41a', bg: '#52c41a' },
      { name: '火山橙', val: '#fa541c', bg: '#fa541c' },
      { name: '极客紫', val: '#722ed1', bg: '#722ed1' }
    ];

    const paletteContainer = document.getElementById('sheet-palette');

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

    const backdrop = document.getElementById('sheet-backdrop');
    const panel = document.getElementById('sheet-panel');
    const inputAlias = document.getElementById('sheet-input-alias');
    const toggleRaw = document.getElementById('sheet-toggle-raw');
    const rawTag = document.getElementById('sheet-raw-tag');
    const saveBtn = document.getElementById('sheet-save-btn');

    let isSheetOpen = false;

    window.__xl_openSheet__ = function(rawName) {
      activeTargetRawName = rawName;
      const aliases = getAliases();
      const currentData = aliases[rawName] || {};

      rawTag.innerText = rawName;
      inputAlias.value = currentData.name || '';
      inputAlias.placeholder = rawName;
      toggleRaw.checked = !!currentData.showRaw;
      selectedColor = currentData.color || 'default';

      updatePaletteUI();

      isSheetOpen = true;

      backdrop.style.opacity = '1';
      backdrop.style.pointerEvents = 'auto';
      panel.style.transition = 'bottom 0.28s cubic-bezier(0.16, 1, 0.3, 1), transform 0.2s ease';
      panel.style.transform = 'translateY(0)';
      panel.style.bottom = '0';
    };

    function closeSheet() {
      if (!isSheetOpen) return;
      isSheetOpen = false;

      backdrop.style.opacity = '0';
      backdrop.style.pointerEvents = 'none';
      panel.style.transition = 'bottom 0.24s ease, transform 0.24s ease';
      panel.style.transform = 'translateY(100%)';
      setTimeout(() => {
        panel.style.bottom = '-100%';
        panel.style.transform = '';
      }, 250);
    }

    // 1. 点击背景阴影关闭
    backdrop.onclick = () => closeSheet();

    // 2. 下滑手势滑动关闭 (Swipe-down to dismiss)
    let touchStartY = 0;
    let currentTranslateY = 0;
    let isDragging = false;

    panel.addEventListener('touchstart', (e) => {
      if (e.target === inputAlias) return; // 避免打字时光标误触
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

    panel.addEventListener('touchend', () => {
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
    });

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
      refreshAllTitles();
      closeSheet();
    };
  }

  function processCards() {
    const rawCards = Array.from(document.querySelectorAll('div.single'));
    if (!rawCards.length) return;

    initSheet();

    let newCardsCount = 0;

    rawCards.forEach((card, index) => {
      if (!card.dataset.origIdx) {
        card.dataset.origIdx = index;
      }

      // 如果已经包装过，跳过（三重防重复守卫）
      if (card.dataset.xlWrapped === '1' || 
          (card.parentElement && card.parentElement.classList.contains('card-wrapper-box')) ||
          (typeof card.closest === 'function' && card.closest('.card-wrapper-box'))) {
        return;
      }
      card.dataset.xlWrapped = '1';

      newCardsCount++;

      const titleEl = card.querySelector('.single-title') || card;
      const rawName = (titleEl.dataset.rawText || titleEl.innerText || '').trim().split('\n')[0];
      titleEl.dataset.rawText = rawName;

      const isFav = getFavs().includes(rawName);

      const wrapper = document.createElement('div');
      wrapper.className = 'card-wrapper-box';
      wrapper.dataset.rawName = rawName;
      wrapper.style.cssText = 'display:flex;align-items:center;width:100%;margin-bottom:8px;';

      card.parentElement.insertBefore(wrapper, card);
      wrapper.appendChild(card);
      card.style.flex = '1';
      card.style.margin = '0';
      card.style.width = 'auto';

      let cardPressTimer = null;
      let isCardLongPress = false;
      let touchStartX = 0;
      let touchStartY = 0;

      card.addEventListener('touchstart', (e) => {
        if (!e.touches || !e.touches[0]) return;
        touchStartX = e.touches[0].clientX;
        touchStartY = e.touches[0].clientY;
        isCardLongPress = false;
        clearTimeout(cardPressTimer);

        cardPressTimer = setTimeout(() => {
          isCardLongPress = true;
          if (typeof window.__xl_openSheet__ === 'function') {
            window.__xl_openSheet__(rawName);
          }
        }, 380);
      }, { passive: true });

      card.addEventListener('touchmove', (e) => {
        if (!cardPressTimer || !e.touches || !e.touches[0]) return;
        const dx = e.touches[0].clientX - touchStartX;
        const dy = e.touches[0].clientY - touchStartY;
        // 允许指腹微动（12px 防抖容差），只有明确滑动才取消长按
        if (Math.hypot(dx, dy) > 12) {
          clearTimeout(cardPressTimer);
          cardPressTimer = null;
        }
      }, { passive: true });

      card.addEventListener('touchend', (e) => {
        clearTimeout(cardPressTimer);
        cardPressTimer = null;
        if (isCardLongPress) {
          e.preventDefault();
          e.stopPropagation();
        }
      }, { passive: false });

      const starSlot = document.createElement('div');
      starSlot.className = 'my-fav-slot';
      starSlot.style.cssText = `
        width: 58px; height: 64px; display: flex; align-items: center;
        justify-content: center; cursor: pointer; user-select: none;
        -webkit-user-select: none; touch-action: manipulation;
      `;

      const star = document.createElement('span');
      star.innerText = isFav ? '★' : '☆';
      star.style.cssText = `
        font-size: 26px; color: ${isFav ? '#f5a623' : '#c0c4cc'};
        pointer-events: none; transition: transform 0.15s ease;
      `;
      starSlot.appendChild(star);

      starSlot.addEventListener('touchstart', () => { star.style.transform = 'scale(1.2)'; }, { passive: true });
      starSlot.addEventListener('touchend', () => { star.style.transform = 'scale(1)'; }, { passive: true });

      starSlot.onclick = (e) => {
        e.stopPropagation();
        e.preventDefault();

        let list = getFavs();
        if (list.includes(rawName)) {
          list = list.filter(item => item !== rawName);
          star.innerText = '☆';
          star.style.color = '#c0c4cc';
        } else {
          list.push(rawName);
          star.innerText = '★';
          star.style.color = '#f5a623';
        }
        setFavs(list);
        resort();
      };

      wrapper.appendChild(starSlot);
    });

    if (newCardsCount > 0) {
      refreshAllTitles();
      resort();
    }
  }

  window.__xl_refresh__ = function() {
    processCards();
    refreshAllTitles();
    resort();
  };

  // 初始执行一次
  processCards();

  // 使用 MutationObserver 动态监听卡片渲染和楼栋切换
  let timer = null;
  const observer = new MutationObserver((mutations) => {
    let shouldCheck = false;
    for (const m of mutations) {
      if (m.addedNodes.length > 0) {
        shouldCheck = true;
        break;
      }
    }
    if (shouldCheck) {
      if (timer) clearTimeout(timer);
      timer = setTimeout(() => {
        processCards();
      }, 40);
    }
  });

  observer.observe(document.body || document.documentElement, {
    childList: true,
    subtree: true
  });
})();
