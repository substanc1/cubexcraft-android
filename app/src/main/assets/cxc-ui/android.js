// © 2026 CubeXCraft (https://www.cubexcraft.site). Source-available, view only — see LICENSE.
// CubeX Mobile (Android): the launcher UI is the same as CubeXCraft for iPhone (app.js, app.css, strings.js). app.js talks to the
// phone through window.webkit.messageHandlers.cxc.postMessage(...) → Promise, as on iPhone; here that goes to the app's
// CxcAndroid (ui/WebBridge.kt), which answers through window.__cxcReply. Events come to window.cxc.emit as on iPhone.
'use strict';
(() => {
  const pending = new Map();
  let seq = 0;
  window.__cxcReply = (id, ok, data) => {
    const p = pending.get(id);
    if (!p) return;
    pending.delete(id);
    if (ok) p.resolve(data); else p.reject(new Error(String(data)));
  };
  window.webkit = {
    messageHandlers: {
      cxc: {
        postMessage: (msg) => new Promise((resolve, reject) => {
          const id = ++seq;
          pending.set(id, { resolve, reject });
          window.CxcAndroid.post(id, JSON.stringify(msg));
        }),
      },
    },
  };
  // the Back key of the phone: the screen's own «back» (a sheet or a window closes first); false — nothing to go back to
  window.__cxcBack = () => {
    const close = document.querySelector('.overlay[data-act]') || document.querySelector('[data-act="dialogClose"]');
    if (close) { close.click(); return true; }
    const back = document.querySelector('.navbar [data-act="go"]');
    if (back) { back.click(); return true; }
    return false;
  };
  // texts that speak about the iPhone
  const S = window.CXC_STR;
  const set = (k, ru, en) => { S[k] = { ru, en }; };
  set('st_base', 'Своё приложение CubeXCraft. Ядро запуска — PojavLauncher / Amethyst (LGPL-3.0), MobileGlues (LGPL-2.1), OpenJDK, LWJGL.',
    'CubeXCraft\'s own app. The launch core is PojavLauncher / Amethyst (LGPL-3.0), MobileGlues (LGPL-2.1), OpenJDK, LWJGL.');
  set('w_m_t', 'Памяти впритык', 'Memory is tight');
  set('w_m_p', 'Памяти у телефона мало — включили качество «Лёгкий» без шейдеров. Если игра закроется, закройте другие приложения.',
    'The phone is short on memory — we set «Light» quality without shaders. If the game closes, close other apps.');
})();
