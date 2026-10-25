// © 2026 CubeXCraft (https://www.cubexcraft.site). Source-available, view only — see LICENSE. The same launcher UI as CubeXCraft for iPhone.
// Лаунчер CubeXCraft для iPhone (SCRUM-363): экраны по handoff Claude Design «CubeXCraft Mobile iOS».
// Всё, что трогает телефон и сеть (вход, API сайта, установка сборки, JIT, запуск игры), делает родной код
// через window.webkit.messageHandlers.cxc (ответ — Promise); события от него приходят в window.cxc.emit.
// Данные только реальные: что не пришло из API — не показываем.
'use strict';
(() => {
  const STR = window.CXC_STR;
  const IC = window.CXC_ICONS;
  const SITE = 'https://www.cubexcraft.site';

  const S = {
    lang: 'ru', screen: 'boot', info: {}, account: null,
    servers: null, profiles: [], sel: null, states: {}, friends: null, offline: false, loading: true,
    modsOpen: false, login: { login: '', password: '', busy: false, twofa: false, error: null },
    setup: { stik: false, vpn: false, vpnApp: false, checking: false, done: false },
    prep: null, jit: null, sheet: null, dialog: null, toast: null, wallet: null, addNick: '', versionTaps: 0,
    news: null, vote: null, newsOpen: null,
  };

  // ---------- мелочи ----------
  const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const t = (k, o) => {
    const v = STR[k];
    let s = v ? (v[S.lang] || v.ru) : k;
    if (o) s = s.replace(/\{(\w+)\}/g, (_, n) => (o[n] ?? ''));
    return s;
  };
  const num = (n, d = 1) => Number(n).toLocaleString(S.lang === 'ru' ? 'ru-RU' : 'en-US', { maximumFractionDigits: d, minimumFractionDigits: d });
  const icon = (name, size = 18, style = '') => `<svg class="i" width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round" style="${style}">${IC[name] || ''}</svg>`;
  const logo = (w, h, sw = 3, glow = '') => `<svg width="${w}" height="${h}" viewBox="0 0 86 96" fill="none" style="flex:none;${glow ? `filter:drop-shadow(0 0 ${glow}px #ff2a3c88)` : ''}"><path d="M43 2 82 24v48L43 94 4 72V24Z" stroke="#ff2a3c" stroke-width="${sw}" fill="#ff2a3c14"/><path d="M43 26 63 37v22L43 70 23 59V37Z" fill="#ff2a3c"/><path d="M43 26 63 37 43 48 23 37Z" fill="#fcee0a"/></svg>`;
  const skin = (nick) => `${SITE}/texture/skin/${encodeURIComponent(nick)}.png`;
  const head = (nick, size) => `<span class="head" style="width:${size}px;height:${size}px"><i class="face" style="background-image:url('${skin(nick)}')"></i><i class="hat" style="background-image:url('${skin(nick)}')"></i></span>`;
  const kick = (text, cls = '') => `<div class="kick ${cls}"><span>// ${esc(text)}</span><span class="ln"></span></div>`;
  const spin = '<span class="spin"></span>';
  const ago = (sec) => {
    if (sec < 60) return t('ago_now');
    if (sec < 3600) return t('ago_min', { n: Math.floor(sec / 60) });
    if (sec < 86400) return t('ago_h', { n: Math.floor(sec / 3600) });
    if (sec < 172800) return t('nt_t3');
    return t('ago_d', { n: Math.floor(sec / 86400) });
  };

  // ---------- мост к родному коду ----------
  const hasNative = !!(window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.cxc);
  const native = (cmd, args = {}) => (hasNative
    ? window.webkit.messageHandlers.cxc.postMessage(Object.assign({ cmd }, args))
    : (window.CXC_MOCK ? window.CXC_MOCK(cmd, args) : Promise.reject(new Error('no native'))));
  window.cxc = { emit: (type, data) => onEvent(type, data || {}) };

  // API сайта через родной код (токен лаунчера там); r = {status, json} или {network: true}
  async function api(path, opt = {}) {
    const r = await native('api', { path, method: opt.method || 'GET', body: opt.body || null, auth: opt.auth !== false });
    if (r.network) {
      setOffline(true);
      throw { network: true };
    }
    setOffline(false);
    if (r.status === 401 && opt.auth !== false) {
      S.dialog = 'session';
      render();
      throw { status: 401 };
    }
    if (r.status >= 400) throw { status: r.status, json: r.json || {} };
    return r.json;
  }
  function setOffline(v) {
    if (S.offline !== v) {
      S.offline = v;
      if (S.screen === 'main') render();
    }
  }
  const save = (k, v) => { try { localStorage.setItem('cxc.' + k, JSON.stringify(v)); } catch (e) { /* без хранилища — без кэша */ } };
  const load = (k) => { try { return JSON.parse(localStorage.getItem('cxc.' + k)); } catch (e) { return null; } };

  // ---------- данные ----------
  const playable = (srv) => S.profiles.find((p) => p.mobile && String(p.title).toLowerCase() === String(srv.name).toLowerCase());
  const selected = () => (S.servers || []).find((s) => s.slug === S.sel) || (S.servers || [])[0];

  async function loadMain() {
    S.loading = !S.servers;
    render();
    try {
      const [srv, prof] = await Promise.all([api('/servers', { auth: false }), api('/profiles?platform=android')]);
      S.servers = srv.servers || [];
      S.profiles = (prof.profiles || []).filter((p) => p.mobile);
      save('servers', S.servers);
      save('profiles', S.profiles);
    } catch (e) {
      S.servers = S.servers || load('servers') || [];
      S.profiles = S.profiles.length ? S.profiles : (load('profiles') || []);
    }
    if (!S.sel || !S.servers.some((s) => s.slug === S.sel)) {
      const first = S.servers.find((s) => playable(s)) || S.servers[0];
      S.sel = load('sel') || (first && first.slug);
      if (!S.servers.some((s) => s.slug === S.sel)) S.sel = first && first.slug;
    }
    S.loading = false;
    render();
    loadState();
    presence();
    loadNews();
  }

  // новости сайта и кнопка «Голосовать» (API /news, без входа). Обложки — data:-адресами через родной код:
  // WebView ходил бы к сайту напрямую, а родной код качает их сам
  async function loadNews() {
    try {
      const r = await api('/news', { auth: false });
      S.news = r.news || [];
      S.vote = r.vote && r.vote.url ? r.vote : null;
    } catch (e) {
      if (!S.news) S.news = 'error';
    }
    if (S.screen === 'main') render();
    for (const n of Array.isArray(S.news) ? S.news : []) {
      if (!n.cover || n.coverData) continue;
      native('image', { url: n.cover }).then((d) => {
        if (d && d.data) { n.coverData = d.data; if (S.screen === 'main') render(); }
      }).catch(() => { /* без обложки */ });
    }
  }
  const newsDate = (d) => { const x = new Date(d); return isNaN(x) ? '' : x.toLocaleDateString(S.lang === 'ru' ? 'ru-RU' : 'en-GB'); };
  async function loadState() {
    const s = selected();
    const p = s && playable(s);
    if (!p) return;
    try {
      S.states[p.title] = await native('projectState', { title: p.title, filesDir: (p.mobile || {}).filesDir });
      render();
    } catch (e) { /* без состояния — кнопка «Играть» как есть */ }
  }
  async function presence() {
    try {
      S.friends = await api('/presence', { method: 'POST', body: { state: 'online' } });
      if (['main', 'friends', 'notifs'].includes(S.screen)) render();
    } catch (e) { /* друзья подождут */ }
  }
  setInterval(() => { if (S.account && ['main', 'friends', 'notifs'].includes(S.screen)) presence(); }, 30000);

  // ---------- экраны ----------
  const app = (inner, overlay = '') => `<div class="app"><div class="col">${inner}</div>${overlay}</div>`;
  const navbar = (title, back, act = '') => `<div class="navbar"><div class="icon-btn tap" data-act="go" data-arg="${back}">${icon('back', 22)}</div><span class="t">${esc(title)}</span>${act}</div>`;
  const langSeg = () => `<div class="seg">${['ru', 'en'].map((l) => `<span class="${S.lang === l ? 'on' : ''}" data-act="lang" data-arg="${l}">${l.toUpperCase()}</span>`).join('')}</div>`;

  const V = {};

  V.boot = () => app(`<div style="flex:1;display:grid;place-items:center">${logo(86, 96, 3, 16)}</div>`);

  V.setup = () => {
    const st = S.setup;
    const ready = st.stik && st.vpn;
    const box = (ok) => `<span class="box">${st.checking ? spin : icon(ok ? 'check' : 'close', 16)}</span>`;
    const row = (name, ok, okText, noText, hint, button) => `<div class="chk ${ok ? 'ok' : ''}"><div class="row">${box(ok)}<div style="flex:1;min-width:0"><div class="n">${esc(name)}</div><div class="s">${st.checking ? t('su_chk') : (ok ? okText : noText)}</div></div></div>${ok ? '' : `<div class="h">${hint}</div>${button || ''}`}</div>`;
    return app(`<div class="scroll"><div style="padding:20px 16px 8px;display:flex;flex-direction:column;gap:18px">
      ${kick(t('su_kick'))}
      <h1 class="title">${st.done ? t('su_t_done') : t('su_t')}</h1>
      <p class="lead" style="margin-top:-6px">${st.done ? t('su_p_done') : t('su_p')}</p>
      <div style="display:flex;flex-direction:column;gap:10px">
        ${row(t('su_stik'), st.stik, t('su_stik_ok'), t('su_stik_no'), t('su_stik_h'), `<div style="margin-top:12px"><div class="btn primary small tap" data-act="store" data-arg="stik">${t('su_stik_how')}</div></div>`)}
        ${row(t('su_vpn'), st.vpn, t('su_vpn_ok'), t('su_vpn_no'), t('su_vpn_h'), st.vpnApp ? `<div style="margin-top:12px"><div class="btn primary small tap" data-act="store" data-arg="vpn">${t('su_vpn_on')}</div></div>` : `<div style="margin-top:12px"><div class="btn primary small tap" data-act="store" data-arg="vpn">${t('su_install')}</div></div>`)}
        ${row(t('su_jit'), ready, t('su_jit_ok'), t('su_jit_no'), t('su_jit_h'))}
      </div></div></div>
      <div class="foot"><div class="btn primary tap" data-act="setupCheck">${st.done ? spin + t('su_go') : (st.checking ? spin + t('su_chk') : t('su_recheck'))}</div></div>`);
  };

  V.login = () => {
    const L = S.login;
    const head2 = `<div style="display:flex;justify-content:flex-end">${langSeg()}</div>
      <div style="display:flex;flex-direction:column;align-items:center;gap:14px;padding:22px 0 10px">${logo(72, 80, 3, 14)}
      <span class="brand" style="font-size:20px">CUBE<b>X</b>CRAFT</span>
      <span class="mono" style="font-size:11px;letter-spacing:.2em;color:var(--yellow);text-transform:uppercase">// ${t('lg_kick')}</span></div>`;
    if (L.twofa) {
      return app(`<div class="scroll"><div style="padding:6px 16px 12px;display:flex;flex-direction:column;gap:16px">${head2}
        <div class="card"><div style="display:flex;flex-direction:column;align-items:center;gap:14px;text-align:center;padding:10px 4px">
          <span class="sq56">${icon('send', 28)}</span>
          <div style="font-family:var(--head);font-weight:600;font-size:16px;color:var(--text)">${t('lg_2fa_t')}</div>
          <div style="font-size:14px;line-height:1.55;color:var(--body)">${t('lg_2fa_p')}</div>
          <div class="mono" style="display:flex;align-items:center;gap:10px;font-size:12px;letter-spacing:.12em;color:var(--yellow);text-transform:uppercase">${spin}${t('lg_2fa_w')}</div></div>
          <div style="margin-top:12px"><div class="btn tap" data-act="loginCancel">${t('cancel')}</div></div></div></div></div>`);
    }
    const err = L.error ? `<div class="alert">${icon(L.error === 'network' ? 'wifiOff' : 'alert', 18, 'color:var(--red)')}<span style="flex:1;min-width:0">${L.error === 'password' ? t('lg_e_pw') : L.error === 'network' ? t('lg_e_net') : L.error === 'rate' ? t('lg_e_many') : esc(L.message || '')}</span></div>` : '';
    const red = L.error === 'password' ? 'err' : '';
    const can = L.login.trim() && L.password;
    return app(`<div class="scroll"><div style="padding:6px 16px 12px;display:flex;flex-direction:column;gap:16px">${head2}
      <div style="display:flex;flex-direction:column;gap:14px">
        <div class="field"><label>${t('lg_login')}</label><div class="input ${red}">${icon('user', 18, 'color:var(--muted)')}<input id="lg-login" autocomplete="username" autocapitalize="none" autocorrect="off" spellcheck="false" placeholder="${esc(t('lg_login_ph'))}" value="${esc(L.login)}"></div></div>
        <div class="field"><label>${t('lg_pass')}</label><div class="input ${red}">${icon('lock', 18, 'color:var(--muted)')}<input id="lg-pass" type="password" autocomplete="current-password" placeholder="${esc(t('lg_pass_ph'))}" value="${esc(L.password)}"></div></div>
        ${err}
        <div class="btn primary tap ${can && !L.busy ? '' : 'off'}" data-act="login">${L.busy ? spin + t('lg_busy') : t('lg_go')}</div>
        <div class="btn tap" data-act="url" data-arg="${SITE}/register">${icon('external')}${t('lg_reg')}</div>
      </div></div></div>`);
  };

  const skelBox = (w, h, m = '0') => `<div class="skel" style="width:${w};height:${h}px;margin:${m}"></div>`;

  function nodeBlock(s, p) {
    if (!s) return '';
    if (!p) {
      return `<div class="node dim">${kick(t('mn_node'), 'grey')}<h1>${esc(s.name)}</h1>
        <div style="margin-top:14px"><span class="chip mute">${icon('alert', 13)}${t('mn_nophone')}</span></div></div>`;
    }
    const up = s.up && !S.offline;
    const props = p.properties || {};
    const chips = [
      s.statusLabel ? `<span class="chip y">${esc(s.statusLabel)}</span>` : '',
      props.wipeDate ? `<span class="chip y">${t('mn_wipe')} ${esc(props.wipeDate)}</span>` : '',
      props.mode ? `<span class="chip r">${esc(props.mode)}</span>` : '',
      s.version ? `<span class="chip">${esc(s.version)}</span>` : '',
      up ? `<span class="chip g"><span class="dot green"></span>${t('mn_srv_on')}</span>` : `<span class="chip r"><span class="dot" style="background:var(--red)"></span>${t('mn_srv_off')}</span>`,
    ].join('');
    const idx = String((S.servers || []).indexOf(s) + 1).padStart(2, '0');
    return `<div class="node"><div class="num">${idx}</div><div style="position:relative">${kick(t('mn_node'))}
      <h1>${esc(s.name)}</h1><div class="chips" style="margin-top:12px">${chips}</div>
      <div class="online">${up ? `<b>${s.online}</b>` : '<b class="none">—</b>'}<span>/ ${s.max}</span></div>
      <div class="cap">${t('mn_online')}</div>${p.info ? `<p>${esc(p.info)}</p>` : ''}</div></div>`;
  }

  function friendRow(f, size = 36, remove = false) {
    const st = f.state === 'playing' ? ['green', f.server ? t('fr_ingame_t', { server: f.server }) : t('fr_on')]
      : f.state === 'online' ? ['green', t('fr_on')] : f.state === 'away' ? ['yellow', t('fr_away')] : ['grey', t('fr_off')];
    return `<div style="display:flex;align-items:center"><div class="fr ${f.state === 'offline' ? 'off' : ''}" style="flex:1;min-width:0">
      <div class="head-wrap">${head(f.username, size)}${f.state !== 'offline' ? `<span class="dot ${st[0]}"></span>` : ''}</div>
      <div style="flex:1;min-width:0"><div class="nm">${esc(f.username)}</div><div class="st ${st[0]}">${esc(st[1])}</div></div></div>
      ${remove ? `<div class="icon-btn tap" style="color:var(--muted)" data-act="frRemove" data-arg="${esc(f.username)}">${icon('trash', 20)}</div>` : ''}</div>`;
  }

  V.main = () => {
    const s = selected();
    const p = s && playable(s);
    const st = p ? S.states[p.title] : null;
    const unread = S.friends ? (S.friends.notifications || []).filter((n) => n.unread).length : 0;
    const top = `<div class="topbar"><div class="who tap" data-act="go" data-arg="account">${head(S.account.username, 32)}<span>${esc(S.account.username)}</span>${icon('chevR', 16, 'color:var(--muted)')}</div>
      <div class="icon-btn tap" data-act="go" data-arg="notifs">${icon('bell', 22)}${unread ? `<span class="badge">${unread}</span>` : ''}</div>
      <div class="icon-btn tap" data-act="go" data-arg="settings">${icon('gear', 22)}</div></div>`;
    const banner = S.offline ? `<div class="banner">${icon('wifiOff', 20, 'color:var(--red)')}<span style="flex:1;min-width:0">${t('w_net')}</span></div>` : '';
    let body;
    if (S.loading || !S.servers) {
      body = `<div class="pad"><div class="node">${skelBox('40%', 12)}${skelBox('70%', 32, '12px 0 0')}<div style="display:flex;gap:6px;margin-top:14px">${skelBox('84px', 22)}${skelBox('52px', 22)}</div>${skelBox('50%', 34, '18px 0 0')}${skelBox('100%', 12, '16px 0 0')}${skelBox('86%', 12, '8px 0 0')}</div>
        <div style="display:flex;flex-direction:column;gap:8px">${skelBox('100%', 64)}${skelBox('100%', 64)}</div>${skelBox('100%', 60)}</div>`;
    } else {
      const net = S.servers.map((x) => {
        const xp = playable(x);
        const meta = xp ? `<div class="m">${esc(x.version || '')} · ${x.up && !S.offline ? x.online : '—'} / ${x.max} </div>` : `<div style="margin-top:6px"><span class="chip mute">${t('mn_nophone')}</span></div>`;
        return `<div class="srv tap ${x.slug === S.sel ? 'sel' : ''}" data-act="sel" data-arg="${esc(x.slug)}"><div style="flex:1;min-width:0"><div class="n">${esc(x.name)}</div>${meta}</div></div>`;
      }).join('');
      const mods = (p && ((p.properties || {}).mods || (st && st.mods))) || [];
      const shown = S.modsOpen ? mods : mods.slice(0, 2);
      const modsBlock = mods.length ? `<div>${`<div class="kick"><span>// ${t('mn_build')} · ${mods.length}</span><span class="ln"></span><span class="fold tap" data-act="mods">${S.modsOpen ? t('mn_fold') : t('mn_unfold')}${icon(S.modsOpen ? 'chevU' : 'chevD', 14)}</span></div>`}
        <div class="mods">${shown.map((m) => `<span class="mod">${esc(m)}</span>`).join('')}${S.modsOpen || mods.length <= 2 ? '' : `<span class="mod more">${t('mn_more')} ${mods.length - 2}</span>`}</div></div>` : '';
      const fr = S.friends ? S.friends.friends || [] : [];
      const on = fr.filter((f) => f.state !== 'offline');
      const friendsBlock = `<div>${kick(`${t('mn_friends')} · ${on.length}`)}<div style="display:flex;flex-direction:column;margin-top:4px">${on.slice(0, 3).map((f) => friendRow(f)).join('')}</div>
        <div style="display:flex;gap:8px;margin-top:6px"><div class="btn tap" style="flex:1" data-act="go" data-arg="friends">${icon('users')}<span>${t('mn_all')}</span></div></div></div>`;
      body = `<div class="pad">${nodeBlock(s, p)}
        <div>${kick(`${t('mn_net')} · ${S.servers.length}`, 'red')}<div class="list">${net}</div></div>
        ${modsBlock}
        <div>${kick(t('mn_feed'), 'red')}${S.vote ? `<div class="vote tap" data-act="url" data-arg="${esc(S.vote.url)}"><div class="t">${icon('external', 18)}<span>${t('mn_vote')}</span></div>${S.vote.reward ? `<div class="r">${t('mn_vote_r').replace('{n}', S.vote.reward)}</div>` : ''}</div>` : ''}
          <div class="newslist">${Array.isArray(S.news) && S.news.length ? S.news.map((n) => `<div class="news tap ${S.newsOpen === n.id ? 'open' : ''}" data-act="news" data-arg="${n.id}">${n.coverData ? `<img class="cv" src="${n.coverData}" alt="">` : n.cover ? '<div class="cv ph"></div>' : ''}<div class="tx"><div class="d">${esc(newsDate(n.date))}</div><div class="h">${esc(n.title)}</div><div class="b">${esc(n.body)}</div></div></div>`).join('') : `<div class="soon">${S.news === 'error' ? t('mn_news_err') : S.news ? t('mn_news_empty') : '…'}</div>`}</div></div>
        ${friendsBlock}</div>`;
    }
    let foot;
    if (S.loading || !S.servers) foot = `<div class="play off">${icon('play', 20)}<span>${t('mn_play')}</span></div>`;
    else if (!p) foot = `<div class="hint">${t('mn_h_nop')}</div><div class="play off"><span>${t('mn_unavail')}</span></div>`;
    else if (S.offline) foot = `<div class="hint">${t('mn_h_off2')}</div><div class="play off"><span>${t('mn_unavail')}</span></div>`;
    else if (!s.up) foot = `<div class="hint">${t('mn_h_off')}</div><div class="play off"><span>${t('mn_unavail')}</span></div>`;
    else if (st && !st.installed) foot = `<div class="play tap" data-act="play">${icon('download', 20)}<span>${t('mn_dlplay')}</span>${st.sizeMB ? `<span class="size">~${st.sizeMB} ${t('mb')}</span>` : ''}</div>`;
    else foot = `<div class="play tap" data-act="play">${icon('play', 20)}<span>${t('mn_play')}</span></div>`;
    return app(`${top}${banner}<div class="scroll" id="main-scroll">${body}</div><div class="foot fade">${foot}</div>`, overlays());
  };

  V.prepare = () => {
    const P = S.prep;
    const s = selected();
    const name = s ? s.name : '';
    const pct = Math.floor(P.percent || 0);
    const files = P.total ? `${P.done || 0} / ${P.total}` : '';
    const kickLine = P.error ? kick(t('pr_k_err'), 'red') : P.cancelled ? kick(t('pr_k_can'), 'grey') : kick(t('pr_k'));
    const step = (i, label) => {
      const cls = P.phase > i ? 'done' : P.phase === i ? (P.error ? 'fail' : P.cancelled ? '' : 'now') : '';
      const ic = cls === 'done' ? 'check' : cls === 'fail' ? 'close' : cls === 'now' ? 'chevR' : 'plus';
      return `<div class="step ${cls}">${icon(ic, 14)}<span>${label}</span></div>`;
    };
    const note = P.error ? `<div class="note err">${icon('alert', 24, 'color:var(--red)')}<div><div class="t">${t('pr_e_t')}</div><div class="p">${P.total ? t('pr_e_p_t', { d: P.done || 0, n: P.total }) : esc(P.error)}</div></div></div>`
      : P.cancelled ? `<div class="note">${icon('info', 24, 'color:var(--muted)')}<div><div class="t">${t('pr_c_t')}</div><div class="p">${t('pr_c_p')}</div></div></div>` : '';
    const line = P.mb ? t('pr_total_t', { n: P.total, mb: num(P.mb) }) : (P.phase === 0 ? t('pr_check_t') : P.phase === 2 ? t('pr_launch_t') : (P.text || ''));
    const foot = P.error ? `<div class="btn primary tap" data-act="play">${icon('refresh', 20)}<span>${t('pr_retry')}</span></div><div class="btn tap" data-act="go" data-arg="main">${t('cancel')}</div>`
      : P.cancelled ? `<div class="btn primary tap" data-act="play">${icon('download', 20)}<span>${t('mn_dlplay')}</span></div><div class="btn tap" data-act="go" data-arg="main">${t('back')}</div>`
      : `<div class="btn danger tap" data-act="cancelPlay">${t('cancel')}</div>`;
    return app(`<div class="scroll"><div style="padding:18px 16px 8px;display:flex;flex-direction:column;gap:16px">
      <div style="display:flex;align-items:center;gap:12px">${logo(26, 29, 5)}<span class="brand" style="font-size:13px;letter-spacing:.1em">CUBE<b>X</b>CRAFT</span></div>
      ${kickLine}<h1 class="big" style="margin-top:-4px">${esc(name)}</h1>${note}
      <div><div class="pct"><b class="${P.error || P.cancelled ? 'mid' : ''} ${P.cancelled ? 'grey' : ''}">${P.cancelled ? 0 : pct}<small>%</small></b><span>${P.cancelled ? '' : files}</span></div>
        <div class="bar ${P.error ? 'err' : ''}"><i style="width:${P.cancelled ? 0 : pct}%"></i></div></div>
      <div class="stats"><div class="stat"><div>${t('pr_speed')}</div><div>${P.speed && !P.error && !P.cancelled ? `${num(P.speed)} ${t('mbs')}` : '—'}</div></div>
        <div class="stat"><div>${t('pr_files')}</div><div>${files && !P.error && !P.cancelled ? files : '—'}</div></div></div>
      <div class="mono" style="font-size:12px;color:var(--muted)">${esc(line)}</div>
      <div class="steps">${step(0, t('pr_s1'))}${step(1, t('pr_s2'))}${step(2, t('pr_s3'))}</div></div></div>
      <div class="foot">${foot}</div>`);
  };

  V.jit = () => {
    const J = S.jit;
    if (J.state === 'fail') {
      const line = (ok, text) => `<div style="display:flex;align-items:center;gap:10px;min-height:36px;font-size:14px;color:var(--text2)"><span style="color:${ok ? 'var(--green)' : 'var(--red)'};display:flex">${icon(ok ? 'check' : 'close', 18)}</span>${text}</div>`;
      return app(`<div class="scroll"><div style="padding:24px 16px 8px;display:flex;flex-direction:column;gap:18px">
        <span class="sq56">${icon('alert', 28)}</span><h1 class="title">${t('jt_f_t')}</h1><p class="lead" style="max-width:320px">${t('jt_f_p')}</p>
        <div class="node" style="padding:10px 14px;clip-path:none">${line(J.stik, J.stik ? t('su_stik_ok2') : t('jt_stik_no'))}${line(J.vpn, J.vpn ? t('jt_vpn_ok') : t('su_vpn_no2'))}</div></div></div>
        <div class="foot"><div class="btn primary tap" data-act="jitRetry">${t('pr_retry')}</div><div class="btn tap" data-act="jitCancel">${t('back')}</div></div>`);
    }
    return app(`<div style="flex:1;min-height:0;padding:24px 16px 8px;display:flex;flex-direction:column;gap:18px;align-items:center;justify-content:center;text-align:center">
      ${logo(86, 96, 3, 16)}<h1 class="title">${t('jt_t')}</h1><p class="lead" style="max-width:320px">${t('jt_p')}</p>
      <div class="mono" style="display:flex;align-items:center;gap:10px;font-size:12px;letter-spacing:.14em;color:var(--yellow);text-transform:uppercase">${spin}${t('jt_wait')}</div></div>
      <div class="foot"><div class="btn danger tap" data-act="jitCancel">${t('cancel')}</div></div>`);
  };

  V.friends = () => {
    const F = S.friends || { friends: [], requests: [] };
    const req = F.requests || [];
    const list = F.friends || [];
    const reqBlock = req.length ? `<div>${kick(`${t('fr_req')} · ${req.length}`, 'red')}${req.map((r) => `<div style="display:flex;align-items:center;gap:10px;min-height:64px">${head(r.username, 36)}
      <div style="flex:1;min-width:0"><div class="mono" style="font-weight:500;font-size:14px;color:var(--text)">${esc(r.username)}</div><div style="font-size:12px;color:var(--muted);margin-top:2px">${t('fr_req_n')}</div></div>
      <span class="fr"><span class="sq ok tap" data-act="frAccept" data-arg="${esc(r.username)}">${icon('check', 20)}</span></span><span class="fr"><span class="sq tap" data-act="frDecline" data-arg="${esc(r.username)}">${icon('close', 20)}</span></span></div>`).join('')}</div>` : '';
    const listBlock = list.length ? `<div>${kick(`${t('fr_list')} · ${list.length}`)}<div style="margin-top:2px">${list.map((f) => friendRow(f, 36, true)).join('')}</div></div>`
      : (req.length ? '' : `<div class="empty">${icon('users', 40, 'color:var(--muted)')}<div class="t">${t('fr_empty')}</div><div class="p">${t('fr_empty_p')}</div></div>`);
    return app(`${navbar(t('fr_title'), 'main')}<div class="scroll"><div style="padding:16px 16px 8px;display:flex;flex-direction:column;gap:18px">
      <div style="display:flex;gap:8px"><div class="input" style="flex:1;min-width:0;height:48px;padding:0 12px">${icon('userPlus', 18, 'color:var(--muted)')}<input id="fr-add" autocapitalize="none" autocorrect="off" spellcheck="false" placeholder="${esc(t('fr_add_ph'))}" value="${esc(S.addNick)}"></div>
        <div class="btn primary small tap" style="width:auto;height:48px" data-act="frAdd">${t('fr_add')}</div></div>
      ${reqBlock}${listBlock}</div></div>
      <div class="foot"><div class="btn tap" data-act="share">${icon('share')}${t('fr_inv')}</div></div>`, overlays());
  };

  V.notifs = () => {
    const N = (S.friends && S.friends.notifications) || [];
    const text = (n) => (n.kind === 'invite' ? t('nt_invite', { server: n.server || '' }) : n.kind === 'accepted' ? t('nt_accepted') : n.kind === 'playing' ? t('nt_playing', { server: n.server || '' }) : '');
    const items = N.length ? N.map((n) => `<div class="nt ${n.unread ? 'unread' : ''}"><span style="margin-top:6px"><span class="dot ${n.unread ? 'red' : 'grey'}"></span></span>
      <div><div class="x"><b>${esc(n.actor)}</b> ${esc(text(n))}</div><div class="w">${ago(n.agoSec || 0)}</div></div></div>`).join('')
      : `<div class="empty">${icon('bell', 40, 'color:var(--muted)')}<div class="t">${t('nt_empty')}</div></div>`;
    const act = N.some((n) => n.unread) ? `<span class="act tap" data-act="ntRead">${t('nt_all')}</span>` : '';
    return app(`${navbar(t('nt_title'), 'main', act)}<div class="scroll">${items}</div>`);
  };

  const qName = (k) => ({ auto: t('q_auto'), max: t('q_max'), balanced: t('q_bal'), light: t('q_low') }[k]);
  V.settings = () => {
    const I = S.info;
    const qv = I.quality === 'auto' ? `${t('q_auto')} (${qName(I.qualityAuto)})` : qName(I.quality);
    const sv = I.shaders === 'auto' ? `${t('sh_auto')} (${I.shadersAuto ? t('sh_on') : t('sh_off')})` : (I.shaders === 'on' ? t('sh_on_v') : t('sh_off_v'));
    return app(`${navbar(t('st_title'), 'main')}<div class="scroll">
      <div class="sec">${kick(t('st_game'), 'red')}</div>
      <div class="row56 tap" data-act="sheet" data-arg="quality"><span class="l">${t('st_quality')}</span><span class="v">${esc(qv)}</span>${icon('chevR', 16, 'color:var(--muted)')}</div>
      <div class="row56 tap" data-act="sheet" data-arg="shaders"><span class="l">${t('st_shaders')}</span><span class="v">${esc(sv)}</span>${icon('chevR', 16, 'color:var(--muted)')}</div>
      <div class="row56 tap" data-act="toggleJoin"><div style="flex:1;min-width:0"><div class="l">${t('st_direct')}</div></div><span class="toggle ${I.joinOnStart ? 'on' : ''}"><i></i></span></div>
      <div class="sec">${kick(t('st_app'), 'red')}</div>
      <div class="row56"><span class="l">${t('st_lang')}</span>${langSeg()}</div>
      <div class="textblock">${t('st_mem_t', { total: num(I.memTotalGB || 0, 0), avail: num(I.memAvailGB || 0) })}</div>
      <div class="sec">${kick(t('st_about'), 'red')}</div>
      <div class="row56" data-act="versionTap"><span class="l">${t('st_ver')}</span><span class="v">${esc(I.version || '')}</span></div>
      <div style="padding:14px 16px;display:flex;flex-direction:column;gap:8px"><div style="font-size:13px;line-height:1.5;color:var(--body)">${t('st_base')}</div>
        <div class="link tap" data-act="url" data-arg="${SITE}/cxl/launcher/ios.json">${icon('external', 16)}${t('st_lic')}</div></div></div>`, overlays());
  };

  V.account = () => {
    const W = S.wallet;
    const cell = (label, value, color) => `<div style="flex:1;min-width:0;border:1px solid var(--line2);background:#0e0609;padding:14px 12px"><div class="mono" style="font-size:11px;letter-spacing:.2em;color:var(--muted);text-transform:uppercase">${label}</div>
      <div style="font-family:var(--head);font-weight:800;font-size:28px;line-height:1;color:${color};margin-top:10px;text-shadow:0 0 18px ${color}66">${W ? esc(W[label === 'CUBEX' ? 'cubex' : 'nanites']) : '—'}</div></div>`;
    return app(`${navbar(t('ac_title'), 'main')}<div class="scroll"><div style="padding:24px 16px 8px;display:flex;flex-direction:column;gap:20px">
      <div style="display:flex;align-items:center;gap:16px">${head(S.account.username, 64)}<div style="min-width:0"><div style="font-family:var(--head);font-weight:800;font-size:22px;color:#fbf2f2;overflow:hidden;text-overflow:ellipsis">${esc(S.account.username)}</div>
        <div class="mono" style="font-size:12px;color:var(--muted);margin-top:6px">cubexcraft.site</div></div></div>
      <div>${kick(t('ac_bal'))}<div style="display:flex;gap:8px;margin-top:12px">${cell('CUBEX', 0, '#fcee0a')}${cell('NANITE', 0, '#00ff9f')}</div></div></div></div>
      <div class="foot"><div class="btn tap" data-act="url" data-arg="${SITE}/profile">${icon('external')}${t('ac_site')}</div><div class="btn danger tap" data-act="dialog" data-arg="logout">${icon('logout')}${t('ac_out')}</div></div>`, overlays());
  };

  function overlays() {
    let o = '';
    if (S.sheet) {
      const keys = S.sheet === 'quality' ? ['auto', 'max', 'balanced', 'light'] : ['auto', 'on', 'off'];
      const cur = S.info[S.sheet];
      const label = (k) => (S.sheet === 'quality'
        ? (k === 'auto' ? `${t('q_auto')} <small>(${qName(S.info.qualityAuto)})</small>` : qName(k))
        : (k === 'auto' ? `${t('sh_auto')} <small>(${S.info.shadersAuto ? t('sh_on') : t('sh_off')})</small>` : k === 'on' ? t('sh_on_v') : t('sh_off_v')));
      o += `<div class="overlay" data-act="sheetClose"><div class="sheet" data-stop="1"><div class="t">${S.sheet === 'quality' ? t('st_quality') : t('st_shaders')}</div>
        ${keys.map((k) => `<div class="opt tap ${cur === k ? 'on' : ''}" data-act="pick" data-arg="${k}"><span style="flex:1">${label(k)}</span>${cur === k ? icon('check', 18, 'color:var(--red)') : ''}</div>`).join('')}</div></div>`;
    }
    if (S.dialog) {
      const D = {
        logout: [t('ac_q'), t('ac_qp'), `<div class="btn danger tap" data-act="logout">${t('ac_out')}</div><div class="btn tap" data-act="dialogClose">${t('cancel')}</div>`],
        session: [t('w_s_t'), t('w_s_p'), `<div class="btn primary tap" data-act="relogin">${t('lg_go')}</div>`],
        memory: [t('w_m_t'), t('w_m_p'), `<div class="btn tap" data-act="dialogClose">${t('w_ok')}</div>`],
      }[S.dialog];
      o += `<div class="overlay"><div class="dialog"><div style="display:flex;gap:12px;align-items:flex-start">${S.dialog === 'logout' ? '' : icon('alert', 22, 'color:var(--red);margin-top:1px')}<div class="t">${D[0]}</div></div><div class="p">${D[1]}</div><div class="b">${D[2]}</div></div></div>`;
    }
    if (S.toast) o += `<div class="toast">${esc(S.toast)}</div>`;
    return o;
  }

  // ---------- отрисовка ----------
  function render() {
    const root = document.getElementById('root');
    const sc = root.querySelector('.scroll');
    const top = sc ? sc.scrollTop : 0;
    const was = root.dataset.screen;
    root.innerHTML = (V[S.screen] || V.boot)();
    root.dataset.screen = S.screen;
    const sc2 = root.querySelector('.scroll');
    if (sc2 && was === S.screen) sc2.scrollTop = top;
    bindInputs();
  }
  function bindInputs() {
    const lg = document.getElementById('lg-login');
    const pw = document.getElementById('lg-pass');
    const upd = () => {
      S.login.login = lg.value;
      S.login.password = pw.value;
      const b = document.querySelector('[data-act="login"]');
      if (b) b.classList.toggle('off', !(S.login.login.trim() && S.login.password) || S.login.busy);
    };
    if (lg) { lg.oninput = upd; pw.oninput = upd; pw.onkeydown = (e) => { if (e.key === 'Enter') ACT.login(); }; }
    const fa = document.getElementById('fr-add');
    if (fa) { fa.oninput = () => { S.addNick = fa.value; }; fa.onkeydown = (e) => { if (e.key === 'Enter') ACT.frAdd(); }; }
  }
  function toast(text) {
    S.toast = text;
    render();
    clearTimeout(toast.t);
    toast.t = setTimeout(() => { S.toast = null; render(); }, 2600);
  }
  async function go(screen) {
    S.screen = screen;
    S.sheet = null;
    render();
    if (screen === 'account') {
      try { S.wallet = await api('/wallet'); render(); } catch (e) { /* без баланса — прочерк */ }
    }
    if (screen === 'friends' || screen === 'notifs') presence();
  }

  // ---------- действия ----------
  const ACT = {
    go: (a) => go(a),
    lang: async (l) => {
      S.lang = l;
      render();
      native('setSetting', { key: 'lang', value: l }).catch(() => {});
    },
    url: (u) => native('openURL', { url: u }),
    store: (which) => native('openStore', { app: which }),
    share: () => native('share', { url: SITE }),
    sel: (slug) => {
      S.sel = slug;
      S.modsOpen = false;
      save('sel', slug);
      render();
      loadState();
    },
    mods: () => { S.modsOpen = !S.modsOpen; render(); },
    news: (id) => { S.newsOpen = S.newsOpen === +id ? null : +id; render(); },
    async setupCheck() {
      if (S.setup.done || S.setup.checking) return;
      S.setup.checking = true;
      render();
      try {
        const j = await native('jitCheck');
        Object.assign(S.setup, { stik: j.stik, vpn: j.vpn, vpnApp: j.vpnApp });
      } catch (e) { /* оставим как было */ }
      S.setup.checking = false;
      if (S.setup.stik && S.setup.vpn) {
        S.setup.done = true;
        native('setSetting', { key: 'setupDone', value: true }).catch(() => {});
        render();
        setTimeout(() => afterSetup(), 1200);
      } else render();
    },
    async login() {
      const L = S.login;
      if (L.busy || !L.login.trim() || !L.password) return;
      Object.assign(L, { busy: true, error: null });
      render();
      try {
        const r = await native('login', { login: L.login.trim(), password: L.password });
        if (r.ok) {
          S.account = r.account;
          Object.assign(L, { busy: false, twofa: false, password: '' });
          go('main');
          loadMain();
          return;
        }
        Object.assign(L, { busy: false, twofa: false, error: r.code || 'other', message: r.message });
      } catch (e) {
        Object.assign(L, { busy: false, twofa: false, error: 'other', message: String(e.message || e) });
      }
      render();
    },
    loginCancel: () => { native('cancelLogin').catch(() => {}); Object.assign(S.login, { busy: false, twofa: false }); render(); },
    play: () => {
      const s = selected();
      const p = s && playable(s);
      if (!p) return;
      S.prep = { phase: 0, percent: 0 };
      S.screen = 'prepare';
      render();
      native('play', { title: p.title, server: s.name }).catch((e) => onEvent('prepError', { message: String(e.message || e) }));
    },
    cancelPlay: () => native('cancelPlay').catch(() => {}),
    jitRetry: () => { S.jit = { state: 'wait' }; render(); native('jitRetry').catch(() => {}); },
    jitCancel: () => { native('cancelPlay').catch(() => {}); S.jit = null; go('main'); },
    sheet: (k) => { S.sheet = k; render(); },
    sheetClose: () => { S.sheet = null; render(); },
    async pick(k) {
      const key = S.sheet;
      S.info[key] = k;
      S.sheet = null;
      render();
      try { S.info = Object.assign(S.info, await native('setSetting', { key, value: k })); render(); } catch (e) { /* останется как выбрано */ }
    },
    async toggleJoin() {
      S.info.joinOnStart = !S.info.joinOnStart;
      render();
      native('setSetting', { key: 'joinOnStart', value: S.info.joinOnStart }).catch(() => {});
    },
    versionTap: () => {
      // скрытый вход в настройки Amethyst — только для нас (7 нажатий на версию)
      S.versionTaps += 1;
      if (S.versionTaps >= 7) { S.versionTaps = 0; native('openAdvanced').catch(() => {}); }
    },
    dialog: (d) => { S.dialog = d; render(); },
    dialogClose: () => { S.dialog = null; render(); },
    async logout() {
      S.dialog = null;
      await native('logout').catch(() => {});
      S.account = null;
      S.friends = null;
      S.wallet = null;
      go('login');
    },
    relogin: () => ACT.logout(),
    async frAdd() {
      const nick = S.addNick.trim();
      if (!nick) return;
      try {
        const r = await api('/friends/request', { method: 'POST', body: { username: nick } });
        S.friends = r;
        S.addNick = '';
        toast(t('fr_sent'));
      } catch (e) { if (e.json) toast(e.json.message || e.json.error); }
    },
    async frAccept(n) { try { S.friends = await api('/friends/accept', { method: 'POST', body: { username: n } }); render(); } catch (e) { if (e.json) toast(e.json.message); } },
    async frDecline(n) { try { S.friends = await api('/friends/decline', { method: 'POST', body: { username: n } }); render(); } catch (e) { /* снимок не изменился */ } },
    async frRemove(n) { try { S.friends = await api('/friends/remove', { method: 'POST', body: { username: n } }); render(); } catch (e) { /* снимок не изменился */ } },
    async ntRead() { try { S.friends = await api('/notifications/read', { method: 'POST', body: {} }); render(); } catch (e) { /* позже */ } },
  };

  document.addEventListener('click', (e) => {
    const el = e.target.closest('[data-act]');
    if (!el) return;
    if (el.classList.contains('overlay') && e.target.closest('[data-stop]')) return;
    const f = ACT[el.dataset.act];
    if (f) f(el.dataset.arg, el);
  });

  // ---------- события от родного кода ----------
  function onEvent(type, d) {
    if (type === 'login2fa') {
      S.login.twofa = true;
      if (S.screen === 'login') render();
    } else if (type === 'prep') {
      S.prep = Object.assign(S.prep || {}, d, { error: null, cancelled: false });
      if (S.screen !== 'prepare') S.screen = 'prepare';
      render();
    } else if (type === 'prepError') {
      S.prep = Object.assign(S.prep || { phase: 1 }, { error: d.message || 'error' });
      S.screen = 'prepare';
      render();
    } else if (type === 'prepCancelled') {
      S.prep = Object.assign(S.prep || { phase: 1 }, { cancelled: true });
      render();
    } else if (type === 'jit') {
      S.jit = d;
      S.screen = 'jit';
      render();
    } else if (type === 'resume') {
      // вернулись из игры — снова главный экран
      S.prep = null;
      S.jit = null;
      if (S.account) { go('main'); loadMain(); }
    } else if (type === 'active') {
      // вернулись в приложение (из StikDebug, LocalDevVPN, App Store) — свежие проверки JIT
      Object.assign(S.setup, { stik: !!d.stik, vpn: !!d.vpn, vpnApp: !!d.vpnApp });
      if (S.screen === 'setup') {
        if (S.setup.stik && S.setup.vpn) ACT.setupCheck(); else render();
      } else if (S.screen === 'jit' && S.jit && S.jit.state === 'fail') {
        S.jit = Object.assign({}, S.jit, { stik: !!d.stik, vpn: !!d.vpn });
        render();
      }
    } else if (type === 'settings') {
      S.info = Object.assign(S.info, d);
      if (S.screen === 'settings') render();
    }
  }

  function afterSetup() {
    if (!S.account) go('login');
    else { go('main'); loadMain(); }
  }

  async function init() {
    render();
    let info = {};
    try { info = await native('init'); } catch (e) { info = {}; }
    S.info = info;
    S.lang = info.lang || ((navigator.language || 'ru').startsWith('en') ? 'en' : 'ru');
    S.account = info.account || null;
    S.setup.stik = !!(info.jit && info.jit.stik);
    S.setup.vpn = !!(info.jit && info.jit.vpn);
    S.setup.vpnApp = !!(info.jit && info.jit.vpnApp);
    if (info.lowMemory) S.dialog = 'memory';
    if (info.needsSetup && !(S.setup.stik && S.setup.vpn)) { S.screen = 'setup'; render(); return; }
    afterSetup();
  }
  init();
})();
