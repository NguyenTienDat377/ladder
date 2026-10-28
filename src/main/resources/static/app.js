'use strict';

/*
 * Shared by every page: API calls (with CSRF), the header, video cards, formatting.
 *
 * No framework and no build step: each page is a static HTML file served by
 * Spring, plus a small module script that uses window.Ladder.
 *
 * Security rule for this file: anything a user wrote (titles, names,
 * descriptions) goes into the page through textContent, never innerHTML, so a
 * video titled "<img onerror=...>" is just text. innerHTML is used only for
 * the fixed icon strings below.
 */
window.Ladder = (() => {
  // ------------------------------------------------------------------ API

  class ApiError extends Error {
    constructor(status) {
      super('HTTP ' + status);
      this.status = status;
    }
  }

  function cookie(name) {
    const match = document.cookie.match(new RegExp('(?:^|; )' + name + '=([^;]*)'));
    return match ? decodeURIComponent(match[1]) : null;
  }

  // The server sets the CSRF token as a cookie on any request and wants it back
  // in a header on every write. Logging in clears the cookie, so a write right
  // after login first makes a cheap GET to receive a fresh one.
  async function csrfToken() {
    let token = cookie('XSRF-TOKEN');
    if (!token) {
      await fetch('/api/v1/auth/me', { credentials: 'same-origin' });
      token = cookie('XSRF-TOKEN');
    }
    return token;
  }

  async function api(method, path, { json, form } = {}) {
    const headers = {};
    let body;
    if (json !== undefined) {
      headers['Content-Type'] = 'application/json';
      body = JSON.stringify(json);
    } else if (form) {
      body = new URLSearchParams(form);
    }
    if (method !== 'GET') {
      headers['X-XSRF-TOKEN'] = await csrfToken();
    }
    const response = await fetch(path, { method, headers, body, credentials: 'same-origin' });
    if (!response.ok) {
      throw new ApiError(response.status);
    }
    const text = await response.text();
    return text ? JSON.parse(text) : null;
  }

  // ------------------------------------------------------------- session

  let mePromise = null;

  /** The logged-in user, or null. Cached for the page; pass true to ask again. */
  function me(refresh = false) {
    if (!mePromise || refresh) {
      mePromise = api('GET', '/api/v1/auth/me').catch(error => {
        if (error.status === 401) return null;
        throw error;
      });
    }
    return mePromise;
  }

  async function login(email, password) {
    await api('POST', '/api/v1/auth/login', { form: { email, password } });
    return me(true); // also fetches the new CSRF cookie the login cleared
  }

  async function logout() {
    await api('POST', '/api/v1/auth/logout');
    location.href = '/';
  }

  function loginUrl() {
    return '/login.html?next=' + encodeURIComponent(location.pathname + location.search);
  }

  /** Only same-site paths, so a crafted ?next= can't send someone to another site after login. */
  function safeNext(next) {
    return next && next.startsWith('/') && !next.startsWith('//') && !next.startsWith('/\\') ? next : '/';
  }

  // -------------------------------------------------------------- DOM

  /** el('a', { class: 'x', href: '/' }, 'text', child): builds elements safely. */
  function el(tag, props = {}, ...children) {
    const node = document.createElement(tag);
    for (const [key, value] of Object.entries(props)) {
      if (value === null || value === undefined || value === false) continue;
      if (key === 'class') node.className = value;
      else if (key === 'text') node.textContent = value;
      else if (key.startsWith('on')) node.addEventListener(key.slice(2), value);
      else node.setAttribute(key, value === true ? '' : value);
    }
    for (const child of children.flat()) {
      if (child === null || child === undefined || child === false) continue;
      node.append(child instanceof Node ? child : String(child));
    }
    return node;
  }

  const ICONS = {
    ladder: '<path d="M7 3v18M17 3v18M7 7.5h10M7 12h10M7 16.5h10"/>',
    plus: '<path d="M12 5v14M5 12h14"/>',
    upload: '<path d="M12 16V4M7 9l5-5 5 5M5 20h14"/>',
    like: '<path d="M7 11v9H4v-9h3zm0 0 4-7a2 2 0 0 1 3 2l-1 5h5.5a2 2 0 0 1 2 2.4l-1.3 6A2 2 0 0 1 17.2 21H7"/>',
    dislike: '<path d="M17 13V4h3v9h-3zm0 0-4 7a2 2 0 0 1-3-2l1-5H5.5a2 2 0 0 1-2-2.4l1.3-6A2 2 0 0 1 6.8 3H17"/>',
    link: '<path d="M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1"/>',
    lock: '<rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V7a4 4 0 0 1 8 0v4"/>',
    unlisted: '<path d="M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1"/>',
    clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
    alert: '<circle cx="12" cy="12" r="9"/><path d="M12 7v6M12 16.5v.5"/>',
    home: '<path d="M4 11 12 4l8 7v9h-5v-6H9v6H4z"/>',
    channel: '<rect x="3" y="5" width="18" height="14" rx="2"/><path d="M10 9.5v5l4-2.5z"/>',
    out: '<path d="M15 4h4v16h-4M10 8l-4 4 4 4M6 12h10"/>',
    search: '<circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/>',
  };

  function icon(name, size = 20) {
    const span = el('span', { 'aria-hidden': 'true', style: 'display:inline-flex' });
    span.innerHTML = `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      stroke-width="2" stroke-linecap="round" stroke-linejoin="round">${ICONS[name]}</svg>`;
    return span;
  }

  // A channel's colour comes from its handle, so it is stable everywhere it appears.
  const AVATAR_COLORS = ['#f5c400', '#f2a65a', '#7cc7a6', '#8fb8f0', '#d9a5e8', '#e8e3d4'];

  function avatar(name, handle, size = '') {
    let hash = 0;
    for (const ch of handle || name || '?') hash = (hash * 31 + ch.codePointAt(0)) >>> 0;
    const initial = (name || handle || '?').trim().charAt(0).toUpperCase() || '?';
    return el('span', {
      class: 'avatar' + (size ? ' avatar-' + size : ''),
      'aria-hidden': 'true',
      style: 'background:' + AVATAR_COLORS[hash % AVATAR_COLORS.length],
      text: initial,
    });
  }

  // ------------------------------------------------------------ format

  const compactNumber = new Intl.NumberFormat('en', { notation: 'compact', maximumFractionDigits: 1 });
  const relative = new Intl.RelativeTimeFormat('en', { numeric: 'auto' });

  function number(n) { return compactNumber.format(n); }

  function count(n, one, many) {
    return n === 1 ? '1 ' + one : number(n) + ' ' + many;
  }

  function views(n) { return count(n, 'view', 'views'); }

  function ago(iso) {
    const seconds = (new Date(iso).getTime() - Date.now()) / 1000;
    const units = [['year', 31536000], ['month', 2592000], ['week', 604800], ['day', 86400],
      ['hour', 3600], ['minute', 60]];
    for (const [unit, size] of units) {
      if (Math.abs(seconds) >= size) return relative.format(Math.round(seconds / size), unit);
    }
    return 'just now';
  }

  function longDate(iso) {
    return new Date(iso).toLocaleDateString('en', { year: 'numeric', month: 'short', day: 'numeric' });
  }

  function duration(total) {
    if (total === null || total === undefined) return null;
    const h = Math.floor(total / 3600);
    const m = Math.floor((total % 3600) / 60);
    const s = String(total % 60).padStart(2, '0');
    return h ? `${h}:${String(m).padStart(2, '0')}:${s}` : `${m}:${s}`;
  }

  const VISIBILITY = {
    PUBLIC: { label: 'Public', help: 'Anyone can watch, and it shows on the home page.' },
    UNLISTED: { label: 'Unlisted', help: 'Anyone with the link can watch. Not on the home page.' },
    PRIVATE: { label: 'Private', help: 'Only you can watch.' },
  };

  // ------------------------------------------------------------ pieces

  function thumbnail(item) {
    const box = el('div', { class: 'thumb' });
    if (item.thumbnailUrl) {
      box.append(el('img', { src: item.thumbnailUrl, alt: '', loading: 'lazy' }));
    } else if (item.status === 'FAILED') {
      box.append(el('div', { class: 'thumb-empty failed' }, icon('alert', 28), 'Processing failed'));
    } else if (item.status && item.status !== 'READY') {
      box.append(el('div', { class: 'thumb-empty' }, icon('clock', 28), 'Processing'));
    } else {
      box.append(el('div', { class: 'thumb-empty' }, icon('ladder', 32)));
    }
    const length = duration(item.durationSecs);
    if (length) box.append(el('span', { class: 'duration', text: length }));
    return box;
  }

  /** A grid card. Unfinished videos aren't links: there is nothing to watch yet. */
  function videoCard(item, { showChannel = true } = {}) {
    const ready = !item.status || item.status === 'READY';
    const meta = el('div', { class: 'card-meta' },
      showChannel ? avatar(item.channelName, item.channelHandle) : null,
      el('div', { class: 'card-text' },
        el('h3', { class: 'card-title', text: item.title }),
        showChannel ? el('span', { class: 'card-sub', text: item.channelName }) : null,
        el('span', { class: 'card-sub', text: views(item.viewCount) + ' · ' + ago(item.createdAt) })));
    const body = ready
      ? el('a', { class: 'card-link', href: '/watch.html?id=' + item.videoId }, thumbnail(item), meta)
      : el('div', { class: 'card-link' }, thumbnail(item), meta);
    return el('article', { class: 'card' }, body);
  }

  function emptyState(title, text, action) {
    return el('div', { class: 'empty' }, icon('ladder', 40), el('h2', { text: title }),
      text ? el('p', { text }) : null, action || null);
  }

  // ------------------------------------------------------------ header

  /** Makes `button` toggle `menu`; outside clicks and Escape close it. */
  function popover(button, menu) {
    const close = () => {
      menu.hidden = true;
      button.setAttribute('aria-expanded', 'false');
    };
    document.addEventListener('click', event => {
      if (!menu.hidden && !menu.contains(event.target) && !button.contains(event.target)) close();
    });
    document.addEventListener('keydown', event => {
      if (event.key === 'Escape' && !menu.hidden) {
        close();
        button.focus();
      }
    });
    button.addEventListener('click', () => {
      const open = menu.hidden;
      menu.hidden = !open;
      button.setAttribute('aria-expanded', String(open));
    });
  }

  function accountMenu(user) {
    const channel = user.channels[0];
    const button = el('button', {
      class: 'avatar', type: 'button', 'aria-haspopup': 'true', 'aria-expanded': 'false',
      'aria-label': 'Account menu for ' + user.userName,
      style: avatar(user.userName, user.userHandle).getAttribute('style'),
      text: user.userName.trim().charAt(0).toUpperCase(),
    });
    const menu = el('div', { class: 'menu', hidden: true },
      el('div', { class: 'menu-who' }, el('strong', { text: user.userName }), el('span', { text: '@' + user.userHandle })),
      channel ? el('a', { href: '/channel.html?handle=' + encodeURIComponent(channel.channelHandle) },
        icon('channel'), 'Your channel') : null,
      el('a', { href: '/upload.html' }, icon('upload'), 'Upload a video'),
      el('button', { type: 'button', onclick: () => logout() }, icon('out'), 'Sign out'));
    popover(button, menu);
    return el('div', { class: 'account' }, button, menu);
  }

  /** Fills <header id="site-header"> and resolves to the logged-in user (or null). */
  async function header() {
    const root = document.getElementById('site-header');
    root.className = 'site-header';
    const brand = el('a', { class: 'brand', href: '/' }, icon('ladder', 26), el('span', { text: 'ladder' }));
    const initialQuery = location.pathname === '/' || location.pathname === '/index.html'
      ? (new URLSearchParams(location.search).get('q') || '').trim()
      : '';
    const input = el('input', {
      class: 'search-input', type: 'search', name: 'q', value: initialQuery,
      placeholder: 'Search videos', 'aria-label': 'Search videos', maxlength: '200',
    });
    const searchForm = el('form', {
      class: 'search-bar', role: 'search', action: '/',
      onsubmit: event => {
        event.preventDefault();
        const q = input.value.trim();
        location.href = q ? '/?q=' + encodeURIComponent(q) : '/';
      },
    }, input, el('button', { class: 'search-btn', type: 'submit', 'aria-label': 'Search' }, icon('search', 18)));
    root.replaceChildren(brand, searchForm);
    let user = null;
    try {
      user = await me();
    } catch {
      // The site still works logged out if /me is unreachable.
    }
    const actions = user
      ? el('nav', { class: 'header-actions', 'aria-label': 'Account' },
          el('a', { class: 'btn', href: '/upload.html' }, icon('plus'), el('span', { class: 'btn-label', text: 'Upload' })),
          accountMenu(user))
      : el('nav', { class: 'header-actions', 'aria-label': 'Account' },
          el('a', { class: 'btn btn-primary', href: loginUrl() }, 'Sign in'));
    root.append(actions);
    return user;
  }

  /** Replaces the page body with a centred message, for "not found" and errors. */
  function notice(main, title, text, action) {
    main.replaceChildren(el('div', { class: 'notice-page' }, icon('ladder', 40),
      el('h1', { text: title }), text ? el('p', { text }) : null, action || null));
  }

  return {
    ApiError, api, me, login, logout, loginUrl, safeNext,
    el, icon, avatar, thumbnail, videoCard, emptyState, header, notice,
    popover, views, number, count, ago, longDate, duration, VISIBILITY,
  };
})();
