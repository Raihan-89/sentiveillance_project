/* Page-only interactions: navigation tabs, read-only history and image previews. */
(function () {
  'use strict';
  var page = document.body.dataset.page;
  var dialog = document.getElementById('captureDialog');
  var image = document.getElementById('captureDialogImage');
  var caption = document.getElementById('captureDialogCaption');

  window.SentiUI.openCapture = function (name, title, detail) {
    if (!name || !dialog) return;
    document.getElementById('captureDialogTitle').textContent = title || 'Face capture';
    caption.textContent = detail || '';
    image.hidden = false;
    image.alt = title || 'Selected face capture';
    image.src = 'api/snapshots/' + encodeURIComponent(name);
    if (!dialog.open) dialog.showModal();
  };
  image.addEventListener('error', function () {
    image.hidden = true;
    caption.textContent = 'This capture is no longer available. The detection record is still saved.';
  });
  document.getElementById('closeCapture').addEventListener('click', function () { dialog.close(); });
  dialog.addEventListener('click', function (event) {
    var bounds = dialog.getBoundingClientRect();
    if (event.target === dialog && (event.clientX < bounds.left || event.clientX > bounds.right || event.clientY < bounds.top || event.clientY > bounds.bottom)) dialog.close();
  });

  var fullscreen = document.getElementById('fullscreenBtn');
  if (fullscreen) fullscreen.addEventListener('click', function () {
    var panel = document.querySelector('.video-panel');
    if (document.fullscreenElement) document.exitFullscreen().catch(function () {});
    else if (panel.requestFullscreen) panel.requestFullscreen().catch(function () {});
  });

  if (page === 'people') {
    var tabs = Array.from(document.querySelectorAll('.page-tab'));
    function activate(tab) {
      tabs.forEach(function (item) {
        var selected = item === tab;
        item.classList.toggle('is-active', selected);
        item.setAttribute('aria-selected', String(selected));
        item.tabIndex = selected ? 0 : -1;
        document.getElementById(item.dataset.panel).hidden = !selected;
      });
      document.dispatchEvent(new Event('sv:people-tab'));
    }
    tabs.forEach(function (tab, index) {
      tab.tabIndex = index ? -1 : 0;
      tab.addEventListener('click', function () { activate(tab); });
      tab.addEventListener('keydown', function (event) {
        if (event.key === 'ArrowLeft' || event.key === 'ArrowRight' || event.key === 'Home' || event.key === 'End') {
          event.preventDefault();
          var next = event.key === 'Home' ? tabs[0] : event.key === 'End' ? tabs[tabs.length - 1] : tabs[(index + 1) % tabs.length];
          activate(next); next.focus();
        }
      });
    });
  }

  if (page === 'sources') {
    var upload = document.querySelector('.upload-zone');
    upload.tabIndex = 0;
    upload.setAttribute('role', 'button');
    upload.addEventListener('keydown', function (event) {
      if (event.key === 'Enter' || event.key === ' ') {
        event.preventDefault(); document.getElementById('videoFile').click();
      }
    });
  }

  if (page !== 'activity') return;
  var rows = [];
  var currentPage = 0;
  var pageSize = 15;
  var fetching = false;
  var body = document.getElementById('activityRows');
  var state = document.getElementById('activityState');
  var search = document.getElementById('activitySearch');
  var identity = document.getElementById('identityFilter');
  var eventFilter = document.getElementById('eventFilter');
  var refresh = document.getElementById('refreshActivity');
  var previous = document.getElementById('activityPrev');
  var next = document.getElementById('activityNext');
  var gallery = document.getElementById('historyGallery');

  function node(tag, text, className) {
    var element = document.createElement(tag);
    if (text != null) element.textContent = text;
    if (className) element.className = className;
    return element;
  }
  function snapshotName(path) {
    return path ? String(path).replace(/\\/g, '/').split('/').pop() : null;
  }
  function formattedTime(iso) {
    var date = new Date(iso);
    return isNaN(date.getTime()) ? 'Time unavailable' : date.toLocaleString(undefined, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit' });
  }
  function personName(row) { return row.employee || row.matchedTemplateId || 'Unknown person'; }
  function eventName(type) {
    return type === 'SUBJECT_APPEARED' ? 'Appeared' : type === 'SUBJECT_TRACK' ? 'Tracked' : type === 'SUBJECT_DISAPPEARED' ? 'Left frame' : String(type || 'Event').replace('SUBJECT_', '');
  }
  function filteredRows() {
    var query = search.value.trim().toLowerCase();
    return rows.filter(function (row) {
      var known = Boolean(row.employee || row.matchedTemplateId);
      return (identity.value === 'all' || (identity.value === 'known' ? known : !known))
        && (eventFilter.value === 'all' || row.eventType === eventFilter.value)
        && (!query || [personName(row), row.source, row.traceIndex, row.matchedTemplateId].join(' ').toLowerCase().indexOf(query) >= 0);
    });
  }
  function open(row) {
    window.SentiUI.openCapture(snapshotName(row.snapshotPath), personName(row), (row.source || 'Source') + ' · ' + formattedTime(row.eventTime) + ' · Trace ' + row.traceIndex);
  }
  function renderGallery(filtered) {
    var seen = new Set();
    var captures = filtered.filter(function (row) {
      var name = snapshotName(row.snapshotPath);
      if (!name || seen.has(name)) return false;
      seen.add(name); return true;
    }).slice(0, 12);
    gallery.textContent = '';
    document.getElementById('captureCount').textContent = captures.length + (captures.length === 1 ? ' capture' : ' captures');
    if (!captures.length) {
      var empty = node('div', null, 'empty');
      empty.innerHTML = window.SentiUI.icon('image') + '<h3>No captures to display</h3><p>Best frames appear here when completed visits have saved snapshots.</p>';
      gallery.appendChild(empty); return;
    }
    captures.forEach(function (row) {
      var figure = node('figure', null, 'shot');
      var button = node('button'); button.type = 'button';
      button.setAttribute('aria-label', 'View capture of ' + personName(row));
      var img = node('img'); img.alt = personName(row); img.loading = 'lazy';
      img.src = 'api/snapshots/' + encodeURIComponent(snapshotName(row.snapshotPath));
      button.appendChild(img); button.addEventListener('click', function () { open(row); });
      var description = node('figcaption', personName(row));
      description.appendChild(node('small', row.source || 'Unknown source'));
      figure.appendChild(button); figure.appendChild(description); gallery.appendChild(figure);
    });
  }
  function render() {
    var filtered = filteredRows();
    var pages = Math.max(1, Math.ceil(filtered.length / pageSize));
    currentPage = Math.min(currentPage, pages - 1);
    body.textContent = '';
    if (!filtered.length) {
      var emptyRow = node('tr'); var cell = node('td'); cell.colSpan = 6;
      var empty = node('div', null, 'empty');
      empty.innerHTML = '<h3>' + (rows.length ? 'No matching detections' : 'No saved activity yet') + '</h3><p>' + (rows.length ? 'Try another search or filter.' : 'Start a source and let people pass the camera to record detections.') + '</p>';
      cell.appendChild(empty); emptyRow.appendChild(cell); body.appendChild(emptyRow);
    }
    filtered.slice(currentPage * pageSize, (currentPage + 1) * pageSize).forEach(function (row) {
      var tr = node('tr');
      var who = node('td'); who.appendChild(node('span', personName(row), 'table-name'));
      who.appendChild(node('span', 'Trace ' + row.traceIndex + (row.hasFaceDetails ? ' · Face captured' : ''), 'table-sub'));
      var kind = node('td'); kind.appendChild(node('span', eventName(row.eventType), 'badge ' + (row.eventType === 'SUBJECT_APPEARED' ? 'badge-success' : 'badge-neutral')));
      var source = node('td', row.source || '—');
      var time = node('td', formattedTime(row.eventTime), 'table-time');
      var score = node('td', row.matchScore == null ? '—' : row.matchScore);
      var shot = node('td');
      if (snapshotName(row.snapshotPath)) {
        var button = node('button', null, 'capture-link'); button.type = 'button';
        button.innerHTML = window.SentiUI.icon('image') + 'View';
        button.setAttribute('aria-label', 'View capture of ' + personName(row));
        button.addEventListener('click', function () { open(row); }); shot.appendChild(button);
      } else shot.textContent = '—';
      [who, kind, source, time, score, shot].forEach(function (cell) { tr.appendChild(cell); }); body.appendChild(tr);
    });
    var from = filtered.length ? currentPage * pageSize + 1 : 0;
    var to = Math.min((currentPage + 1) * pageSize, filtered.length);
    document.getElementById('activityCount').textContent = from + '–' + to + ' of ' + filtered.length + ' matching events · ' + rows.length + ' loaded';
    document.getElementById('activityPage').textContent = 'Page ' + (currentPage + 1) + ' of ' + pages;
    previous.disabled = currentPage === 0; next.disabled = currentPage >= pages - 1;
    renderGallery(filtered);
  }
  function load() {
    if (fetching) return;
    fetching = true; refresh.disabled = true;
    state.textContent = rows.length ? 'Refreshing…' : 'Loading…';
    fetch('api/detections?limit=200', { cache: 'no-store', headers: { Accept: 'application/json' } })
      .then(function (response) { if (!response.ok) throw new Error('Unable to read activity'); return response.json(); })
      .then(function (data) {
        rows = data; state.textContent = 'Updated ' + new Date().toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
        state.className = 'badge badge-neutral'; render();
      }).catch(function () {
        state.textContent = 'Connection interrupted'; state.className = 'badge badge-danger';
        if (!rows.length) {
          body.textContent = '';
          var row = node('tr'); var cell = node('td'); cell.colSpan = 6;
          cell.appendChild(node('div', 'Could not load saved activity. Use Refresh activity to retry.', 'empty'));
          row.appendChild(cell); body.appendChild(row);
          gallery.textContent = ''; gallery.appendChild(node('div', 'Captures are unavailable until activity can be loaded.', 'empty'));
          document.getElementById('activityCount').textContent = 'Activity unavailable';
        }
      }).then(function () { fetching = false; refresh.disabled = false; });
  }
  [search, identity, eventFilter].forEach(function (element) {
    element.addEventListener(element === search ? 'input' : 'change', function () { currentPage = 0; render(); });
  });
  previous.addEventListener('click', function () { if (currentPage > 0) { currentPage--; render(); } });
  next.addEventListener('click', function () { currentPage++; render(); });
  refresh.addEventListener('click', load);
  load();
  setInterval(function () { if (!document.hidden) load(); }, 10000);
}());
