# SentiVeillance 10.1 — setup, run and test

Scope right now: **one** source (a video file *or* one Dahua camera), faces detected,
tracked and matched against enrolled employee photos, every event written to PostgreSQL.
The design is single-source but not single-source-*only*: see "Going multi-camera" at the end.

---

## 0. What the SDK gave us, and what we copied

| Need | Where the pattern came from |
|---|---|
| Native library loading | `Tutorials/Common/Java/utils/.../LibraryManager.java` |
| License obtain + component checks | `Samples/Surveillance/Java/surveillance-web-sample/.../SurveillanceService.java` |
| Video file / RTSP as a source | `Tutorials/Surveillance/CS/CountSubjectsCS/Program.cs` (`ConnectDevice`) |
| Reading event details safely | `Samples/.../surveillance-web-sample/.../eventdata/EventData.java` |
| Photo → face template → watch list | `Samples/.../surveillance-web-sample/.../watchlist/WatchListService.java` |

**Two places the docs and the samples disagree — the samples won:**

1. **There are no Java surveillance tutorials.** `Tutorials/Surveillance/` ships C, C++, C#
   and VB only. Everything Java for surveillance lives under `Samples/Surveillance/Java/`.
   So the control flow was taken from the C# `CountSubjects` tutorial and the exact Java
   signatures were verified with `javap` against `Bin/Java/neurotec-surveillance.jar`.
2. **`NBiometricEngine`, not `NBiometricClient`.** The Java *biometrics* tutorials
   (`enroll-face-from-image`) use `NBiometricClient`; the Java *surveillance* samples use
   `NBiometricEngine` for watch-list enrolment. We use `NBiometricEngine` — it is the
   closest match to this use case and avoids pulling in `neurotec-biometrics-client`.

A third thing worth knowing: `NSurveillanceSource` has a constructor that takes an
`NMediaReader` directly, so a video file *can* bypass the device plugin. We deliberately do
not use it — routing both the file and the camera through the `Media` device plugin means
the file path and the RTSP path are the same code, which is what makes adding cameras later
a config change rather than a code change.

---

## 1. Missing on this machine — do these before running

Verify at any time with:

```bash
./scripts/check-prerequisites.sh
```

### 1a. `libcuda.so.1` — **hard blocker**

`libNSurveillance.so` lists `libcuda.so.1` as a direct `DT_NEEDED` entry, so the dynamic
loader must resolve it **even with `use-gpu=false`**. Your machine has a GeForce GT 710 and
no NVIDIA driver installed.

> Note on the GPU itself: the GT 710 is Kepler (compute capability 3.5). The SDK bundles
> TensorRT 10 and CUDA 12.8, which need Turing or newer. **GPU acceleration is not
> achievable on this card** — keep `sentiveillance.sdk.use-gpu=false` and run on CPU via the
> bundled OpenVINO. The card is irrelevant here; we only need the library to exist so the
> loader is satisfied.

**Build the stub — no NVIDIA packages:**

```bash
./scripts/build-cuda-stub.sh
```

That compiles `scripts/cuda-stub/libcuda_stub.c` into
`$SENTIVEILLANCE_SDK_ROOT/Lib/Linux_x86_64/libcuda.so.1` (~15 KB). `libNSurveillance.so`
imports exactly 7 CUDA driver symbols (`cuInit`, `cuDeviceGetCount`, `cuDeviceGet`,
`cuDeviceGetAttribute`, `cuDeviceGetName`, `cuDeviceGetUuid`, `cuGetErrorString`) and is not
linked `BIND_NOW`, so a stub providing those names satisfies the loader; they return
"no CUDA device" if the SDK ever probes. The file sits inside the SDK tree, found through
`RUNPATH $ORIGIN` and the `LD_LIBRARY_PATH` that `scripts/run.sh` exports — nothing
system-wide changes. Re-run the script after moving or re-extracting the SDK. To undo,
delete the file.

> ### Do NOT install the NVIDIA driver packages for this
>
> An earlier version of this document recommended `sudo apt install libnvidia-compute-580`
> and claimed it would not touch the display driver. **That was wrong.** apt resolves it
> together with `nvidia-kernel-common-580` and `nvidia-firmware-580`, which install modprobe
> rules, firmware blobs and initramfs hooks. Branch 580 dropped Kepler support, so on a
> GT 710 the machine boots into a confused graphics state and the display changes
> unexpectedly. It was installed on 2026-11-05 and purged on 2026-11-08; the display is back
> on `nouveau`. Do not reinstall it — the stub above replaces it completely.
>
> The same applies to NVIDIA's own stub from `cuda-driver-dev-13-1`: it would work, but it
> needs NVIDIA's apt repo for a file we can compile in one second from source.

### 1b. OpenSSL 1.1 — **blocks all video input**

The SDK's bundled ffmpeg 7.x (`libavformat.so.61`, `libavcodec.so.61`, `libavutil.so.59`)
was linked against OpenSSL 1.1. Ubuntu 26.04 ships OpenSSL 3 only, and the two ABIs are not
compatible — symlinking `libcrypto.so.3` will **not** work.

These libraries are `dlopen`ed by `libNMedia.so` at runtime, so the app starts fine and then
fails to read video, which is a confusing error. Fix it up front:

```bash
./scripts/install-missing-libs.sh
```

That downloads Ubuntu's `libssl1.1` package and copies `libcrypto.so.1.1` / `libssl.so.1.1`
into `Lib/Linux_x86_64/`, next to the ffmpeg libraries. **Nothing is installed
system-wide** — no EOL OpenSSL on your system path. `scripts/run.sh` puts that directory on
`LD_LIBRARY_PATH` so the loader finds them. (`LD_LIBRARY_PATH` rather than
`jna.library.path` is required because the ffmpeg libraries carry no `RUNPATH` of their own.)

If you also want ONVIF camera discovery (`NdmOnvif.so`), the same two files cover it.

### 1c. Maven

`mvn` is not on your PATH, but `./mvnw` works and Maven 3.10.0 is already in
`~/.m2/wrapper`. Use `./mvnw` everywhere; installing Maven is optional.

### 1d. PostgreSQL credentials — **needed**

The server is up (PostgreSQL 18.6, port 5432) but there is no `raihan` role, so I could not
connect to confirm the `sentiveillance` database. Fill these in
`src/main/resources/application.properties`:

```properties
spring.datasource.username=<your role>
spring.datasource.password=<password>
```

If you need a role, as the `postgres` superuser:

```bash
sudo -u postgres psql -c "CREATE ROLE raihan LOGIN PASSWORD 'yourpassword';"
sudo -u postgres psql -c "GRANT ALL ON DATABASE sentiveillance TO raihan;"
sudo -u postgres psql -d sentiveillance -c "GRANT ALL ON SCHEMA public TO raihan;"
```

`spring.jpa.hibernate.ddl-auto=update` creates the `employee` and `detection_event` tables
on first run, so no SQL migration is needed yet.

### 1d-bis. The SDK's broken RUNPATHs - handled automatically

The bundled native libraries cannot be found by the dynamic loader on their own:

- the nine `libopencv_*.so.4.4` files were built with `RUNPATH=/tmp/working/build/lib`
  (Neurotechnology's build machine), and `libopencv_core` has a row of empty entries;
- the ffmpeg / VLC libraries (`libavformat`, `libavcodec`, ...) carry **no** `RUNPATH` at all
  and are `dlopen`ed by `libNMedia.so` at runtime rather than linked.

The loader resolves a library's `DT_NEEDED` entries using *that library's own* runpath, so the
`$ORIGIN` runpath on `libNSurveillance.so` covers its direct dependencies but nothing they
pull in underneath. Two different failures result:

| missing group | symptom |
|---|---|
| OpenCV | `UnsatisfiedLinkError: libopencv_features2d.so.4.4: cannot open shared object file` at `new NSurveillance()` |
| ffmpeg / VLC | source connects, immediately reports `running = false`, **zero events**, nothing in the log |

`jna.library.path` does not help: it tells JNA where `libNSurveillance.so` is, not how the OS
loader walks that library's dependency tree. `LD_LIBRARY_PATH` does work, but it is read when
the process starts, so it cannot be set from inside `main()`.

**`SdkBootstrap.preloadBrokenRunpathLibraries` solves this in-process**: it loads all 20
affected libraries by absolute path, in dependency order, before anything touches a
`com.neurotec.*` class. `System.load` takes an absolute path, so the broken runpaths never
apply, and once a library is in the process the loader satisfies later `DT_NEEDED` and
`dlopen` requests by SONAME from what is already loaded. Every one of these files has a
SONAME equal to its file name, which is exactly the string the dependents ask for.

It prints what it did:

```
[SdkBootstrap] native preload: 20/20 present (works around the SDK's unusable RUNPATHs)
```

**The practical effect: no `LD_LIBRARY_PATH` is needed.** Running from IntelliJ, from a plain
`java -cp ...`, or from `scripts/run.sh` all work identically. `scripts/run.sh` still exports
the variable (harmless, and a safety net if a future SDK adds a library this list misses), and
`.idea/runConfigurations/Sentiveillance__with_SDK_.xml` ("Sentiveillance (with SDK)") sets it
too. If the preload is ever incomplete, `SdkBootstrap` falls back to printing the
`LD_LIBRARY_PATH` instructions.

Verified without `LD_LIBRARY_PATH`: 95 detections, `John (#1) score=198`,
`Jenifa (#2) score=186`, no errors.

### 1e. A licence — **CURRENTLY BLOCKING**

`new NSurveillance()` fails unless a licence has been obtained, and `NBiometricEngine` is
licence-gated too, so nothing runs without this.

Start the daemon (needed every boot, and it must be **root**):

```bash
sudo my_files/sdk/SentiVeillance_10_1_SDK/Bin/Linux_x86_64/Activation/run_pgd.sh start
my_files/sdk/SentiVeillance_10_1_SDK/Bin/Linux_x86_64/Activation/run_pgd.sh log
```

**But a running daemon is not a licence. ROOT CAUSE (confirmed 2026-11-05): the
SentiVeillance 30-day trial has EXPIRED.**

The Windows Activation Wizard lists `SentiVeillance`, `SentiVeillance 10`,
`SentiVeillance VH`, `SentiVeillance ALPR`, `SentiVeillance Server` and
`SentiVeillance Cluster` all as **Expired**, while unrelated products on the same machine
still show ~4 weeks left. The trial clock is per product and SentiVeillance's started more
than 30 days ago.

Matching symptoms on this Linux machine:

- `NLicense.obtain` returns false for all three components
- `NLicense.getObtainedStatus` returns **`LICENSE_NOT_OBTAINED`** (not `SERVER_OFFLINE`) -
  the licensing layer is reached and refuses, which is what expiry looks like
- `Bin/Licenses/` holds no `.lic`; `Activation/LicenceUsage.log` is header-only
- Neurotechnology's own prebuilt `Bin/Java/surveillance-sample.jar` fails identically, so
  this is not application code

**There is no technical workaround.** None of these help: the wizard's "Configure" button
(selecting different trial products *replaces* them, it does not restore time),
`TrialFlag.txt`, `NLicenseManager.setTrialMode` (these only choose which mode to use), or
reinstalling the SDK. The expiry is held on Neurotechnology's side.

Resolution: contact support@neurotechnology.com for a **trial extension** or a **purchased
licence** for `SentiVeillance` (plus `SentiVeillanceVH` for body tracking). Run
`Activation/id_gen` and `Activation/diagnostic_report.sh` and attach their output.

Useful diagnostics are built in: on failure `LicenseService` logs trial mode, the HTTPS
setting, the writable licence storage path, the working directory, and the
`NLicenseObtainedStatus` for each component. `SERVER_OFFLINE` there would mean a
connectivity problem instead; `LICENSE_NOT_OBTAINED` means no valid licence.

Once a licence exists, with a serial number:

```bash
cd my_files/sdk/SentiVeillance_10_1_SDK/Bin/Linux_x86_64/Activation
sudo ./run_license_activation.sh -s XXXX-XXXX-XXXX-XXXX -o license.lic
cp license.lic ../../Licenses/
# then add to pgd.conf:  LicenseFile = <abs path>/Bin/Licenses/license.lic
sudo ./run_pgd.sh restart
./run_pgd.sh log          # should now report loaded licences
```

Then set `sentiveillance.sdk.trial-mode=false` for a purchased licence. Trial licences also
require a constant internet connection.

### 1f. One-time: put the SDK jars in your local Maven repo

Already done, but you will need it again on a fresh machine. This is the SDK's own
documented step — its root `pom.xml` is a component installer:

```bash
cd my_files/sdk/SentiVeillance_10_1_SDK && ../../../mvnw validate
```

It writes only to `~/.m2/repository/com/neurotec/`. To undo: `rm -rf ~/.m2/repository/com/neurotec`.

---

## 2. Run it

```bash
# 1. licensing daemon (once per boot)
my_files/sdk/SentiVeillance_10_1_SDK/Bin/Linux_x86_64/Activation/run_pgd.sh start

# 2. confirm everything is green
./scripts/check-prerequisites.sh

# 3. go
./scripts/run.sh
```

Do not use `./mvnw spring-boot:run` directly — you would lose `LD_LIBRARY_PATH` and video
decoding would fail. Spring DevTools restart is disabled on purpose: a restart gives Spring
a fresh class loader, which tries to load `libNCore.so` a second time in the same process,
and JNA refuses.

### What you should see

```
[SdkBootstrap] SDK root      : .../SentiVeillance_10_1_SDK
[SdkBootstrap] native libs   : .../Lib/Linux_x86_64
Trial mode: true
License SentiVeillance -> obtained
Detectors available - faces: true, vehicles&humans: true
Enrolled John as EMP-1 (... byte template) from Employee_1_John.png
Enrolled Jenifa as EMP-2 (... byte template) from Employee_2_Jenifa.png
Watch list loaded: 2 template(s)
Engine settings - gpu=false, matchingThreshold=48, quality>=30, interOcular>=24px, template=LARGE
Source 'source-1' ready: ... | modalities=[FACES, VEHICLES_AND_HUMANS]
Surveillance running = true
[source-1] SUBJECT_APPEARED trace=1 UNKNOWN score=null face=false box=84x210 frame=37
[source-1] SUBJECT_TRACK    trace=1 EMP-1 score=71 face=true box=86x214 frame=52
[source-1] SUBJECT_DISAPPEARED trace=1 John (#1) score=78 face=true ...
```

---

## 3. Test it

```bash
curl -s localhost:8080/api/status      | jq
curl -s localhost:8080/api/employees   | jq
curl -s 'localhost:8080/api/detections?limit=20' | jq
ls -la var/snapshots/          # best frame of every finished track
```

Direct in the database:

```sql
-- one row per visit, with who it was
SELECT trace_index, min(event_time) AS entered, max(event_time) AS left_frame,
       max(match_score) AS best_score, max(matched_template_id) AS who
FROM detection_event
GROUP BY trace_index
ORDER BY entered DESC;
```

### Reading the results

- **`trace_index` is one visit, not one frame.** `SUBJECT_APPEARED`, many `SUBJECT_TRACK`
  and one `SUBJECT_DISAPPEARED` all share it. Attendance should aggregate over the trace.
- **`has_face_details = false` is normal** on early events — the person is being tracked by
  body before a usable face is seen.
- **Score rises along a track.** The engine accumulates evidence across frames, so the
  `SUBJECT_DISAPPEARED` score is the one to trust.

### Tuning

| Property | Effect |
|---|---|
| `faces.matching-threshold` | Recognition decision. Lower → more identifications, more false ones. If John is never recognised, drop to 36 and watch the scores. |
| `faces.minimal-inter-ocular-distance` | Pixels between the eyes for a face to be recognisable. Ceiling cameras give small faces — lower it (to ~16) to reach further, at some accuracy cost. |
| `faces.quality-threshold` | How good a frame must be before a template is extracted. Lower → more attempts on blurry frames. |
| `source.detect-people` | Adds the vehicle/human detector so people are tracked by body when no face is visible. |

Change one at a time and compare `max(match_score)` per trace.

---

## 4. Your Dahua camera (DH-IPC-HDW2431TP-AS-S2)

### On the camera

1. Give it a static IP (or a DHCP reservation) — the config stores a URL, so the address has
   to stay put.
2. Web UI → **Setup → Camera → Video**, and note the resolution/FPS of both streams.
3. Web UI → **Setup → Network → RTSP**, confirm port **554** is enabled.
4. Create a dedicated read-only user for this app rather than reusing `admin`.
5. Turn **off** on-camera overlays (timestamp, channel name) in the region where faces
   appear — burnt-in text degrades detection.

### Stream URLs

```
Main stream (full resolution):
  rtsp://USER:PASS@CAMERA_IP:554/cam/realmonitor?channel=1&subtype=0
Sub stream (lower resolution, much cheaper to decode):
  rtsp://USER:PASS@CAMERA_IP:554/cam/realmonitor?channel=1&subtype=1
```

Check it outside the app first — if VLC or ffprobe cannot open it, neither can the SDK:

```bash
ffprobe -rtsp_transport tcp "rtsp://USER:PASS@CAMERA_IP:554/cam/realmonitor?channel=1&subtype=0"
```

### Switch the app over

Two ways, and neither needs a code change.

**From the dashboard (no restart).** Live view → **IP camera** → **Add camera…**. Paste the
full RTSP URL, or leave the URL empty and fill in host / port / user / password / channel /
stream and let the app assemble the Dahua URL — a password containing `@` or `:` is
percent-encoded for you, which is the single most common reason a camera that works in its
own web UI refuses to open over RTSP. **Test** connects, reports what answered and
disconnects again without disturbing whatever is playing; **Save & use** makes it the live
source. Saved cameras persist in the `ip_camera` table, so they are still there after a
restart.

**From the configuration (used at boot).** In `src/main/resources/application.properties`:

```properties
sentiveillance.source.type=IP_CAMERA
sentiveillance.source.name=gate-camera
sentiveillance.source.url=rtsp://svc:secret@192.168.1.108:554/cam/realmonitor?channel=1&subtype=0
#sentiveillance.source.file=   <- ignored for IP_CAMERA
```

A camera configured this way is also registered in the dashboard's camera list on startup,
so you can play a video file and then switch back to it without editing the file again.

The password is masked in the logs, in the API and in the browser. It is **not** encrypted at
rest: it sits in `application.properties` or in the `ip_camera.url` column in plain text, the
same way the database password does. Give the app a dedicated read-only camera account rather
than the camera's `admin` login.

**Use the main stream.** This camera is 4MP (2688×1520); at a ceiling mount the sub stream's
faces are usually below the inter-ocular-distance limit. Start on the main stream, and only
drop to the sub stream if CPU cannot keep up — then lower
`minimal-inter-ocular-distance` to compensate, and expect lower accuracy.

**Mounting matters more than any setting.** The SDK recognises faces, so the camera needs to
see faces, not the tops of heads. Aim for 15–30° from horizontal at the point people walk
past, not straight down.

---

## 4b. Operating it day to day

### Every boot — start the licensing daemon

```bash
sudo my_files/sdk/SentiVeillance_10_1_SDK/Bin/Linux_x86_64/Activation/run_pgd.sh start
```

It does not survive a reboot. To make it permanent, the SDK ships a systemd template at
`Bin/Linux_x86_64/Activation/pgd.Sample.service` — copy it to `/etc/systemd/system/pgd.service`,
fix the paths inside, then `sudo systemctl enable --now pgd`.

### Start / stop the pipeline

```bash
./scripts/check-prerequisites.sh    # always run this first
./scripts/run.sh                    # Ctrl-C to stop
```

The app keeps running after a video file ends, because it is also a web server. With a
camera it runs until stopped.

### Control it while running

| Action | Command |
|---|---|
| Health + counters | `curl -s localhost:8080/api/status` |
| Who is enrolled | `curl -s localhost:8080/api/employees` |
| Recent events | `curl -s 'localhost:8080/api/detections?limit=50'` |
| Pause the source | `curl -X POST localhost:8080/api/surveillance/stop` |
| Resume it | `curl -X POST localhost:8080/api/surveillance/start` |
| Saved face frames | `ls var/snapshots/` |
| What is playing now | `curl -s localhost:8080/api/source` |
| Saved cameras | `curl -s localhost:8080/api/source/cameras` |

### Add or change an employee

Drop a photo named `Employee_<number>_<name>.png` into `my_files/` and restart. Enrolment
runs at startup, skips anyone already in the database, and the number becomes the watch-list
id (`Employee_3_Ayesha.png` -> `EMP-3`).

To re-extract templates for people already enrolled (after changing
`faces.template-size`, or replacing a photo), set
`sentiveillance.enrollment.re-enroll-existing=true` for one run, then set it back.

### Switch between the video file and the camera

The **Live view** panel has a **Video file / IP camera** switch. Both kinds of source go
through the same door in the SDK — the `Media` device plugin, with a `FileName` parameter for
a file and a `Url` parameter for a camera — so switching between them keeps the watch list,
the thresholds and the counters exactly as they are. A failed switch restores the previous
source.

**Video file.** **Choose video** imports a video from your computer; the engine switches to it
and starts capture once the upload finishes. The dropdown replays previously uploaded videos
without another upload. Files are kept in `sentiveillance.source.uploads-directory` (default
`var/uploads`) with unique filenames, so importing another file with the same name does not
overwrite an active video. Supported extensions: mp4, avi, mkv, mov, m4v, mpg, mpeg, wmv, flv,
webm, ts — actual decoding depends on the SDK Media plugin and the installed codecs. Uploads
default to a 2 GB multipart limit; adjust `spring.servlet.multipart.max-file-size` and
`spring.servlet.multipart.max-request-size` together if needed.

**IP camera.** The dropdown lists saved cameras, with what each one reported the last time it
answered. **Test** connects through the SDK, reports resolution / frame rate / codec, and
disconnects — the live source is untouched, so it is safe to test one camera while another is
running. **Use** makes the selected camera the live source. **Remove** deletes it from the
list, and refuses while it is the live source. Cameras live in the `ip_camera` table and
survive a restart; the one in `application.properties` is added to the list automatically.

A camera can fail in three ways that the SDK reports identically, so they are separated before
the SDK is involved and the dashboard says which happened:

| What you see | What it means |
|---|---|
| *Not a camera URL…* | the URL is malformed — checked without touching the network |
| *Nothing answered at HOST:554* | the host or the RTSP port is unreachable (plain TCP connect, 3 s) |
| *Camera did not answer within 20s* | the port is open but the stream never opened — usually wrong credentials or a wrong path |
| *Connected, stream not negotiated yet* | the plugin has the device but has not pulled a frame yet; normal before capture starts |

The **Source** panel beside the live video shows what the engine actually holds: the masked
address, the device make/model the plugin reported, the negotiated stream (`2688×1520 @ 25 fps
H264`), which decoder backend is in use, whether capture is running and whether a frame has
arrived yet — a camera that connected but delivers nothing shows as *running, no frame yet*
rather than looking healthy. For a camera it also states how large a head has to be **on that
stream** for recognition to be possible, derived from
`faces.minimal-inter-ocular-distance` — the same arithmetic `scripts/check-camera.sh` prints,
and the most common reason a correctly configured ceiling camera never identifies anybody.

The API behind all of it:

| Call | Does |
|---|---|
| `GET /api/source` | what is playing now, either kind, plus the SDK's view of the device |
| `GET /api/source/videos` | uploaded videos |
| `POST /api/source/video` (multipart `file`) | import a video and switch to it |
| `POST /api/source/video/{name}` | replay an uploaded video |
| `GET /api/source/cameras` | saved cameras, passwords masked |
| `POST /api/source/cameras` (`?activate=true`) | save a camera, optionally make it live |
| `POST /api/source/cameras/test` | test a camera that is not saved yet |
| `POST /api/source/cameras/{id}/test` | test a saved camera |
| `POST /api/source/cameras/{id}/activate` | make a saved camera the live source |
| `DELETE /api/source/cameras/{id}` | remove a saved camera |

Example, straight from the shell:

```bash
curl -s -X POST localhost:8080/api/source/cameras/test \
  -H 'Content-Type: application/json' \
  -d '{"host":"192.168.1.108","username":"svc","password":"secret","subtype":0}'
```

Selections apply immediately and the camera list is persistent, but the *boot* source is still
whatever `sentiveillance.source.*` says — a restart goes back to that.

### Faster unknown-person enrollment

The **Unidentified people** queue captures the first usable unknown face immediately. It
copies an SDK template directly when available; otherwise a reusable biometric engine
extracts one on the candidate worker. There is no deliberate first-capture delay. Work is
bounded to four outstanding captures and failed attempts can retry after 250 ms.

Pending face templates are matched across visits, sources and restarts. A matching person
keeps the existing queue ID; only a strictly higher SDK face-quality score replaces the
image and template together. Equal or lower quality leaves the saved capture intact.
Legacy pending rows have unknown quality until a scored capture replaces them; matching
legacy duplicates are merged when that person is seen again. This adds a nullable
`unknown_subject.face_quality` column through the existing Hibernate schema update.

The browser receives a queue-change event after the database transaction commits and
refreshes immediately, with one-second polling as a reconnect fallback. Updated cards keep
their name input while their image and quality refresh. Enrollment/dismissal and capture
updates use row locks so an in-flight improvement cannot overwrite enrollment or recreate
a dismissed card. Face matching still requires a usable biometric template: a detection
box by itself does not guarantee immediate enrollment, and very poor views can fail to
match the same identity across visits.

Validation covered queue updates, face matching, and preservation of typed names. A
sample-video diagnostic verified early queue admission with an isolated repository. The
installed SDK also aborted during native shutdown with candidate capture disabled; that
shutdown problem remains unresolved. Admission measurements exclude PostgreSQL and
browser/network latency. The temporary tests added for this work were removed after
validation at the user's request.

### Recognized faces on the dashboard

The **Recognized people** panel beside the live video displays a face cropped from the
video, the enrolled name/template ID, the SDK match score, source, and last-seen time.
It updates while a recognized person is tracked, without waiting for them to leave.
Cards show the 24 most recently seen people, with one card per enrolled identity.
Face images refresh at most every five seconds per person and the panel polls every two
seconds. These previews stay in memory and clear when the source changes or the app
restarts. A card appears once the detector supplies usable face geometry; the existing
unidentified queue and persisted snapshots continue to work separately.

### Turning this into attendance

Not built yet, and deliberately so: the detection layer has to be proven on real camera
footage first. When it is, the shape is a job that groups `detection_event` by
`(source_name, trace_index)`, takes the best-scoring identified row per trace, and writes
one attendance record per employee per day — first trace in, last trace out. The index on
`(source_name, trace_index)` is already there for that query.

## 5. Going multi-camera later

Nothing here assumes one camera — only the configuration does:

- `NSurveillance` is already a single shared engine with a `Sources` collection, and the
  code starts and stops **per source** (`startSource`) rather than globally.
- `SurveillanceSourceFactory` builds a source from one `Source` config block; making
  `sentiveillance.source` a list and looping is the whole change.
- The `ip_camera` table already holds many cameras — today only one of them can be the live
  source at a time, because the engine keeps a single `source` field. Running several means
  holding a source per camera row instead, which is what `startSource`/`stopSource` being
  per-source already allows.
- `DetectionEvent.sourceName` is already on every row, and `trace_index` is only unique
  *within* a source — so the natural key is `(source_name, trace_index)`, which is how the
  index is already defined.

The one thing that will need real thought at 20–30 cameras is throughput: one CPU cannot
decode and run inference on 30 × 4MP streams. That is when `use-gpu=true` on suitable
hardware, `detector-scaling`, and per-source regions of interest start to matter.
