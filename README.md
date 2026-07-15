# OrangePet

A Shimeji-style floating desktop pet for Android. OrangePet renders a small
sprite in a `TYPE_APPLICATION_OVERLAY` window that sits above other apps and
runs a cute, self-directed behavior system — walking, idling, blinking,
hopping, looking around, showing a heart, sleeping, playing ball, skipping —
instead of pacing back and forth on a fixed loop. It reacts to charging, to
incoming messages, and (optionally) to a daily morning/lunch/night schedule
with AI-or-local greeting text, and it always freezes in a faint "resting"
state when the battery drops to 30% or below (unless it's charging).

## v3.1 bug-fix pass — "pet appears but never animates"

If you're updating from an earlier `OrangePet-v3.zip`: that build had a real
bug where the pet would render its first frame and then never visibly
update again — looking exactly like "idle, not moving, doing nothing"
regardless of which behavior was actually running underneath. Root cause
and fixes, all in this build:

1. **Lifecycle-ordering bug (the primary cause).** `FloatingPetService`
   attached the `ComposeView` to `WindowManager` *while its `Lifecycle`
   was still `CREATED`*, and only moved it to `STARTED` afterward.
   Compose's Recomposer only actively recomposes once the associated
   `Lifecycle` reaches `STARTED` — attaching first and starting second
   meant the very first frame could render from the default state and then
   nothing would ever visibly update again, since nothing was actually
   recomposing. **Fixed** by moving the `STARTED` transition to happen
   *before* `overlay.attach(...)` in `FloatingPetService.initializeOverlay()`.
2. **A real (if self-healing) startup race.** A `StateFlow` collector could
   deliver its default value during `onCreate()` — before the overlay was
   even attached — and that was enough to launch a behavior job prematurely.
   It self-corrected a moment later, but it was a genuine gap. **Fixed**
   with an explicit `isStarted` guard on `PetBehaviorController`: nothing
   can launch a behavior job until `start()` is called, which now only
   happens after the overlay is fully attached with an active lifecycle.
3. **No recovery from an unexpected exception.** If any behavior function
   ever threw, the coroutine died silently and nothing restarted it.
   **Fixed**: `launchBehaviorJob` now catches non-cancellation exceptions,
   waits a second (so a deterministic bug can't spin in a tight loop), and
   forces a fresh priority resolution — so *something* always resumes.
4. **Tuning, on top of the above**, so a quiet moment doesn't *read* as
   frozen even when everything's working correctly: `WALKING`'s weight
   went from 26% to 32% (and `IDLE` from 26% down to 18%), `IDLE`'s typical
   duration shortened, and its breathing animation doubled in amplitude
   (2% → 4% scale) so it's clearly visible rather than nearly imperceptible
   on a small sprite.

See "v3 environment verification" near the end of this file for exactly
how these were diagnosed and verified without a physical device.

## Package

- Namespace / applicationId: `com.orangepet.app`
- Minimum SDK: 26
- Compile / Target SDK: 35

## Tech stack (pinned — unchanged since v1)

| Component | Version |
|---|---|
| Kotlin | 2.0.21 |
| Kotlin Compose plugin | 2.0.21 |
| Android Gradle Plugin | 8.7.3 |
| Gradle wrapper | 8.9 |
| Compose BOM | 2024.12.01 |
| Core KTX | 1.15.0 |
| Activity Compose | 1.10.0 |
| Lifecycle | 2.8.7 |
| Saved State | 1.2.1 |
| Coroutines Android | 1.9.0 |
| Java | 17 |

**v3 adds zero new Gradle dependencies.** Every new feature (settings
screens, Gemini calls, scheduling, local adaptation) is built from what was
already pinned, plus `java.time` (native since API 26) and `org.json`/
`HttpURLConnection` (built into the Android SDK). See "v3 scope notes"
below for why, and what that ruled out.

## Architecture (v3 layered structure)

```
app/src/main/java/com/orangepet/app/
├── MainActivity.kt              — overlay/notification permission flow, entry point
├── settings/                    — onboarding + settings screen, preferences, API key storage
├── service/                     — FloatingPetService, WindowManager, battery monitoring
├── behavior/                    — state machine: enum/state/priority/scheduling/rendering
├── ai/                          — PetBrain interface, Gemini + offline implementations
├── context/                     — time-of-day bucketing + local engagement counters
├── notification/                — notification-reaction listener + scheduled-greeting posts
└── model/                       — UserProfile data class
```

- **`FloatingPetService`** is deliberately thin: it owns only what's
  inherently Android-specific (foreground-service lifecycle, the
  notification, and being the `LifecycleOwner`/`SavedStateRegistryOwner`/
  `ViewModelStoreOwner` a window-attached `ComposeView` needs, since it
  has no Activity to inherit those from). It wires together the four
  classes below and contains no behavior logic, timing math, or AI code.
- **`OverlayWindowController`** (`service/`) owns `WindowManager`: attach,
  move, and safely remove the `ComposeView`. Checks `isAttached` before
  every `updateViewLayout`/`removeView` call.
- **`BatteryMonitor`** (`service/`) — one `BroadcastReceiver` for
  `ACTION_BATTERY_CHANGED` / `ACTION_POWER_CONNECTED` /
  `ACTION_POWER_DISCONNECTED`, exposed as a `StateFlow<BatteryStatus>`.
- **`PetBehaviorController`** (`behavior/`) owns the **single master
  behavior `Job`**, resolves which "track" (see below) should be active,
  and implements every individual behavior as a small suspend function
  that's a sequence of `StateFlow` updates plus `delay()` calls.
- **`PetScheduleController`** (`behavior/`) owns the morning/lunch/night
  schedule: computes the next boundary with `java.time`, persists
  once-per-day firing, recalculates on `ACTION_TIME_CHANGED`/
  `ACTION_TIMEZONE_CHANGED`/`ACTION_DATE_CHANGED`, and is the **only**
  place in the app that calls a `PetBrain` — which is what guarantees
  Gemini is never called from the animation loop.
- **`PetBrain`** (`ai/`) is the interface the rest of the app depends on;
  `OfflinePetBrain` (canned phrases, always available) and `GeminiPetBrain`
  (see below) both implement it, and every failure mode in
  `GeminiPetBrain` falls back to `OfflinePetBrain` rather than throwing.
- **`InteractionLearningRepository`** (`context/`) stores simple local
  shown/positive/dismissed counters; `AdaptationMath` turns them into a
  clamped `[0.5x, 1.5x]` weight multiplier. See "Privacy-safe adaptation".
- **`UserPreferencesRepository`** (`settings/`) stores the user's name,
  toggles, and schedule times via `SharedPreferences`, exposed as a
  `StateFlow<UserProfile>` — "exposes changes through Flow" without adding
  a DataStore dependency, which wasn't genuinely required for this data's
  shape.

## Priority system

`PetPriority.resolveTrack()` (a pure function, unit-tested) resolves
exactly one of five tracks, highest first:

1. **FAINTED** — battery ≤ 30% and not charging. Absolute priority over
   everything, including night sleep: a dead-battery pet doesn't get a
   bedtime animation, it just faints.
2. **NIGHT_SLEEP** — within the configured night window (default
   10pm–7am, handles the midnight wraparound). Beats charging: a phone
   charging overnight still shows the pet sleeping, not bouncing happily
   at 2am.
3. **CHARGING** — plugged in, not fainted, not night. Shows
   `orange_pet_happy` with a gentle repeating bounce.
4. **EVENT** — a pending one-shot interrupt: a scheduled greeting/lunch/
   goodnight message, or a message-notification reaction. Runs once, then
   the controller re-resolves the track from scratch (so if charging
   started *during* the event, it resumes into CHARGING, not RANDOM).
5. **RANDOM** — the weighted-random scheduler (below).

Whenever the resolved track changes, `PetBehaviorController` cancels
whatever `Job` is currently running and starts the replacement — there is
**never more than one behavior `Job` at a time.** That cancellation always
goes through a `finally` block that resets every temporary visual field
(bob, rotation, heart, speech, ball, food, sleep text), so a behavior
interrupted mid-hop or mid-fade never leaves stale visual state behind —
not just on each function's normal return path.

## Random weighted scheduler

| Behavior | Weight | Typical duration |
|---|---|---|
| Walking | 32% | 3–8 s, or until an edge is reached |
| Idle (breathing) | 18% | 1.5–3.5 s |
| Blinking | 12% | 100–180 ms (sometimes a double-blink) |
| Looking around | 9% | ~0.8–1.4 s total |
| Playing ball | 8% | 2–4 s |
| Hopping | 8% | 500–900 ms per hop, 1–2 hops |
| Skipping | 5% | 2–4 small hops |
| Showing a heart | 5% | 1–1.5 s |
| Sleeping (short nap) | 3% | 5–10 s |

The same "special" behavior (anything other than idle/walking) is never
picked twice in a row. **Walking** moves the real overlay window using
velocity that eases toward a target speed each frame (exponential
smoothing) rather than a fixed per-tick step, giving an organic
accelerate/cruise feel; reaching an edge triggers a brief pause-and-squash
reaction before turning around.

If **"Context-aware behavior"** is enabled in Settings (off by default),
weights are adjusted by two independent, clamped multipliers before
picking: a deterministic time-of-day bias (`ContextualBias` — e.g. hopping
is slightly more likely in the morning, sleeping slightly more likely at
night) and a learned engagement multiplier from
`InteractionLearningRepository`. **Neither ever reads another app, your
screen, or notification content** — see "Privacy-safe adaptation".

## Scheduled behaviors (morning / lunch / night)

`PetScheduleController` computes the next of the three configured times
using `java.time`, sleeps until then (re-checked at least every 15 minutes
so the night-window flag stays responsive even between named boundaries),
and on each boundary:

1. Fetches greeting text — from Gemini if the user consented and has a
   working key, otherwise from `OfflinePetBrain` — and hands it to
   `PetBehaviorController` as a `PetEvent.Scheduled`.
2. Optionally posts a real (non-foreground) system notification with the
   same text via `PetNotificationManager`, if "Notifications" is enabled.

Each boundary fires **at most once per local date** (persisted as
`lastMorningGreetingDate`/`lastLunchActionDate`/`lastGoodNightDate`), so a
service restart never repeats a greeting. **NIGHT_SLEEPING** (the
scheduled overnight state) is distinct from **SLEEPING** (the short random
nap from the table above) — the goodnight message plays once, then the
NIGHT_SLEEP track takes over automatically since the night window is
already active by that point.

## Ball, skipping, and eating

- **Playing ball**: a small ball prop (`pet_ball`) arcs left-right near the
  pet using `translationX`/`translationY`, entirely inside the transparent
  overlay window — no second `WindowManager` window.
- **Skipping**: 2–4 small hops with alternating rotation and a rope prop
  (`pet_skipping_rope`) shown underneath; no rope physics, just a vector
  line.
- **Eating** (lunch): a subtle chewing bob/scale for ~9–16s with a food
  bowl prop (`pet_food_bowl`), followed by the lunch message.

## AI (Gemini) — implementation note

`GeminiPetBrain` talks to the Gemini REST endpoint directly via
`HttpURLConnection` + `org.json` (both built into the Android SDK)
**instead of** the official `com.google.ai.client.generativeai` /
Firebase AI client SDK. This project was built in a sandbox with no
access to Google's Maven repository, so a new SDK dependency's exact
artifact coordinates and current API surface could not be verified against
a real build — and zero GitHub Actions build errors was the explicit,
stated top priority for this version. A REST call needs no new Gradle
dependency at all, which removes that entire risk category outright. If
you'd rather use the official SDK, it's a contained swap: every other
class depends only on the `PetBrain` interface, never on `GeminiPetBrain`
directly.

Safety measures, all in `PromptPolicy`/`GeminiPetBrain`:

- Short timeouts (6s connect/read, 8s overall via `withTimeout`).
- Rate-limited to at most one call per minute (`PromptPolicy.isCallAllowed`).
- Requests plain text, under 15 words, no markdown/emoji/quotes; the
  response is also sanitized and hard-truncated regardless of what comes
  back (`PromptPolicy.sanitize`).
- The prompt sends **only** the user's display name (which they typed into
  Settings themselves) and which part of the day it is — never screen
  contents, notifications, passwords, or any other private data.
- Every failure mode (missing/invalid key, offline, timeout, malformed
  response, rate limit) falls back to `OfflinePetBrain` rather than
  throwing, and `PetBrain` is only ever called from
  `PetScheduleController` — never from `PetBehaviorController`'s
  walking/animation loop.

## API key storage — security scope (read this)

Per the architecture notes: **private `SharedPreferences` is not a full
security boundary.** `SharedPreferencesHelper`/`PrivatePrefsApiKeyStore`
protect the key from other apps on a normal, non-rooted device — not from
a compromised/reverse-engineered client or a rooted device. For a
personal, user-supplied key (this feature's actual use case) that's a
reasonable, disclosed trade-off; it would not be reasonable for a shared
production credential, which is why the architecture notes recommend a
backend proxy for that case instead. Everything goes through the
`ApiKeyStore` interface, so a Keystore-backed implementation can be
swapped in later without touching any caller. The key is never logged,
never placed in `BuildConfig`, and never shown again in the UI after
saving (masked field, with an explicit "Show"/"Hide" toggle while typing).

## Privacy-safe adaptation (not reinforcement learning)

`InteractionLearningRepository` stores three integers per behavior
(shown / positive / dismissed) in local `SharedPreferences` — never
*why*, never foreground app, never screen content. `AdaptationMath` turns
those into a multiplier clamped to `[0.5x, 1.5x]`, so learned preferences
can only ever nudge weights, never eliminate a behavior or let it
dominate.

**Scope decision:** the overlay stays `FLAG_NOT_TOUCHABLE` in this
version, unchanged from v1/v2's explicit "no tap or drag interaction"
design — changing that would be a meaningful behavior change beyond an
incremental update, and touch hit-testing on a system overlay is exactly
the kind of thing worth getting right deliberately rather than bolting on.
That means real positive/dismissed signal currently only exists for the
three notification-backed behaviors (`GREETING`/`EATING`/`NIGHT_SLEEPING`
— tap a scheduled notification for positive, swipe it away for
dismissed). The other nine random-scheduler behaviors are shown-count
tracked but have no feedback source yet, so their learned multiplier
stays neutral. To make "context-aware" meaningfully visible without touch
input, `ContextualBias` adds a small, deterministic, always-on time-of-day
nudge (e.g. hopping slightly more likely in the morning) independent of
the learned counters — see `PetBehaviorController.computeWeights()`. A
future version could wire real per-behavior feedback in once there's a
tap gesture to source it from.

## Settings / onboarding

A separate `SettingsActivity` (not a destination inside a `NavHost` —
there's currently exactly one screen, so Navigation-Compose would be a new
dependency with no benefit yet) offers: name (skippable), a masked Gemini
API key field with Show/Hide + Save/Remove, three toggles (AI messages,
notifications, context-aware behavior — all off/local-only by default
except notifications), and stepper controls for the three schedule times.
The 🗝️ emoji is used instead of a Material icon for the API key field,
since `androidx.compose.material:material-icons-core` isn't a pinned
dependency and the architecture notes explicitly say the emoji is
acceptable when a Material icon isn't available.

## Pet art

Nine drawables ship in this repo. The four from v2 are real PNGs derived
from the sprite supplied with the project; the four new v3 props are
vector placeholders (the architecture notes explicitly allow this "until
final art is available"):

| Resource | Used for |
|---|---|
| `orange_pet.png` | Default / walking / idle / hopping / playing / etc. |
| `orange_pet_blink.png` | `BLINKING` (closed-eye variant) |
| `orange_pet_happy.png` | `CHARGING` (smiling + blush variant) |
| `orange_pet_faint.png` | `FAINTED` (reduced-opacity variant) |
| `orange_pet_sleep.png` | `NIGHT_SLEEPING` (closed-eye, gently dimmed variant) |
| `pet_ball.xml` | `PLAYING_BALL` prop (vector placeholder) |
| `pet_skipping_rope.xml` | `SKIPPING` prop (vector placeholder) |
| `pet_food_bowl.xml` | `EATING` prop (vector placeholder) |
| `pet_bed.xml` | `NIGHT_SLEEPING` prop (vector placeholder) |

To swap in real art for any `.xml` prop: add the `.png` with the exact
same base resource name, then **delete the `.xml` first** — Android's
resource compiler treats a same-named `.xml` and `.png` coexisting as a
duplicate-resource build error. No Kotlin changes are needed either way;
everything is referenced only via `R.drawable.*`.

The overlay window is 128dp × 180dp (wider than tall) so the heart, sleep
text, chat/speech bubble, ball, bowl, and rope all have transparent room
to render around the 128dp pet image without clipping, while staying far
short of a full-screen surface.

## Testing

Every pure-logic module lives in a file with no `android.*` import
specifically so it's unit-testable on a plain JVM via `./gradlew test` —
no emulator, device, or Robolectric:

| File | Covers |
|---|---|
| `behavior/PetPriority.kt`, `behavior/ScheduleMath.kt` | Priority resolution, night-window wraparound, once-per-day firing |
| `behavior/BehaviorWeights.kt` | Weighted selection, no-repeat-special reroll, multiplier clamping |
| `service/OverlayMath.kt` | Horizontal clamping/boundaries |
| `context/AdaptationMath.kt`, `context/ContextProvider.kt` | Multiplier bounds, time-bucket/weekend detection |
| `ai/PromptPolicy.kt`, `ai/OfflinePetBrain.kt` | Sanitization, rate-limit timing, offline greeting fallback |

Mapping to the spec's "Essential tests" list:

| # | Requirement | How it's verified |
|---|---|---|
| 1 | FAINTED overrides every other state at ≤30% | `SchedulePriorityTest.priority_lowBatteryNotCharging_isFaintedEvenAtNight` |
| 2 | Night sleep blocks random scheduling 10pm–7am | `nightWindow_*` tests (midnight wraparound) + priority resolution puts NIGHT_SLEEP above RANDOM |
| 3 | Battery recovery during nighttime → NIGHT_SLEEPING | `priority_lowBatteryButCharging_atNight_isNightSleepNotFainted` |
| 4 | Battery recovery during daytime starts exactly one scheduler | `reconcile()` only starts a job when the resolved track actually changed |
| 5 | Morning/lunch/goodnight fire once per local date | `shouldFireForDate_*` tests |
| 6 | Timezone/clock changes recalculate the schedule | `PetScheduleController` re-registers on `ACTION_TIME_CHANGED`/`ACTION_TIMEZONE_CHANGED` (architectural; needs a real Context to exercise directly) |
| 7 | Walking stays within the valid horizontal range | `OverlayMathTest.clampX_*` |
| 8 | Ball/food/speech/heart/scale/rotation/offsets reset after cancellation | `launchBehaviorJob`'s `finally` block — architectural, see below |
| 9 | No Gemini key → `OfflinePetBrain` | `GeminiPetBrain.createGreeting` checks `apiKey.isBlank()` first; `FloatingPetService.buildPetBrain()` |
| 10 | Invalid Gemini responses don't crash the overlay | `GeminiPetBrain`'s single catch-all `try/catch` around the whole call |
| 11 | AI calls rate-limited, never in animation loops | `PromptPolicyTest.isCallAllowed_*`; only `PetScheduleController` calls `PetBrain` |
| 12 | Service destruction cancels all work | `FloatingPetService.onDestroy()` — architectural |
| 13 | No WindowManager op after removal | `OverlayWindowController.isAttached` guard — architectural |
| 14 | Learned preferences can't eliminate required safety states | `AdaptationMath`/`BehaviorWeights.applyMultipliers` only ever affect the RANDOM track's weights, never FAINTED/NIGHT_SLEEP/CHARGING selection |
| 15 | Names/keys never appear in logs | No `Log.*`/`println` calls touch `apiKey`, `displayName`, or greeting text anywhere in the codebase (grep-verified) |

Items marked "architectural" need a real `Service`/`WindowManager`/
`Context` to exercise directly and are enforced by construction (single
`Job` field, `isAttached`/`isServiceActive`-style guards, `finally`
blocks) rather than by a JVM-only test — see "v3 environment
verification" below for exactly what could and couldn't be
compiler-verified in the sandbox this was built in.

## Building

```bash
./gradlew --no-daemon clean assembleDebug
```

The debug APK is produced at:

```
app/build/outputs/apk/debug/app-debug.apk
```

## Continuous integration

`.github/workflows/android.yml` is **unchanged from v1/v2** — same
`ubuntu-latest`/wrapper-validation/Java 17/`setup-gradle`/
`assembleDebug`/upload-artifact sequence, builds a debug APK on every push
to `main` and on manual dispatch.

## Permissions

| Permission | Reason | Since |
|---|---|---|
| `SYSTEM_ALERT_WINDOW` | Draw the pet overlay above other apps | v1 |
| `FOREGROUND_SERVICE` | Run the overlay as a foreground service | v1 |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Declare the `specialUse` foreground service type | v1 |
| `POST_NOTIFICATIONS` | Foreground-service notification + scheduled-greeting notifications | v1 (scope widened in v3) |
| `INTERNET` | `GeminiPetBrain`'s REST calls — only made if the user supplied a key and consented | **v3, new** |

`PetNotificationListenerService` continues to need no `uses-permission` —
it's gated by system-enforced `BIND_NOTIFICATION_LISTENER_SERVICE` on the
service declaration and the user's explicit "Notification access" grant.
No Accessibility Service, usage-access, exact-alarm, or screen-capture
permission was added, matching the architecture notes' explicit guidance
to avoid all of those for this version's adaptation feature.

## v3 scope notes (deviations from the architecture doc, and why)

- **Gemini SDK → REST calls.** Covered above under "AI (Gemini)".
- **DataStore → SharedPreferences + StateFlow.** `UserPreferencesRepository`
  wraps `SharedPreferences` in a `MutableStateFlow` that refreshes on every
  write, satisfying "exposes changes through Flow" without adding
  `androidx.datastore:datastore-preferences` as an unverified dependency.
- **No Navigation-Compose.** `SettingsActivity` is a second plain Activity,
  not a `NavHost` destination — there's one settings screen; adding a
  navigation dependency for that would be a new, unverified dependency for
  no functional benefit yet.
- **No Hilt/DI framework.** Manual constructor injection + a
  `ViewModelProvider.Factory`, per the architecture notes' explicit
  "avoid... unless the corresponding feature genuinely requires them."
- **`by viewModels()` → `ViewModelProvider(...).get(...)`.** The Kotlin
  `by viewModels()` delegate's exact transitive-dependency source
  (`activity-ktx` vs. bundled in `activity-compose`) couldn't be confirmed
  against a real build here, so `SettingsActivity` uses the more primitive
  `ViewModelProvider` API instead, which is unambiguously available from
  the already-pinned `lifecycle-viewmodel-ktx`. Same reasoning for using
  plain `remember` instead of `rememberSaveable` in `SettingsScreen` (the
  only cost: draft text in the name/API-key fields doesn't survive a
  screen rotation, since the underlying saved data is unaffected either
  way).
- **`PetUiState` field names unchanged from v2** (`facingRight`,
  `translationY`, etc.) rather than renamed to the doc's suggested
  `direction`/`verticalOffsetPx` shape, to avoid touching already-verified
  v2 rendering code for a purely cosmetic rename.
- **No WorkManager, no exact alarms.** The schedule runs as a coroutine
  that sleeps until the next boundary (recalculated on time/timezone
  change, capped so it re-checks at least every 15 minutes) while the
  service is alive — matching the architecture notes' own preference
  ("use WorkManager only for deferrable notifications or recovery... avoid
  exact alarms unless... a strict requirement"). This means scheduled
  greetings only fire while `FloatingPetService` is running; there's no
  boot-completed receiver in this version, so a rebooted device needs the
  app opened once to resume the schedule.
- **No per-app "quiet" rules / Usage Access.** Left for a later phase, per
  the architecture doc's own phase breakdown (Phase 4, opt-in only).

## v3 environment verification

This project was generated and reviewed in a sandbox with **no Android
SDK and no network access to `dl.google.com`/`maven.google.com`/
`services.gradle.org`**, so `./gradlew assembleDebug` cannot complete here
— re-confirmed directly (`./gradlew --version` fails with HTTP 403 from
the egress proxy at `services.gradle.org`). What **was** genuinely done,
rather than just asserted:

- A real, unmodified **Kotlin 2.0.21** compiler (fetched from
  `github.com/JetBrains/kotlin`'s official release assets) compiled every
  Android-framework-free file — 15 files across `behavior/`, `context/`,
  `ai/`, `model/`, and `settings/` — with **zero errors, zero warnings**.
- A standalone runtime harness (not shipped in this repo) then actually
  *ran* 155 assertions against those compiled classes — not just
  compiled them — covering the midnight-wraparound night-window logic,
  every branch of the charging/fainting/night-sleep priority interaction,
  weighted-selection bounds, multiplier clamping, and context bucketing.
  All 155 passed.
- Manual review of every Compose file caught one real bug this way: a
  `Modifier.graphicsLayer { this.alpha = alpha }` call where the
  right-hand `alpha` would have resolved to `GraphicsLayerScope`'s own
  `alpha` property (Kotlin's implicit-receiver shadowing rules), not the
  function's `alpha` parameter — a silent no-op fade, not a compile error.
  Fixed by renaming the parameter (`bubbleAlpha`) so there's no collision;
  the rest of the codebase was checked for the same pattern and has none.
- Every `R.drawable.*`/`R.string.*` reference was grepped and
  cross-checked against the actual resource files/entries — full match,
  no missing resources, no orphaned unused ones.
- The Gradle wrapper jar/scripts are the genuine, unmodified 8.9 wrapper
  (unchanged since v1).
- Every Android-framework-dependent file (the majority of the v3 code —
  `FloatingPetService`, the controllers, the settings UI, etc.) was
  reviewed by hand: cross-package imports traced, constructor/lambda
  signatures checked against call sites, brace/paren balance verified.
  These could **not** be compiled in this sandbox (no `android.jar`, no
  Google Maven for AndroidX/Compose). Treat them as carefully written and
  reviewed, not compiler-verified — the GitHub Actions workflow is the
  first place the complete app actually gets compiled.

### v3.1: diagnosing "pet renders but never animates"

This sandbox still has no device or emulator to reproduce the report on
directly, so the fix came from tracing the exact `onCreate()` →
`onStartCommand()` → `initializeOverlay()` sequence line by line against
what's documented about Compose's lifecycle-aware Recomposer (see fix #1
in the changelog above), rather than from a stack trace. That's a real
limit worth being upfront about: **I'm confident this fix addresses a
genuine bug in the order things happened in, and I'd bet on it being the
cause, but "confident from static analysis" is not the same as "watched
it fail, applied the fix, watched it work."** The `isStarted` guard and
crash-recovery catch in `launchBehaviorJob` (fixes #2–3) are added
defense-in-depth either way — they close off two more ways a behavior job
could end up not running, independent of whether #1 was the exact
mechanism.

If you apply this build and the pet *still* doesn't animate, the most
useful next report would be `adb logcat` output from around the moment
you start the pet service (filtered to your package,
`adb logcat --pid=$(adb shell pidof -s com.orangepet.app)`) — that would
show a real exception if one's being thrown, which would replace this
static-analysis-based diagnosis with an actual stack trace.
