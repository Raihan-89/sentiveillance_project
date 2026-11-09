/* SentiVeillance console.
 *
 * Three data paths:
 *   - the video is an MJPEG <img>, decoded by the browser, nothing to drive here;
 *   - detections arrive over SSE (/api/live/events) and drive the feed and the overlay;
 *   - /api/status is polled as the authoritative counter source, so the tiles stay
 *     correct even if a browser missed events while the tab was in the background.
 */
(function () {
  'use strict';

  var POLL_MILLIS = 2000;
  /* Matches sentiveillance.live.preview-interval-millis; asking faster only wastes requests. */
  var FRAME_INTERVAL_MILLIS = 200;
  var BOX_LIFETIME_MILLIS = 1200;   // how long a box stays after its last update
  var FEED_LIMIT = 60;

  var el = {
    sourceLine: document.getElementById('sourceLine'),
    runPill: document.getElementById('runPill'),
    runText: document.getElementById('runText'),
    startBtn: document.getElementById('startBtn'),
    stopBtn: document.getElementById('stopBtn'),
    events: document.getElementById('mEvents'),
    subjects: document.getElementById('mSubjects'),
    identified: document.getElementById('mIdentified'),
    stored: document.getElementById('mStored'),
    watch: document.getElementById('mWatch'),
    nonHuman: document.getElementById('mNonHuman'),
    lag: document.getElementById('mLag'),
    pending: document.getElementById('mPending'),
    candidates: document.getElementById('candidates'),
    stage: document.getElementById('stage'),
    stageEmptyTitle: document.getElementById('stageEmptyTitle'),
    stageEmptyHint: document.getElementById('stageEmptyHint'),
    video: document.getElementById('video'),
    overlay: document.getElementById('overlay'),
    frameInfo: document.getElementById('frameInfo'),
    feed: document.getElementById('feed'),
    feedState: document.getElementById('feedState'),
    watchList: document.getElementById('watchList'),
    watchState: document.getElementById('watchState'),
    gallery: document.getElementById('gallery')
  };

  /* traceIndex -> { rect, label, identified, at } */
  var active = new Map();
  var sourceDims = { width: 0, height: 0 };
  var seenShots = new Set();

  // ---------------------------------------------------------------- helpers

  /* no-store on every JSON read: these endpoints carry live counters and have no
     Cache-Control header of their own, which leaves a browser free to serve a heuristically
     cached copy and make the dashboard look frozen. */
  function getJson(url) {
    return fetch(url, {
      cache: 'no-store',
      headers: { Accept: 'application/json' }
    }).then(function (r) {
      if (!r.ok) { throw new Error(url + ' -> ' + r.status); }
      return r.json();
    });
  }

  function clockOf(iso) {
    var d = iso ? new Date(iso) : new Date();
    if (isNaN(d.getTime())) { d = new Date(); }
    return d.toLocaleTimeString([], { hour12: false });
  }

  function initials(name) {
    return String(name || '?').trim().split(/\s+/).slice(0, 2)
      .map(function (p) { return p.charAt(0).toUpperCase(); }).join('');
  }

  // ------------------------------------------------------------ video stage

  /* The feed used to be <img src="api/live/stream.mjpg">. MJPEG is a multipart response
     that never ends, and an <img> is a document subresource, so the browser's load
     indicator spun forever: the page looked permanently "loading" because it was waiting on
     a request that by design can never finish.

     Each frame is fetched as its own request instead and swapped in as a blob URL. Every
     request completes, so the page reaches a finished state; the picture updates as before.
     /api/live/stream.mjpg is left in place for anything that wants a real stream (VLC, ffplay).

     The previous blob URL is revoked only once its replacement has decoded - revoking it
     straight away can leave the <img> pointing at a URL that has already been released. */
  var framePending = false;
  var staleBlobUrl = null;

  function startVideo() {
    el.video.addEventListener('load', function () {
      markFrameArrived();
      if (staleBlobUrl) {
        URL.revokeObjectURL(staleBlobUrl);
        staleBlobUrl = null;
      }
    });
    pollFrame();
    setInterval(pollFrame, FRAME_INTERVAL_MILLIS);
  }

  function pollFrame() {
    // Never queue requests: if one is still in flight the next tick is simply skipped.
    if (framePending) { return; }
    framePending = true;
    fetch('api/live/frame.jpg', { cache: 'no-store' })
      .then(function (r) { return r.status === 200 ? r.blob() : null; })
      .then(function (blob) {
        if (!blob) { return; }           // 204 = nothing captured yet
        var url = URL.createObjectURL(blob);
        if (el.video.src && el.video.src.lastIndexOf('blob:', 0) === 0) {
          staleBlobUrl = el.video.src;
        }
        el.video.src = url;
      })
      .catch(function () { /* transient - the next tick retries */ })
      .then(function () { framePending = false; });
  }

  function markFrameArrived() {
    el.stage.classList.add('has-frame');
  }

  function resizeOverlay() {
    var w = el.video.clientWidth;
    var h = el.video.clientHeight;
    if (!w || !h) { return false; }
    var dpr = window.devicePixelRatio || 1;
    if (el.overlay.width !== Math.round(w * dpr) || el.overlay.height !== Math.round(h * dpr)) {
      el.overlay.width = Math.round(w * dpr);
      el.overlay.height = Math.round(h * dpr);
    }
    return true;
  }

  function drawOverlay() {
    requestAnimationFrame(drawOverlay);
    var ctx = el.overlay.getContext('2d');
    if (!ctx || !resizeOverlay()) { return; }

    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.clearRect(0, 0, el.overlay.width, el.overlay.height);

    // Natural size of the decoded frame is the most reliable source scale; fall back to
    // whatever /api/live/meta reported.
    var srcW = el.video.naturalWidth || sourceDims.width;
    var srcH = el.video.naturalHeight || sourceDims.height;
    if (!srcW || !srcH) { return; }

    if (el.video.naturalWidth) { markFrameArrived(); }

    var scaleX = el.overlay.width / srcW;
    var scaleY = el.overlay.height / srcH;
    var now = Date.now();
    var font = Math.max(11, Math.round(el.overlay.width / 64));

    active.forEach(function (entry, trace) {
      if (now - entry.at > BOX_LIFETIME_MILLIS) { active.delete(trace); return; }

      var x = entry.rect.left * scaleX;
      var y = entry.rect.top * scaleY;
      var w = entry.rect.width * scaleX;
      var h = entry.rect.height * scaleY;
      var colour = entry.identified ? '#37d67a' : '#ffb347';

      ctx.lineWidth = Math.max(2, el.overlay.width / 480);
      ctx.strokeStyle = colour;
      ctx.strokeRect(x, y, w, h);

      ctx.font = '600 ' + font + 'px ui-sans-serif, system-ui, sans-serif';
      var text = entry.label;
      var padding = font * 0.4;
      var textWidth = ctx.measureText(text).width;
      var boxH = font + padding * 2;
      var boxY = y - boxH < 0 ? y + h : y - boxH;

      ctx.fillStyle = colour;
      ctx.fillRect(x, boxY, textWidth + padding * 2, boxH);
      ctx.fillStyle = '#06101c';
      ctx.textBaseline = 'middle';
      ctx.fillText(text, x + padding, boxY + boxH / 2);
    });
  }

  // -------------------------------------------------------------- event feed

  function labelFor(detection) {
    if (detection.employee) {
      return detection.employee + (detection.matchScore != null ? ' · ' + detection.matchScore : '');
    }
    if (detection.matchedTemplateId) { return detection.matchedTemplateId; }
    return (detection.objectType || 'subject') + ' #' + detection.traceIndex;
  }

  function addFeedRow(detection) {
    var li = document.createElement('li');

    var tag = document.createElement('span');
    var type = String(detection.eventType || '');
    tag.className = 'tag ' + (type === 'SUBJECT_APPEARED' ? 'tag-appeared'
      : type === 'SUBJECT_DISAPPEARED' ? 'tag-disappeared' : '');
    tag.textContent = type.replace('SUBJECT_', '') || 'EVENT';

    var who = document.createElement('span');
    var known = Boolean(detection.employee || detection.matchedTemplateId);
    who.className = 'who ' + (known ? 'is-known' : 'is-unknown');
    who.textContent = known
      ? (detection.employee || detection.matchedTemplateId)
      : 'unknown · trace ' + detection.traceIndex;

    var meta = document.createElement('span');
    meta.className = 'meta';
    var bits = [clockOf(detection.eventTime)];
    if (detection.matchScore != null) { bits.unshift('score ' + detection.matchScore); }
    if (detection.hasFaceDetails) { bits.unshift('face'); }
    meta.textContent = bits.join(' · ');

    li.appendChild(tag);
    li.appendChild(who);
    li.appendChild(meta);

    el.feed.insertBefore(li, el.feed.firstChild);
    while (el.feed.childElementCount > FEED_LIMIT) {
      el.feed.removeChild(el.feed.lastElementChild);
    }
  }

  function addShot(name, caption) {
    if (!name || seenShots.has(name)) { return; }
    seenShots.add(name);

    var fig = document.createElement('figure');
    fig.className = 'shot';

    var img = document.createElement('img');
    img.src = 'api/snapshots/' + encodeURIComponent(name);
    img.alt = caption || 'capture';
    img.loading = 'lazy';

    var cap = document.createElement('figcaption');
    cap.textContent = caption || 'unknown';

    fig.appendChild(img);
    fig.appendChild(cap);
    el.gallery.insertBefore(fig, el.gallery.firstChild);

    while (el.gallery.childElementCount > 12) {
      el.gallery.removeChild(el.gallery.lastElementChild);
    }
  }

  function onDetection(detection) {
    addFeedRow(detection);

    if (detection.rect) {
      active.set(detection.traceIndex, {
        rect: detection.rect,
        label: labelFor(detection),
        identified: Boolean(detection.employee || detection.matchedTemplateId),
        at: Date.now()
      });
    }
    if (detection.eventType === 'SUBJECT_DISAPPEARED') {
      active.delete(detection.traceIndex);
      addShot(detection.snapshot, detection.employee || 'unknown');
    }
  }

  function openStream() {
    var stream = new EventSource('api/live/events');

    stream.addEventListener('open', function () {
      el.feedState.textContent = 'live';
    });
    stream.addEventListener('detection', function (e) {
      try { onDetection(JSON.parse(e.data)); } catch (err) { /* ignore a bad frame */ }
    });
    stream.addEventListener('error', function () {
      // EventSource reconnects on its own; just reflect the state.
      el.feedState.textContent = 'reconnecting…';
    });
  }

  // ------------------------------------------------------------------ panels

  function renderStatus(status) {
    el.events.textContent = status.events;
    el.subjects.textContent = status.subjects;
    el.identified.textContent = status.identified;
    el.stored.textContent = status.storedEvents;
    el.watch.textContent = status.watchListSize;
    el.nonHuman.textContent = status.nonHumanIgnored;
    // Milliseconds under a second, otherwise seconds - a 4-digit ms value is unreadable.
    el.pending.textContent = status.unknownPending;
    var lag = status.detectionLagMillis;
    el.lag.textContent = lag == null ? '—'
      : lag < 1000 ? lag + 'ms' : (lag / 1000).toFixed(1) + 's';

    var running = Boolean(status.running);
    el.runPill.className = 'pill ' + (running ? 'is-running' : 'is-stopped');
    el.runText.textContent = running ? 'capturing' : 'stopped';
    el.startBtn.disabled = running;
    el.stopBtn.disabled = !running;

    // Capture no longer starts on its own, so distinguish "not started yet" from
    // "started, first frame still on its way".
    if (running) {
      el.stageEmptyTitle.textContent = 'Waiting for the first frame';
      el.stageEmptyHint.textContent = 'Frames are only encoded while this page is open.';
    } else {
      el.stageEmptyTitle.textContent = 'Source is stopped';
      el.stageEmptyHint.textContent = 'Press \u201cStart source\u201d to begin capturing.';
      el.stage.classList.remove('has-frame');
    }
  }

  function renderMeta(meta) {
    sourceDims.width = meta.frameWidth;
    sourceDims.height = meta.frameHeight;
    el.sourceLine.textContent = meta.sourceName + ' · ' + meta.sourceType;
    el.frameInfo.textContent = meta.frameWidth
      ? meta.frameWidth + '×' + meta.frameHeight
      : '';
  }

  function renderEmployees(list) {
    el.watchState.textContent = list.length + ' enrolled';
    el.watchList.textContent = '';
    if (!list.length) {
      var empty = document.createElement('p');
      empty.className = 'empty';
      empty.textContent = 'Nobody enrolled - drop Employee_<n>_<name>.png files in the photo directory.';
      el.watchList.appendChild(empty);
      return;
    }
    list.forEach(function (e) {
      var card = document.createElement('div');
      card.className = 'card';

      var avatar = document.createElement('div');
      avatar.className = 'avatar';
      avatar.textContent = initials(e.name);

      var box = document.createElement('div');
      var name = document.createElement('div');
      name.className = 'card-name';
      name.textContent = e.name;
      var meta = document.createElement('div');
      meta.className = 'card-meta';
      meta.textContent = e.templateId + ' · ' + e.templateBytes + ' B';
      box.appendChild(name);
      box.appendChild(meta);

      card.appendChild(avatar);
      card.appendChild(box);
      el.watchList.appendChild(card);
    });
  }

  /* People the engine saw with a face but could not match. Naming one posts it straight
     into the running watch list, so recognition starts on their next appearance - there is
     no photo to supply and no restart. */
  function renderCandidates(rows) {
    el.candidates.textContent = '';
    if (!rows.length) {
      var empty = document.createElement('p');
      empty.className = 'empty';
      empty.textContent = 'Nobody waiting. Unmatched people with a usable face appear here '
        + 'when they leave the frame.';
      el.candidates.appendChild(empty);
      return;
    }
    rows.forEach(function (row) { el.candidates.appendChild(candidateCard(row)); });
  }

  function candidateCard(row) {
    var card = document.createElement('div');
    card.className = 'candidate';

    if (row.snapshot) {
      var img = document.createElement('img');
      img.src = 'api/snapshots/' + encodeURIComponent(row.snapshot);
      img.alt = 'unidentified person, trace ' + row.traceIndex;
      img.loading = 'lazy';
      card.appendChild(img);
    } else {
      var none = document.createElement('div');
      none.className = 'no-shot';
      none.textContent = 'no snapshot';
      card.appendChild(none);
    }

    var body = document.createElement('div');
    body.className = 'candidate-body';

    var meta = document.createElement('div');
    meta.className = 'candidate-meta';
    meta.textContent = clockOf(row.seenAt) + ' · trace ' + row.traceIndex
      + ' · ' + row.templateBytes + ' B';

    var input = document.createElement('input');
    input.type = 'text';
    input.placeholder = 'Name, then press Enter';
    input.setAttribute('aria-label', 'Name for this person');

    var error = document.createElement('div');
    error.className = 'candidate-error';
    error.hidden = true;

    var actions = document.createElement('div');
    actions.className = 'candidate-actions';
    var save = document.createElement('button');
    save.type = 'button';
    save.className = 'btn';
    save.textContent = 'Enrol';
    var skip = document.createElement('button');
    skip.type = 'button';
    skip.className = 'btn btn-ghost';
    skip.textContent = 'Dismiss';
    actions.appendChild(save);
    actions.appendChild(skip);

    function fail(message) {
      error.textContent = message;
      error.hidden = false;
      card.classList.remove('is-busy');
    }

    function enrol() {
      var name = input.value.trim();
      if (!name) { input.focus(); return fail('A name is required.'); }
      error.hidden = true;
      card.classList.add('is-busy');
      fetch('api/unknown/' + row.id + '/enroll', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: name })
      })
        .then(function (r) { return r.json().then(function (b) { return { ok: r.ok, body: b }; }); })
        .then(function (res) {
          if (!res.ok) { return fail(res.body.error || 'Could not enrol.'); }
          card.remove();
          refreshPanels();
        })
        .catch(function () { fail('Could not reach the server.'); });
    }

    save.addEventListener('click', enrol);
    input.addEventListener('keydown', function (e) { if (e.key === 'Enter') { enrol(); } });
    skip.addEventListener('click', function () {
      card.classList.add('is-busy');
      fetch('api/unknown/' + row.id, { method: 'DELETE' })
        .then(function () { card.remove(); refreshPanels(); })
        .catch(function () { fail('Could not dismiss.'); });
    });

    body.appendChild(meta);
    body.appendChild(input);
    body.appendChild(error);
    body.appendChild(actions);
    card.appendChild(body);
    return card;
  }

  /* After an enrol the watch list and the queue have both changed. */
  function refreshPanels() {
    getJson('api/employees').then(renderEmployees).catch(function () {});
    getJson('api/unknown?limit=24').then(renderCandidates).catch(function () {});
    poll();
  }

  function poll() {
    getJson('api/status').then(renderStatus).catch(function () {});
    getJson('api/live/meta').then(renderMeta).catch(function () {});
  }

  function control(path) {
    return function () {
      el.startBtn.disabled = el.stopBtn.disabled = true;
      fetch(path, { method: 'POST' })
        .then(poll)
        .catch(poll);
    };
  }

  // -------------------------------------------------------------------- boot

  el.startBtn.addEventListener('click', control('api/surveillance/start'));
  el.stopBtn.addEventListener('click', control('api/surveillance/stop'));

  startVideo();
  drawOverlay();
  openStream();
  poll();
  setInterval(poll, POLL_MILLIS);

  refreshPanels();
  /* The queue is re-read on a slow timer as well: new candidates only appear when somebody
     leaves the frame, which no live event announces. */
  setInterval(function () {
    getJson('api/unknown?limit=24').then(renderCandidates).catch(function () {});
  }, 10000);
  /* The feed and the gallery are deliberately NOT seeded from /api/detections. They used to
     be, which made a refresh look like cached output: those rows come from PostgreSQL, so
     old events reappeared on every reload. Both panels now show only what arrives live over
     SSE from the moment the page opens. The stored history is still queryable via
     /api/detections. */
}());
