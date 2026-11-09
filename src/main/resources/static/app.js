/* SentiVeillance console.
 *
 * Three data paths:
 *   - the video is a still refreshed on a timer; the detection boxes are already drawn
 *     into it by the server, so there is nothing to position here;
 *   - detections arrive over SSE (/api/live/events) and drive the feed and gallery;
 *   - /api/status is polled as the authoritative counter source, so the tiles stay
 *     correct even if a browser missed events while the tab was in the background.
 */
(function () {
  'use strict';

  var POLL_MILLIS = 2000;
  /* Matches sentiveillance.live.preview-interval-millis; asking faster only wastes requests. */
  var FRAME_INTERVAL_MILLIS = 200;
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
    frameInfo: document.getElementById('frameInfo'),
    videoFile: document.getElementById('videoFile'),
    videoPicker: document.getElementById('videoPicker'),
    sourceStatus: document.getElementById('sourceStatus'),
    feed: document.getElementById('feed'),
    feedState: document.getElementById('feedState'),
    watchList: document.getElementById('watchList'),
    watchState: document.getElementById('watchState'),
    recognizedPeople: document.getElementById('recognizedPeople'),
    recognizedState: document.getElementById('recognizedState'),
    gallery: document.getElementById('gallery')
  };

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

  /* Choosing the video from the browser rather than editing a property and restarting.
     The file is uploaded because the SDK reads a path, not a browser blob: it decodes the
     video natively and keeps reading for as long as the source is attached. */
  function note(message, kind) {
    el.sourceStatus.textContent = message;
    el.sourceStatus.className = kind === 'busy' ? 'is-busy-text'
      : kind === 'error' ? 'is-error' : 'muted';
  }

  var sourceBusy = false;
  var sourceRunning = false;

  function sourceBusyState(busy) {
    sourceBusy = busy;
    el.videoFile.disabled = busy;
    el.videoPicker.disabled = busy;
    el.startBtn.disabled = busy || sourceRunning;
    el.stopBtn.disabled = busy || !sourceRunning;
  }

  function postJson(url, options) {
    return fetch(url, options)
      .then(function (r) {
        return r.text().then(function (text) {
          var body;
          try { body = JSON.parse(text); } catch (e) {
            body = { error: r.status === 413 ? 'Video exceeds the upload size limit.'
              : 'Request failed (HTTP ' + r.status + ').' };
          }
          return { ok: r.ok, body: body };
        });
      });
  }

  function afterSwitch(res) {
    if (!res.ok) {
      note(res.body.error || 'Could not switch video.', 'error');
      loadVideoList();
      poll();
      return;
    }
    note((res.body.running ? 'Now playing ' : 'Selected ') + res.body.current, null);
    el.stage.classList.remove('has-frame');
    loadVideoList();
    poll();
  }

  function uploadVideo(file) {
    if (sourceBusy) { return; }
    if (!file.size) { note('Choose a non-empty video file.', 'error'); return; }
    sourceBusyState(true);
    var form = new FormData();
    form.append('file', file);
    note('Uploading ' + file.name + ' (' + Math.round(file.size / 1048576) + ' MB)...', 'busy');
    postJson('api/source/video', { method: 'POST', body: form })
      .then(afterSwitch)
      .catch(function () { note('Upload failed. Check the connection and try again.', 'error'); })
      .then(function () { sourceBusyState(false); });
  }

  function loadVideoList() {
    getJson('api/source/videos').then(function (data) {
      el.videoPicker.textContent = '';
      if (!data.videos.length) { return; }
      var head = document.createElement('option');
      head.value = '';
      head.textContent = 'Switch to...';
      el.videoPicker.appendChild(head);
      data.videos.forEach(function (v) {
        var opt = document.createElement('option');
        opt.value = v.name;
        opt.textContent = v.name + ' (' + Math.round(v.bytes / 1048576) + ' MB)'
          + (v.name === data.current ? ' - playing' : '');
        el.videoPicker.appendChild(opt);
      });
    }).catch(function () {});
  }

  function markFrameArrived() {
    el.stage.classList.add('has-frame');
  }

  // -------------------------------------------------------------- event feed

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
    if (detection.eventType === 'SUBJECT_DISAPPEARED') {
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
    sourceRunning = running;
    el.runPill.className = 'pill ' + (running ? 'is-running' : 'is-stopped');
    el.runText.textContent = running ? 'capturing' : 'stopped';
    el.startBtn.disabled = sourceBusy || running;
    el.stopBtn.disabled = sourceBusy || !running;

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
  /* Cards are reconciled by id, never rebuilt.
     This panel is re-read every 10s because a new candidate only appears when somebody
     leaves the frame and no live event announces it. Clearing the container and recreating
     every card made that refresh destroy the <input> you were typing into - the name
     vanished a few seconds after you started. Existing cards are now left in place, so the
     text you have typed survives; only genuinely new or departed candidates are touched. */
  var cardsById = new Map();

  function renderCandidates(rows) {
    var seen = new Set();

    // Newest first in the payload, so inserting each at the top keeps that order.
    rows.slice().reverse().forEach(function (row) {
      seen.add(row.id);
      if (!cardsById.has(row.id)) {
        var card = candidateCard(row);
        cardsById.set(row.id, card);
        el.candidates.insertBefore(card, el.candidates.firstChild);
      }
    });

    cardsById.forEach(function (card, id) {
      if (!seen.has(id)) {
        card.remove();
        cardsById.delete(id);
      }
    });

    var placeholder = el.candidates.querySelector('.empty');
    if (!cardsById.size && !placeholder) {
      var empty = document.createElement('p');
      empty.className = 'empty';
      empty.textContent = 'Nobody waiting. Unmatched people with a usable face appear here '
        + 'when they leave the frame.';
      el.candidates.appendChild(empty);
    } else if (cardsById.size && placeholder) {
      placeholder.remove();
    }
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
          cardsById.delete(row.id);
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
        .then(function () {
          cardsById.delete(row.id);
          card.remove();
          refreshPanels();
        })
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

  function renderRecognized(people) {
    var container = el.recognizedPeople;
    el.recognizedState.textContent = people.length + ' recent ' + (people.length === 1 ? 'person' : 'people');
    if (!people.length) {
      container.textContent = '';
      var empty = document.createElement('p');
      empty.className = 'empty';
      empty.textContent = 'Recognized faces will appear here while the video plays.';
      container.appendChild(empty);
      return;
    }
    // Reuse cards and image URLs so status polling does not reload every face.
    var existing = new Map();
    Array.prototype.forEach.call(container.children, function (card) {
      if (card.dataset.templateId) { existing.set(card.dataset.templateId, card); }
    });
    Array.prototype.forEach.call(container.querySelectorAll('.empty'), function (node) { node.remove(); });
    people.forEach(function (person) {
      var card = existing.get(person.templateId);
      if (!card) {
        card = document.createElement('article');
        card.className = 'recognized-card';
        card.dataset.templateId = person.templateId;
        var image = document.createElement('img');
        image.className = 'recognized-face';
        var body = document.createElement('div');
        ['card-name', 'card-meta', 'card-meta', 'card-meta'].forEach(function (className) {
          var line = document.createElement('div');
          line.className = className;
          body.appendChild(line);
        });
        card.appendChild(image);
        card.appendChild(body);
      }
      existing.delete(person.templateId);
      var img = card.firstChild;
      var version = String(person.faceVersion);
      if (img.dataset.version !== version) {
        img.dataset.version = version;
        img.src = 'api/live/recognized/' + encodeURIComponent(person.templateId) + '/face.jpg?v=' + version;
      }
      img.alt = 'Detected face of ' + person.name;
      var lines = card.lastChild.children;
      lines[0].textContent = person.name;
      lines[1].textContent = person.templateId + (person.score == null ? '' : ' · Match score ' + person.score);
      lines[2].textContent = 'Last seen ' + clockOf(person.lastSeen);
      lines[3].textContent = person.source;
      container.appendChild(card);
    });
    existing.forEach(function (card) { card.remove(); });
  }

  function poll() {
    getJson('api/status').then(renderStatus).catch(function () {});
    getJson('api/live/meta').then(renderMeta).catch(function () {});
    getJson('api/live/recognized').then(renderRecognized).catch(function () {
      el.recognizedState.textContent = 'reconnecting…';
    });
  }

  function control(path) {
    return function () {
      el.startBtn.disabled = true;
      el.stopBtn.disabled = true;
      fetch(path, { method: 'POST' })
        .then(poll)
        .catch(poll);
    };
  }

  // -------------------------------------------------------------------- boot

  el.videoFile.addEventListener('change', function () {
    if (this.files && this.files[0]) {
      uploadVideo(this.files[0]);
      this.value = '';            // so re-picking the same file fires change again
    }
  });
  el.videoPicker.addEventListener('change', function () {
    var name = this.value;
    if (!name || sourceBusy) { return; }
    sourceBusyState(true);
    note('Switching to ' + name + '...', 'busy');
    postJson('api/source/video/' + encodeURIComponent(name), { method: 'POST' })
      .then(afterSwitch)
      .catch(function () { note('Could not switch video.', 'error'); })
      .then(function () { sourceBusyState(false); });
  });

  el.startBtn.addEventListener('click', control('api/surveillance/start'));
  el.stopBtn.addEventListener('click', control('api/surveillance/stop'));

  startVideo();
  openStream();
  poll();
  setInterval(poll, POLL_MILLIS);

  refreshPanels();
  loadVideoList();
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
