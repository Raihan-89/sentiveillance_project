/* Shared presentation shell. All source and recognition operations remain in app.js. */
(function () {
  'use strict';
  var paths = {
    monitor: '<rect x="3" y="4" width="18" height="13" rx="2"/><path d="M8 21h8m-4-4v4"/>',
    camera: '<path d="M14 4h-4L8 7H5a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-3z"/><circle cx="12" cy="13" r="4"/>',
    users: '<path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2m20 0v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75"/><circle cx="9" cy="7" r="4"/>',
    'user-plus': '<path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2m18-13v6m-3-3h6"/><circle cx="9" cy="7" r="4"/>',
    activity: '<path d="M3 12h4l3-8 4 16 3-8h4"/>',
    settings: '<path d="M9 3h6l1 3 3 1 2 5-2 5-3 1-1 3H9l-1-3-3-1-2-5 2-5 3-1z"/><circle cx="12" cy="12" r="3"/>',
    play: '<path d="m8 5 11 7-11 7z"/>', pause: '<path d="M8 5v14M16 5v14"/>',
    plus: '<path d="M12 5v14M5 12h14"/>', search: '<circle cx="10.5" cy="10.5" r="6.5"/><path d="m16 16 4 4"/>',
    arrow: '<path d="M5 12h14m-6-6 6 6-6 6"/>', check: '<path d="m5 12 4 4L19 6"/>',
    shield: '<path d="m12 3 8 3v6c0 5-8 9-8 9s-8-4-8-9V6z"/><path d="m8 12 3 3 5-6"/>',
    clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
    refresh: '<path d="M20 7v5h-5M4 17v-5h5"/><path d="M6 7a7 7 0 0 1 11-2l3 3M4 16l3 3a7 7 0 0 0 11-2"/>',
    expand: '<path d="M8 3H3v5m13-5h5v5M3 16v5h5m13-5v5h-5"/>',
    moon: '<path d="M20.8 13a9 9 0 0 1-9.8-9.8A9 9 0 1 0 20.8 13z"/>',
    sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1.5 1.5m11 11L19 19M5 19l1.5-1.5m11-11L19 5"/>',
    menu: '<path d="M4 6h16M4 12h16M4 18h16"/>', close: '<path d="m6 6 12 12M6 18 18 6"/>',
    upload: '<path d="M12 16V3m-5 5 5-5 5 5M4 16v5h16v-5"/>',
    video: '<rect x="3" y="5" width="13" height="14" rx="2"/><path d="m16 10 5-3v10l-5-3"/>',
    signal: '<path d="M5 20v-4m5 4v-8m5 8V8m5 12V4"/>',
    trash: '<path d="M3 6h18M9 6V3h6v3M5 6l1 15h12l1-15M10 10v7m4-7v7"/>',
    filter: '<path d="M3 4h18l-7 8v7l-4 2v-9z"/>', image: '<rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="8" cy="8" r="1"/><path d="m21 15-6-6L3 21"/>',
    info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v6m0-10h.01"/>',
    database: '<ellipse cx="12" cy="5" rx="8" ry="3"/><path d="M4 5v14a8 3 0 0 0 16 0V5M4 12a8 3 0 0 0 16 0"/>',
    'chevron-left': '<path d="m14 6-6 6 6 6"/>', 'chevron-right': '<path d="m10 6 6 6-6 6"/>'
  };
  function icon(name) { return '<svg class="icon" aria-hidden="true"><use href="#i-' + name + '"></use></svg>'; }
  window.SentiUI = { icon: icon };
  var pages = [
    ['monitor', 'index.html', 'Live monitor', 'monitor'],
    ['sources', 'sources.html', 'Sources', 'camera'],
    ['people', 'people.html', 'People', 'users'],
    ['activity', 'activity.html', 'Activity', 'activity'],
    ['system', 'system.html', 'System status', 'settings']
  ];
  var current = document.body.dataset.page || 'monitor';
  var title = pages.filter(function (p) { return p[0] === current; })[0][2];
  var symbols = Object.keys(paths).map(function (name) {
    return '<symbol id="i-' + name + '" viewBox="0 0 24 24">' + paths[name] + '</symbol>';
  }).join('');
  document.getElementById('appShell').innerHTML =
    '<svg class="icon-sprite" xmlns="http://www.w3.org/2000/svg" aria-hidden="true" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round">' + symbols + '</svg>' +
    '<button class="sidebar-backdrop" id="sidebarBackdrop" type="button" aria-label="Close navigation" hidden></button>' +
    '<aside class="app-sidebar" id="appSidebar"><a class="brand" href="index.html" aria-label="SentiVeillance live monitor"><span class="brand-mark"><svg viewBox="0 0 32 32" aria-hidden="true"><path d="M11 5H5v6m16-6h6v6M5 21v6h6m16-6v6h-6"/><circle cx="16" cy="16" r="5"/></svg></span><span>SentiVeillance<small>OPERATIONS CONSOLE</small></span></a>' +
    '<div class="nav-section-label">WORKSPACE</div><nav class="main-navigation" aria-label="Main navigation">' + pages.map(function (p) {
      return '<a href="' + p[1] + '"' + (p[0] === current ? ' class="nav-link is-active" aria-current="page"' : ' class="nav-link"') + '>' + icon(p[3]) + '<span>' + p[2] + '</span>' + (p[0] === 'people' ? '<span class="nav-count" id="navPending" hidden></span>' : '') + '</a>';
    }).join('') + '</nav><div class="sidebar-bottom"><div class="sidebar-source"><span class="nav-section-label">ACTIVE SOURCE</span><p id="sourceLine">Waiting for source…</p><span class="pill" id="runPill"><span class="dot"></span><span id="runText">Checking status</span></span></div><button class="theme-button" id="themeToggle" type="button" aria-pressed="false">' + icon('moon') + '<span id="themeLabel">Dark appearance</span></button><div class="sidebar-signature">' + icon('shield') + '<span>Surveillance workspace</span></div></div></aside>' +
    '<header class="app-topbar"><div class="topbar-left"><button class="icon-button mobile-menu" id="menuToggle" type="button" aria-label="Open navigation" aria-controls="appSidebar" aria-expanded="false">' + icon('menu') + '</button><span class="breadcrumb-root">Workspace</span><span class="breadcrumb-separator">/</span><span class="breadcrumb-current">' + title + '</span></div><div class="topbar-right"><span class="connection-status" id="connectionStatus"><span class="dot"></span><span id="connectionText">Connecting</span></span><span class="topbar-divider"></span><time id="headerDate"></time></div></header>';
  var toggle = document.getElementById('menuToggle');
  var backdrop = document.getElementById('sidebarBackdrop');
  function menu(open) {
    document.body.classList.toggle('menu-open', open);
    toggle.setAttribute('aria-expanded', String(open));
    toggle.setAttribute('aria-label', open ? 'Close navigation' : 'Open navigation');
    backdrop.hidden = !open;
    document.getElementById('mainContent').inert = open;
    if (open) document.querySelector('.nav-link[aria-current]').focus();
    else toggle.focus();
  }
  toggle.addEventListener('click', function () { menu(!document.body.classList.contains('menu-open')); });
  backdrop.addEventListener('click', function () { menu(false); });
  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape' && document.body.classList.contains('menu-open')) menu(false);
  });
  window.addEventListener('resize', function () { if (innerWidth > 820 && document.body.classList.contains('menu-open')) menu(false); });
  document.addEventListener('keydown', function (event) {
    if (event.key !== 'Tab' || !document.body.classList.contains('menu-open')) return;
    var focusable = [toggle].concat(Array.from(document.querySelectorAll('#appSidebar a, #appSidebar button')));
    var index = focusable.indexOf(document.activeElement);
    if (event.shiftKey && index <= 0) { event.preventDefault(); focusable[focusable.length - 1].focus(); }
    else if (!event.shiftKey && index === focusable.length - 1) { event.preventDefault(); toggle.focus(); }
  });
  var themeToggle = document.getElementById('themeToggle');
  function renderTheme() {
    var dark = document.documentElement.dataset.theme === 'dark';
    themeToggle.setAttribute('aria-pressed', String(dark));
    document.getElementById('themeLabel').textContent = dark ? 'Light appearance' : 'Dark appearance';
    themeToggle.querySelector('use').setAttribute('href', dark ? '#i-sun' : '#i-moon');
  }
  themeToggle.addEventListener('click', function () {
    var theme = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
    document.documentElement.dataset.theme = theme;
    try { localStorage.setItem('sv-theme', theme); } catch (e) { /* private browsing */ }
    renderTheme();
  });
  renderTheme();
  document.getElementById('headerDate').textContent = new Date().toLocaleDateString(undefined, { weekday: 'short', month: 'short', day: 'numeric', year: 'numeric' });
  document.getElementById('dismissNotice').addEventListener('click', function () { document.getElementById('operationNotice').hidden = true; });
}());
