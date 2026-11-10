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
  var statusPending = false;
  var backendOnline = false;
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
    tabVideo: document.getElementById('tabVideo'),
    tabCamera: document.getElementById('tabCamera'),
    paneVideo: document.getElementById('paneVideo'),
    paneCamera: document.getElementById('paneCamera'),
    cameraPicker: document.getElementById('cameraPicker'),
    cameraUseBtn: document.getElementById('cameraUseBtn'),
    cameraTestBtn: document.getElementById('cameraTestBtn'),
    cameraDeleteBtn: document.getElementById('cameraDeleteBtn'),
    cameraAddBtn: document.getElementById('cameraAddBtn'),
    cameraForm: document.getElementById('cameraForm'),
    cameraFormStatus: document.getElementById('cameraFormStatus'),
    camUrl: document.getElementById('camUrl'),
    camName: document.getElementById('camName'),
    camHost: document.getElementById('camHost'),
    camPort: document.getElementById('camPort'),
    camUser: document.getElementById('camUser'),
    camPass: document.getElementById('camPass'),
    camChannel: document.getElementById('camChannel'),
    camSubtype: document.getElementById('camSubtype'),
    camFlags: document.getElementById('camFlags'),
    camTestBtn: document.getElementById('camTestBtn'),
    camSaveBtn: document.getElementById('camSaveBtn'),
    camSaveUseBtn: document.getElementById('camSaveUseBtn'),
    camCancelBtn: document.getElementById('camCancelBtn'),
    sourceDetails: document.getElementById('sourceDetails'),
    sourceKind: document.getElementById('sourceKind'),
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

  function setText(node, value) {
    if (node) node.textContent = value == null ? '—' : value;
  }

  function formatCount(value) {
    return value == null ? '—' : Number(value).toLocaleString();
  }

  function setDisabled(node, disabled) { if (node) node.disabled = disabled; }

  function connection(online) {
    backendOnline = online;
    var status = document.getElementById('connectionStatus');
    status.className = 'connection-status ' + (online ? 'is-connected' : 'is-offline');
    setText(document.getElementById('connectionText'), online ? 'System connected' : 'Reconnecting');
    document.getElementById('connectionBanner').hidden = online;
    setText(document.getElementById('apiHealth'), online ? 'Connected' : 'Connection interrupted');
    if (!online) {
      setDisabled(el.startBtn, true);
      setDisabled(el.stopBtn, true);
      el.runPill.className = 'pill';
      setText(el.runText, 'Connection interrupted');
      setText(document.getElementById('workspaceSync'), 'Connection lost · retrying automatically');
    } else {
      var sync = clockOf(new Date().toISOString());
      setText(document.getElementById('workspaceSync'), 'Last synced ' + sync);
      setText(document.getElementById('systemLastSync'), sync);
    }
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
    if (!el.video || document.hidden || framePending || !sourceRunning || !backendOnline) { return; }
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
    if (!el.sourceStatus) return;
    document.getElementById('operationNotice').hidden = !message;
    el.sourceStatus.textContent = message;
    el.sourceStatus.className = kind === 'busy' ? 'is-busy-text'
      : kind === 'error' ? 'is-error' : 'muted';
  }

  var sourceBusy = false;
  var sourceRunning = false;

  function sourceBusyState(busy) {
    sourceBusy = busy;
    setDisabled(el.videoFile, busy);
    setDisabled(el.videoPicker, busy);
    setDisabled(el.startBtn, busy || sourceRunning || !backendOnline);
    setDisabled(el.stopBtn, busy || !sourceRunning || !backendOnline);
    cameraControls();
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
      note(res.body.error || 'Could not switch source.', 'error');
      loadVideoList();
      loadCameras();
      poll();
      return;
    }
    note((res.body.running ? 'Now playing ' : 'Selected ') + res.body.current, null);
    if (el.stage) el.stage.classList.remove('has-frame');
    loadVideoList();
    loadCameras();
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
    if (!el.videoPicker) return;
    getJson('api/source/videos').then(function (data) {
      el.videoPicker.textContent = '';
      renderVideoLibrary(data);
      if (!data.videos.length) {
        var empty = document.createElement('option');
        empty.textContent = 'No uploaded videos yet';
        empty.value = '';
        el.videoPicker.appendChild(empty);
        el.videoPicker.disabled = true;
        return;
      }
      el.videoPicker.disabled = sourceBusy;
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
    }).catch(function () { note('Could not load the video library. Check your connection and retry.', 'error'); });
  }

  /* ------------------------------------------------------------- IP cameras

     A camera is not just "a video file with a URL": it can be unreachable, it can refuse
     the credentials, and it can accept the connection and then deliver nothing. So the
     camera pane has a Test that connects, reports what answered and disconnects again -
     without touching whatever the engine is currently running. */

  var cameraList = [];
  var activeCameraId = null;
  var lastSource = null;
  var lastMeta = null;
  var tabChosen = false;
  var sourceTab = 'VIDEO_FILE';

  function jsonBody(payload) {
    return {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    };
  }

  function selectedCamera() {
    var id = el.cameraPicker.value;
    if (!id) { return null; }
    for (var i = 0; i < cameraList.length; i++) {
      if (String(cameraList[i].id) === id) { return cameraList[i]; }
    }
    return null;
  }

  function cameraControls() {
    if (!el.cameraPicker) return;
    var has = Boolean(selectedCamera());
    el.cameraPicker.disabled = sourceBusy || !cameraList.length;
    el.cameraUseBtn.disabled = sourceBusy || !has;
    el.cameraTestBtn.disabled = sourceBusy || !has;
    el.cameraDeleteBtn.disabled = sourceBusy || !has;
    el.cameraAddBtn.disabled = sourceBusy;
    var buttons = document.querySelectorAll('[data-camera-action]');
    buttons.forEach(function (button) { button.disabled = sourceBusy; });
  }

  /* Describes what the SDK reported for a camera. width is 0 until the plugin has
     negotiated the stream, which only happens once frames are actually pulled. */
  function describeCamera(cam) {
    if (!cam) { return 'connected'; }
    if (!cam.width) { return 'connected, stream not negotiated yet'; }
    return cam.width + '\u00d7' + cam.height
      + (cam.frameRate ? ' @ ' + cam.frameRate + ' fps' : '')
      + (cam.codec ? ' ' + cam.codec : '');
  }

  function loadCameras(preferId) {
    if (!el.cameraPicker) return Promise.resolve();
    return getJson('api/source/cameras').then(function (data) {
      cameraList = data.cameras || [];
      activeCameraId = data.activeId;
      renderCameraLibrary();
      var wanted = preferId != null ? String(preferId) : el.cameraPicker.value;
      el.cameraPicker.textContent = '';
      if (!cameraList.length) {
        var none = document.createElement('option');
        none.value = '';
        none.textContent = 'No cameras saved yet';
        el.cameraPicker.appendChild(none);
      } else {
        var available = {};
        cameraList.forEach(function (c) {
          available[String(c.id)] = true;
          var opt = document.createElement('option');
          opt.value = String(c.id);
          opt.textContent = c.name
            + (c.active ? ' \u2014 active source' : '')
            + (c.width ? ' \u00b7 ' + c.width + '\u00d7' + c.height : '')
            + (c.lastOk === false ? ' \u00b7 last attempt failed' : '');
          el.cameraPicker.appendChild(opt);
        });
        el.cameraPicker.value = available[wanted] ? wanted
          : activeCameraId != null ? String(activeCameraId)
          : String(cameraList[0].id);
      }
      cameraControls();
    }).catch(function () {
      setText(document.getElementById('cameraCount'), 'Camera library unavailable');
      note('Could not load saved cameras. Check your connection and reload this page.', 'error');
    });
  }

  function useCamera() {
    var camera = selectedCamera();
    if (!camera || sourceBusy) { return; }
    sourceBusyState(true);
    note('Connecting to ' + camera.name + '\u2026', 'busy');
    postJson('api/source/cameras/' + camera.id + '/activate', { method: 'POST' })
      .then(afterSwitch)
      .catch(function () { note('Could not reach the camera.', 'error'); })
      .then(function () { sourceBusyState(false); });
  }

  function testCamera() {
    var camera = selectedCamera();
    if (!camera || sourceBusy) { return; }
    sourceBusyState(true);
    note('Testing ' + camera.name + '\u2026 (up to 20s)', 'busy');
    postJson('api/source/cameras/' + camera.id + '/test', { method: 'POST' })
      .then(function (res) {
        note(res.ok ? camera.name + ': ' + describeCamera(res.body.camera)
          : (res.body.error || 'Test failed.'), res.ok ? null : 'error');
        loadCameras(camera.id);
      })
      .catch(function () { note('Test failed.', 'error'); })
      .then(function () { sourceBusyState(false); });
  }

  function deleteCamera() {
    var camera = selectedCamera();
    if (!camera || sourceBusy) { return; }
    if (!window.confirm('Remove camera "' + camera.name + '" from the list?')) { return; }
    sourceBusyState(true);
    fetch('api/source/cameras/' + camera.id, { method: 'DELETE' })
      .then(function (r) { return r.json().catch(function () { return {}; }).then(function (b) {
        note(r.ok ? 'Removed ' + camera.name : (b.error || 'Could not remove it.'), r.ok ? null : 'error');
      }); })
      .catch(function () { note('Could not remove it.', 'error'); })
      .then(function () { sourceBusyState(false); loadCameras(); });
  }

  // ------------------------------------------------------- the add-camera form

  function formNote(message, kind) {
    el.cameraFormStatus.textContent = message;
    el.cameraFormStatus.className = kind === 'busy' ? 'is-busy-text'
      : kind === 'error' ? 'is-error'
      : kind === 'ok' ? 'is-ok' : 'muted';
  }

  function showCameraForm(show) {
    if (!el.cameraForm) return;
    el.cameraForm.hidden = !show;
    if (el.cameraAddBtn) el.cameraAddBtn.setAttribute('aria-expanded', String(show));
    if (show) {
      formNote('', null);
      el.camUrl.focus();
    } else {
      el.camPass.value = '';       // never leave a camera password sitting in the page
    }
  }

  function cameraFormPayload() {
    return {
      name: el.camName.value.trim() || null,
      url: el.camUrl.value.trim() || null,
      host: el.camHost.value.trim() || null,
      port: Number(el.camPort.value) || 554,
      username: el.camUser.value || null,
      password: el.camPass.value || null,
      channel: Number(el.camChannel.value) || 1,
      subtype: Number(el.camSubtype.value) || 0,
      flags: el.camFlags.value || null
    };
  }

  function formFilledIn() {
    if (el.camUrl.value.trim() || el.camHost.value.trim()) { return true; }
    formNote('Give either a full URL or a host/IP address.', 'error');
    return false;
  }

  function testNewCamera() {
    if (!formFilledIn()) { return; }
    formNote('Connecting\u2026 (up to 20s)', 'busy');
    postJson('api/source/cameras/test', jsonBody(cameraFormPayload()))
      .then(function (res) {
        if (!res.ok) { formNote(res.body.error || 'Test failed.', 'error'); return; }
        formNote(res.body.url + ' \u2014 ' + describeCamera(res.body.camera), 'ok');
      })
      .catch(function () { formNote('Test failed.', 'error'); });
  }

  function saveCamera(activate) {
    if (!formFilledIn()) { return; }
    formNote(activate ? 'Saving and connecting\u2026' : 'Saving\u2026', 'busy');
    if (activate) { sourceBusyState(true); }
    postJson('api/source/cameras' + (activate ? '?activate=true' : ''), jsonBody(cameraFormPayload()))
      .then(function (res) {
        if (!res.ok) {
          formNote(res.body.error || 'Could not save the camera.', 'error');
          loadCameras();
          return;
        }
        formNote('Saved as "' + res.body.name + '"', 'ok');
        loadCameras(res.body.id);
        if (activate) {
          showCameraForm(false);
          note('Now playing ' + res.body.current, null);
          if (el.stage) el.stage.classList.remove('has-frame');
          poll();
        }
      })
      .catch(function () { formNote('Could not save the camera.', 'error'); })
      .then(function () { if (activate) { sourceBusyState(false); } });
  }

  // --------------------------------------------------------- which pane shows

  function showTab(kind) {
    if (!el.tabCamera || !el.tabVideo) return;
    sourceTab = kind;
    var camera = kind === 'IP_CAMERA';
    el.tabCamera.classList.toggle('is-active', camera);
    el.tabVideo.classList.toggle('is-active', !camera);
    el.tabCamera.setAttribute('aria-selected', String(camera));
    el.tabVideo.setAttribute('aria-selected', String(!camera));
    el.tabCamera.tabIndex = camera ? 0 : -1;
    el.tabVideo.tabIndex = camera ? -1 : 0;
    el.paneCamera.hidden = !camera;
    el.paneVideo.hidden = camera;
    if (!camera) { showCameraForm(false); }
  }

  // -------------------------------------------------------- source details

  function detailRow(parent, label, value, cls) {
    if (value == null || value === '') { return; }
    var wrap = document.createElement('div');
    var dt = document.createElement('dt');
    dt.textContent = label;
    var dd = document.createElement('dd');
    dd.textContent = value;
    if (cls) { dd.className = cls; }
    wrap.appendChild(dt);
    wrap.appendChild(dd);
    parent.appendChild(wrap);
  }

  function renderSource(data) {
    lastSource = data;
    /* The pane follows whatever is actually playing, but only until the operator picks a
       tab - otherwise a poll two seconds later would snatch it back. */
    if (!tabChosen) { showTab(data.type === 'IP_CAMERA' ? 'IP_CAMERA' : 'VIDEO_FILE'); }
    renderSourceDetails();
  }

  function renderSourceDetails() {
    var src = lastSource;
    if (!src || !el.sourceDetails) { return; }
    var isCamera = src.type === 'IP_CAMERA';
    var cam = src.camera || {};
    el.sourceKind.textContent = isCamera ? 'IP camera' : 'video file';

    var list = document.createDocumentFragment();
    detailRow(list, 'Name', src.name);
    if (isCamera) {
      detailRow(list, 'Address', src.url);
    } else {
      detailRow(list, 'File', src.file);
    }
    detailRow(list, 'Device', [cam.make, cam.model].filter(Boolean).join(' ') || null);
    if (cam.width) {
      detailRow(list, 'Stream', describeCamera(cam));
    }
    detailRow(list, 'Decoder', src.flags || 'SDK default');
    detailRow(list, 'Detector', src.detectPeople ? 'faces + bodies' : 'faces only');

    var hasFrame = lastMeta && lastMeta.hasFrame;
    detailRow(list, 'Capture',
      !src.running ? 'stopped'
        : hasFrame ? 'running'
        : 'running, no frame yet',
      !src.running ? null : hasFrame ? 'is-ok' : 'is-warn');
    if (lastMeta && lastMeta.frameWidth) {
      detailRow(list, 'Preview', lastMeta.frameWidth + '\u00d7' + lastMeta.frameHeight
        + ' \u00b7 ' + lastMeta.framesEncoded + ' encoded');
    }

    /* Same guidance as scripts/check-camera.sh: recognition needs the eyes at least
       minimal-inter-ocular-distance pixels apart, which on a given stream width translates
       into a head of a certain size. It is the single most common reason a correctly
       configured ceiling camera never identifies anybody. */
    var width = cam.width || (lastMeta ? lastMeta.frameWidth : 0);
    if (isCamera && width) {
      var iod = src.minimalInterOcularDistance || 24;
      var low = Math.round(width / 22 * iod / 24);
      var high = Math.round(width / 14 * iod / 24);
      detailRow(list, 'Face size', 'heads should span \u2248' + low + '\u2013' + high
        + 'px here (eyes \u2265' + iod + 'px apart)', 'hint');
    }

    el.sourceDetails.textContent = '';
    el.sourceDetails.appendChild(list);
  }

  function markFrameArrived() {
    el.stage.classList.add('has-frame');
  }

  // -------------------------------------------------------------- event feed

  function addFeedRow(detection) {
    if (!el.feed) return;
    var placeholder = el.feed.querySelector('.feed-placeholder');
    if (placeholder) placeholder.remove();
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
    if (!el.gallery || !name || seenShots.has(name)) { return; }
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
      setText(el.feedState, 'Live');
    });
    stream.addEventListener('detection', function (e) {
      try { onDetection(JSON.parse(e.data)); } catch (err) { /* ignore a bad frame */ }
    });
    stream.addEventListener('candidates-changed', refreshCandidates);
    stream.addEventListener('error', function () {
      // EventSource reconnects on its own; just reflect the state.
      setText(el.feedState, 'Reconnecting…');
    });
  }

  // ------------------------------------------------------------------ panels

  function renderStatus(status) {
    setText(el.events, formatCount(status.events));
    setText(el.subjects, formatCount(status.subjects));
    setText(el.identified, formatCount(status.identified));
    setText(el.stored, formatCount(status.storedEvents));
    setText(el.watch, formatCount(status.watchListSize));
    setText(el.nonHuman, formatCount(status.nonHumanIgnored));
    // Milliseconds under a second, otherwise seconds - a 4-digit ms value is unreadable.
    setText(el.pending, formatCount(status.unknownPending));
    var lag = status.detectionLagMillis;
    setText(el.lag, lag == null ? '—'
      : lag < 1000 ? lag + ' ms' : (lag / 1000).toFixed(1) + ' s');
    var navPending = document.getElementById('navPending');
    setText(navPending, status.unknownPending);
    navPending.hidden = !status.unknownPending;
    setText(document.getElementById('queueTabCount'), status.unknownPending);
    setText(document.getElementById('watchTabCount'), status.watchListSize);
    setText(document.getElementById('pipelineState'), status.running ? 'Capturing' : 'Stopped');
    setText(document.getElementById('captureHealth'), status.running ? 'Running' : 'Stopped');

    var running = Boolean(status.running);
    sourceRunning = running;
    el.runPill.className = 'pill ' + (running ? 'is-running' : 'is-stopped');
    el.runText.textContent = running ? 'Capturing' : 'Stopped';
    setDisabled(el.startBtn, sourceBusy || running);
    setDisabled(el.stopBtn, sourceBusy || !running);

    // Capture no longer starts on its own, so distinguish "not started yet" from
    // "started, first frame still on its way".
    if (!el.stageEmptyTitle) return;
    if (running) {
      el.stageEmptyTitle.textContent = 'Waiting for the first frame';
      el.stageEmptyHint.textContent = 'The source is running. Waiting for video to arrive.';
    } else {
      el.stageEmptyTitle.textContent = 'Source is stopped';
      el.stageEmptyHint.textContent = 'Press \u201cStart source\u201d to begin capturing.';
      if (el.stage) el.stage.classList.remove('has-frame');
    }
  }

  function renderMeta(meta) {
    lastMeta = meta;
    sourceDims.width = meta.frameWidth;
    sourceDims.height = meta.frameHeight;
    el.sourceLine.textContent = meta.sourceName + ' · '
      + (meta.sourceType === 'IP_CAMERA' ? 'IP camera' : 'video file');
    setText(el.frameInfo, meta.frameWidth ? meta.frameWidth + ' × ' + meta.frameHeight : 'No frame yet');
    setText(document.getElementById('systemSourceName'), meta.sourceName);
    setText(document.getElementById('systemFrames'), Number(meta.framesEncoded || 0).toLocaleString());
    setText(document.getElementById('systemViewers'), meta.viewers);
    renderSourceDetails();
  }

  function renderEmployees(list) {
    if (!el.watchList) return;
    el.watchState.textContent = list.length + ' enrolled';
    el.watchList.textContent = '';
    if (!list.length) {
      var empty = document.createElement('p');
      empty.className = 'empty';
      empty.textContent = 'Your watch list is empty. Enroll a person from the review queue to start recognizing them.';
      el.watchList.appendChild(empty);
      return;
    }
    list.forEach(function (e) {
      var card = document.createElement('div');
      card.className = 'card';
      card.dataset.search = [e.name, e.templateId, e.employeeNo].join(' ').toLowerCase();

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
    applyPeopleFilter();
  }

  /* People the engine saw with a face but could not match. Naming one posts it straight
     into the running watch list, so recognition starts on their next appearance - there is
     no photo to supply and no restart. */
  /* Cards are reconciled by id, never rebuilt.
     Queue-change events refresh immediately; a timer covers reconnects. Existing cards
     update their image and quality in place, preserving the name currently being typed. */
  var cardsById = new Map();

  function renderCandidates(rows) {
    if (!el.candidates) return;
    setText(document.getElementById('queueState'), rows.length ? rows.length + ' ready for review' : 'Queue clear');
    var loading = el.candidates.querySelector('.empty');
    if (loading && !cardsById.size) loading.remove();
    var seen = new Set();

    // Newest first in the payload, so inserting each at the top keeps that order.
    rows.slice().reverse().forEach(function (row) {
      seen.add(row.id);
      if (!cardsById.has(row.id)) {
        var card = candidateCard(row);
        cardsById.set(row.id, card);
        el.candidates.insertBefore(card, el.candidates.firstChild);
      } else {
        cardsById.get(row.id).updateCandidate(row);
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
        + 'as soon as a usable template is captured.';
      el.candidates.appendChild(empty);
    } else if (cardsById.size && placeholder) {
      placeholder.remove();
    }
    applyPeopleFilter();
  }

  function candidateCard(row) {
    var card = document.createElement('div');
    card.className = 'candidate';

    var img = document.createElement('img');
    img.alt = 'Unidentified person';
    img.tabIndex = 0;
    img.setAttribute('role', 'button');
    img.setAttribute('aria-label', 'Enlarge this face capture');
    function enlargeFace() { if (row.snapshot && window.SentiUI.openCapture) window.SentiUI.openCapture(row.snapshot, 'Unidentified person', 'Trace ' + row.traceIndex + (row.source ? ' · ' + row.source : '')); }
    img.addEventListener('click', enlargeFace);
    img.addEventListener('keydown', function (e) { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); enlargeFace(); } });
    var none = document.createElement('div');
    none.className = 'no-shot';
    none.textContent = 'no snapshot';
    card.appendChild(img);
    card.appendChild(none);

    var body = document.createElement('div');
    body.className = 'candidate-body';

    var meta = document.createElement('div');
    meta.className = 'candidate-meta';
    card.updateCandidate = function (updated) {
      row = updated;
      card.dataset.search = ['unknown', row.source, row.traceIndex].join(' ').toLowerCase();
      var snapshot = row.snapshot ? 'api/snapshots/' + encodeURIComponent(row.snapshot) : '';
      if (snapshot && img.getAttribute('src') !== snapshot) img.src = snapshot;
      img.hidden = !snapshot;
      none.hidden = Boolean(snapshot);
      meta.textContent = clockOf(row.seenAt) + ' · trace ' + row.traceIndex
        + ' · ' + row.templateBytes + ' B'
        + (row.faceQuality == null ? '' : ' · Quality ' + row.faceQuality)
        + (row.source ? ' · ' + row.source : '');
    };
    card.updateCandidate(row);

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
    save.textContent = 'Enroll person';
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
      postJson('api/unknown/' + row.id, { method: 'DELETE' })
        .then(function (res) {
          if (!res.ok) { fail(res.body.error || 'Could not dismiss this capture.'); return; }
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

  var candidatesFetching = false;
  var candidatesRefreshPending = false;
  function refreshCandidates() {
    if (!el.candidates) return;
    if (candidatesFetching) { candidatesRefreshPending = true; return; }
    candidatesFetching = true;
    getJson('api/unknown?limit=200').then(renderCandidates).catch(function () { setText(document.getElementById('queueState'), 'Unable to load · retrying'); })
      .then(function () {
        candidatesFetching = false;
        if (candidatesRefreshPending) {
          candidatesRefreshPending = false;
          refreshCandidates();
        }
      });
  }

  /* After an enrol the watch list and the queue have both changed. */
  function refreshPanels() {
    if (el.watchList) getJson('api/employees').then(renderEmployees).catch(function () { setText(el.watchState, 'Unable to load · retrying'); });
    refreshCandidates();
    poll();
  }

  function renderRecognized(people) {
    var container = el.recognizedPeople;
    if (!container) return;
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
    if (statusPending) return;
    statusPending = true;
    var requests = [
      getJson('api/status').then(function (status) { connection(true); renderStatus(status); }).catch(function () { connection(false); }),
      getJson('api/source').then(renderSource).catch(function () {}),
      getJson('api/live/meta').then(renderMeta).catch(function () {})
    ];
    if (el.recognizedPeople) requests.push(getJson('api/live/recognized').then(renderRecognized).catch(function () {
      setText(el.recognizedState, 'Reconnecting…');
    }));
    return Promise.all(requests).then(function () { statusPending = false; });
  }

  function control(path) {
    return function () {
      setDisabled(el.startBtn, true);
      setDisabled(el.stopBtn, true);
      postJson(path, { method: 'POST' }).then(function (res) {
        if (!res.ok) note(res.body.error || 'The source could not be updated.', 'error');
        poll();
      }).catch(function () { note('Could not reach the source. Please retry.', 'error'); poll(); });
    };
  }

  function applyPeopleFilter() {
    var search = document.getElementById('peopleSearch');
    if (!search) return;
    var query = search.value.trim().toLowerCase();
    var active = document.getElementById('watchPanel').hidden ? el.candidates : el.watchList;
    if (!active) return;
    var cards = active.querySelectorAll('.candidate, .card');
    var visible = 0;
    cards.forEach(function (card) {
      card.hidden = Boolean(query && (card.dataset.search || '').indexOf(query) < 0);
      if (!card.hidden) visible++;
    });
    document.getElementById('peopleNoResults').hidden = !query || !cards.length || visible > 0;
  }

  function renderCameraLibrary() {
    var library = document.getElementById('cameraLibrary');
    if (!library) return;
    setText(document.getElementById('cameraCount'), cameraList.length + ' saved ' + (cameraList.length === 1 ? 'camera' : 'cameras'));
    library.textContent = '';
    if (!cameraList.length) {
      var empty = document.createElement('div');
      empty.className = 'empty';
      empty.innerHTML = window.SentiUI.icon('camera') + '<h3>No cameras connected yet</h3><p>Add your first IP camera, test its connection, then activate it to start monitoring.</p>';
      library.appendChild(empty);
      return;
    }
    cameraList.forEach(function (camera) {
      var row = document.createElement('article');
      row.className = 'camera-list-item';
      var symbol = document.createElement('span');
      symbol.className = 'camera-item-icon';
      symbol.innerHTML = window.SentiUI.icon('camera');
      var content = document.createElement('div');
      content.className = 'camera-item-content';
      var title = document.createElement('div');
      title.className = 'camera-item-name';
      var name = document.createElement('span');
      name.textContent = camera.name;
      title.appendChild(name);
      var badge = document.createElement('span');
      badge.className = 'badge ' + (camera.active ? 'badge-success' : camera.lastOk === false ? 'badge-danger' : 'badge-neutral');
      badge.textContent = camera.active ? 'Active source' : camera.lastOk === false ? 'Test failed' : camera.lastOk ? 'Last test passed' : 'Saved';
      title.appendChild(badge);
      var detail = document.createElement('p');
      detail.textContent = [camera.url, camera.width ? describeCamera(camera) : 'Stream details available after capture starts'].filter(Boolean).join(' · ');
      content.appendChild(title); content.appendChild(detail);
      var actions = document.createElement('div');
      actions.className = 'camera-item-actions';
      ['Test', 'Activate'].forEach(function (label) {
        var button = document.createElement('button');
        button.type = 'button'; button.className = 'btn btn-ghost'; button.textContent = label;
        button.dataset.cameraAction = label.toLowerCase();
        button.setAttribute('aria-label', label + ' ' + camera.name);
        button.disabled = sourceBusy;
        button.addEventListener('click', function () {
          el.cameraPicker.value = String(camera.id);
          if (label === 'Test') testCamera(); else useCamera();
        });
        actions.appendChild(button);
      });
      row.appendChild(symbol); row.appendChild(content); row.appendChild(actions);
      library.appendChild(row);
    });
  }

  function renderVideoLibrary(data) {
    var library = document.getElementById('videoLibrary');
    if (!library) return;
    library.textContent = '';
    data.videos.forEach(function (video) {
      var row = document.createElement('div'); row.className = 'video-file-row';
      row.innerHTML = window.SentiUI.icon('video');
      var body = document.createElement('div');
      var name = document.createElement('strong'); name.textContent = video.name;
      var size = document.createElement('small'); size.textContent = Math.round(video.bytes / 1048576) + ' MB' + (video.name === data.current ? ' · Current source' : '');
      body.appendChild(name); body.appendChild(size);
      var play = document.createElement('button'); play.type = 'button'; play.className = 'btn btn-ghost'; play.textContent = 'Play';
      play.setAttribute('aria-label', 'Play ' + video.name);
      play.addEventListener('click', function () { if (!sourceBusy) { el.videoPicker.value = video.name; el.videoPicker.dispatchEvent(new Event('change')); } });
      row.appendChild(body); row.appendChild(play); library.appendChild(row);
    });
  }

  // -------------------------------------------------------------------- boot
  function on(node, event, handler) { if (node) node.addEventListener(event, handler); }
  on(el.videoFile, 'change', function () {
    if (this.files && this.files[0]) { uploadVideo(this.files[0]); this.value = ''; }
  });
  on(el.videoPicker, 'change', function () {
    var name = this.value;
    if (!name || sourceBusy) return;
    sourceBusyState(true);
    note('Switching to ' + name + '…', 'busy');
    postJson('api/source/video/' + encodeURIComponent(name), { method: 'POST' })
      .then(afterSwitch).catch(function () { note('Could not switch video.', 'error'); })
      .then(function () { sourceBusyState(false); });
  });
  on(el.tabVideo, 'click', function () { tabChosen = true; showTab('VIDEO_FILE'); });
  on(el.tabCamera, 'click', function () { tabChosen = true; showTab('IP_CAMERA'); });
  [el.tabVideo, el.tabCamera].forEach(function (tab) {
    on(tab, 'keydown', function (event) {
      if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
        event.preventDefault();
        var next = tab === el.tabCamera ? el.tabVideo : el.tabCamera;
        next.click(); next.focus();
      }
    });
  });
  on(el.cameraPicker, 'change', cameraControls);
  on(el.cameraUseBtn, 'click', useCamera);
  on(el.cameraTestBtn, 'click', testCamera);
  on(el.cameraDeleteBtn, 'click', deleteCamera);
  on(el.cameraAddBtn, 'click', function () { showCameraForm(el.cameraForm.hidden); });
  on(el.camTestBtn, 'click', testNewCamera);
  on(el.camSaveBtn, 'click', function () { saveCamera(false); });
  on(el.camSaveUseBtn, 'click', function () { saveCamera(true); });
  on(el.camCancelBtn, 'click', function () { showCameraForm(false); el.cameraAddBtn.focus(); });
  on(el.cameraForm, 'submit', function (event) { event.preventDefault(); saveCamera(false); });
  on(el.startBtn, 'click', control('api/surveillance/start'));
  on(el.stopBtn, 'click', control('api/surveillance/stop'));
  on(document.getElementById('peopleSearch'), 'input', applyPeopleFilter);
  document.addEventListener('sv:people-tab', applyPeopleFilter);
  if (el.cameraPicker) showTab('IP_CAMERA');
  if (el.video) startVideo();
  if (el.feed || el.candidates) openStream();
  poll();
  setInterval(function () { if (!document.hidden) poll(); }, POLL_MILLIS);
  if (el.watchList) refreshPanels();
  loadVideoList();
  loadCameras();
  if (el.candidates) setInterval(function () { if (!document.hidden) refreshCandidates(); }, 1000);
  document.addEventListener('visibilitychange', function () {
    if (!document.hidden) { poll(); refreshCandidates(); }
  });
}());
