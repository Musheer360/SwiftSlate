# SwiftSlate: WeChat (com.tencent.mm) Compatibility Patch

**Audience:** upstream SwiftSlate maintainers (and anyone who wants to integrate WeChat
support). This document explains *why* WeChat breaks SwiftSlate, the minimal fix we
applied, every file it touches, and how to adopt it upstream.

> Scope: the patch is currently bundled in the `preview` build type only, so it does not
> affect the stable release. We recommend adopting the same structure (see
> "Integration for upstream" below).
>
> Version: this document tracks the **v1.0.76** codebase (upstream `master` at `cabd098`).
> Upstream has since adopted the `srcNull` fallback itself (#125), so the fork now only
> adds: the preview whitelist-name services, the recursive editable-node search refinement,
> the `SwiftSlateDiag` `Log.e` diagnostics, and the UI hints (see §3).

---

## 1. The problem: WeChat actively sabotages accessibility services

WeChat 8.0.74 (versionCode 3120, and presumably all current versions) contains an
**anti-accessibility defense** that detects non-whitelisted accessibility services and
then **wipes (or fakes) the accessibility node tree of the active window**. The visible
symptoms:

- `rootInActiveWindow` returns a tree whose nodes are empty (`rootCls` reports the real
  class only for the root; child `EditText` nodes vanish).
- `TYPE_VIEW_TEXT_CHANGED` events for the chat input either never arrive, or arrive with
  an empty/`null` source.
- SwiftSlate cannot read the text, so `?fix` / `?translate` never trigger.

This is **not** FLAG_SECURE: the WeChat window does not set it (verified with
`dumpsys window`). The empty tree is produced in software, in WeChat's accessibility
bridge.

### Reverse-engineered mechanics (verified against smali)

| Piece | Location (smali) | Behaviour |
|---|---|---|
| Master switch | `AccUtil.smali` — `isAccessibilityEnabled()` | Returns `true` (→ no attack) if **any** of: monkey env, TalkBack touch-explore enabled, a whitelist service is enabled, or the account is server-whitelisted. Otherwise returns `false` → the attack arms. |
| Whitelist match | `AccExptService.smali` — `checkHasServiceInList()` | Reads `Settings.Secure.enabled_accessibility_services` and checks each enabled service string `package/name` with a **`CharSequence.contains()`** against each whitelist entry. |
| Whitelist entries | `AccExptServiceKt.smali` (clinit) | Exactly two, hard-coded: `com.google.android.accessibility.selecttospeak.SelectToSpeakService` and `com.dianming.phoneapp.MyAccessibilityService`. |
| Tree wiping | `base/MapExpandKt.smali` — `clearInfo()`/`toFakeInfo()`; entry `MMAccessibilityDelegateWrap.smali:340` `onInitializeAccessibilityNodeInfo()` | When armed, `needClearNodeInfo()` makes the delegate return without populating the node → empty tree. `needUseFakeInfo()` fabricates dummy text. |
| Server config | `q15/a.smali` + `AccConfigManager.smali` | Fields like `accinfo_clear_strike`, `accinfo_random_strike`, `intercept_stack`; cached in MMKV, expired periodically. |

**The key insight:** the whitelist match is a **string `contains()` on the service's
`package/name`** against two hard-coded class names. WeChat does not verify that the
class actually *belongs* to Google or to Dianming — it only checks the string.

---

## 2. The fix: a no-op service whose class name *is* a whitelist entry

Because the match is `package/name` **contains** a whitelisted FQCN, SwiftSlate can
declare an accessibility service with a class name exactly equal to one of the two
whitelist entries. WeChat then sees a "whitelisted" service enabled, `isAccessibilityEnabled()`
returns `true`, and the tree-wiping attack is never armed. The service itself does
nothing.

Two such services are declared (one per whitelist entry, for redundancy — if WeChat
rotates one entry, the other still matches):

- `com.google.android.accessibility.selecttospeak.SelectToSpeakService`
- `com.dianming.phoneapp.MyAccessibilityService`

Both are no-ops (`onAccessibilityEvent`/`onInterrupt` empty). They exist **only** in the
`preview` source set, never in the stable build.

**Verified on device (Realme RMX2202, Android 14, no root):** after enabling the
compatibility service together with SwiftSlate's main service, WeChat's `rootCls`
reports `android.widget.FrameLayout` again, `TEXT_CHANGED` events fire with a real
source, text is readable, and `?fix` replacement works end-to-end. Requires **no root**
— it relies only on the standard accessibility-settings toggles.

---

## 3. File-by-file change list

### New files (preview-only)

| File | Purpose |
|---|---|
| `app/src/preview/AndroidManifest.xml` | Registers the two no-op compatibility services (label, `BIND_ACCESSIBILITY_SERVICE` permission, standard a11y meta-data). |
| `app/src/preview/java/com/google/android/accessibility/selecttospeak/SelectToSpeakService.kt` | No-op `AccessibilityService`; class name matches whitelist entry #1. |
| `app/src/preview/java/com/dianming/phoneapp/MyAccessibilityService.kt` | No-op `AccessibilityService`; class name matches whitelist entry #2. |

### Modified files

| File | Change |
|---|---|
| `app/build.gradle.kts` | `buildConfigField("String", "WHITELIST_SERVICE", ...)`: declared **empty in `defaultConfig`** (so stable builds compile — the field is referenced from main source) and overridden in the `preview` buildType with `"com.dianming.phoneapp.MyAccessibilityService"`. Used by the Dashboard hint so the UI can tell whether the compat service is enabled. |
| `app/proguard-rules.pro` | `-keep` both no-op service classes' `<init>()` — **R8 must not rename them**, the FQCN *is* the feature. (Class names must survive minification exactly.) Also keeps `Log.e` (it carries `SwiftSlateDiag` diagnostics; `-assumenosideeffects` only strips v/d/i/w). |
| `gradle.properties` | Fork release pin: `versionName=1.0.76`, `versionCode=227` (overridable with `-PversionName`/`-PversionCode`). |
| `app/src/preview/res/values/strings.xml` | Labels for the two services (`SwiftSlate 微信适配` / `SwiftSlate 微信适配（备选）`). |
| `app/src/main/java/.../service/AssistantService.kt` | **(a)** `srcNull` fallback refinement: upstream's #125 fallback used `root.findFocus(FOCUS_INPUT)`, which returns the WebView/container node (not editable) for WebView-based editors. The fork walks the tree for a node that is both **editable and focused** (`findFocusedEditableSource` / `findFocusedEditable`), keeping upstream's throttling + crash hardening. **(b)** Diagnostic `Log.e` output (`SwiftSlateDiag`) on the event pipeline and `replaceText`. **(c)** A `startWindowDump()` debug dump-loop, **disabled** (commented call) — it pokes WeChat's delegate every 3s and made the IME candidate bar jump. |
| `app/src/main/java/.../ui/DashboardScreen.kt` | WeChat-compat hint card: when the main service is on but the whitelist service is off, show a prompt that both must be enabled (preview builds only; guarded by `BuildConfig.WHITELIST_SERVICE` being non-empty). |
| `app/src/main/java/.../ui/SettingsScreen.kt` | A permanent "Accessibility" entry row (opens `Settings.ACTION_ACCESSIBILITY_SETTINGS`) placed inside the Backup card so it does not disturb the fixed-height layout of the About card. |
| `app/src/main/res/values/strings.xml` (+ `values-zh`, `values-zh-rCN`) | New strings: accessibility entry (`settings_accessibility_*`) and Dashboard hint (`dashboard_wechat_hint_*`). |

---

## 4. The srcNull fallback (a genuinely useful side-fix)

While diagnosing, we found that some apps emit `TYPE_VIEW_TEXT_CHANGED` with
`event.source == null` (custom input pipelines, WebViews, etc.). Previously SwiftSlate
aborted on those. **Upstream already adopted this fix in #125** (v1.0.76); the fork only
refines it: upstream's version calls `root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)`,
which for WebView-based editors returns the WebView *container* (not editable). The fork
walks the tree for a node that is both **editable and focused** instead:
when the event carries no source, walk `rootInActiveWindow` and pick the focused editable
node.

- **Works for:** native `EditText` fields that simply don't carry a source in their event.
- **Does not work for:** WebView rich-text editors (e.g. ColorOS Notes). Their editable
  HTML fields are *virtual* nodes never exposed through the accessibility child hierarchy
  (`childCount` is 1 but `getChild(0)` returns null). This is a WebView accessibility
  limitation, not a SwiftSlate bug.

---

## 5. Integration for upstream (recommended)

We kept the patch isolated to `preview` so the stable build is untouched. For upstream we
suggest the same pattern, possibly with a real feature flag:

1. **Keep the two no-op services in a dedicated source set / optional build type** so
   stable builds don't ship classes named after Google/Dianming (see "Ethical / Play
   considerations").
2. **Keep the `-keep` rules** with the exact class names — renaming silently breaks the
   feature.
3. **Keep the `srcNull` fallback** in `AssistantService` unconditionally: it is a pure
   improvement for native EditText apps, independent of WeChat.
4. **Make the Dashboard hint conditional** on the compat service actually being compiled in
   (as done via `BuildConfig.WHITELIST_SERVICE`).
5. **Re-verify the whitelist entries** on new WeChat releases. The two names are hard-coded
   in WeChat's `AccExptServiceKt` clinit; if WeChat adds/removes entries, add/remove matching
   no-op classes.

### Testing checklist
1. Install and enable SwiftSlate main service + one compat service.
2. Open WeChat, enter a chat, focus the input.
3. Confirm `adb logcat -s SwiftSlateDiag:E` shows `rootCls=android.widget.FrameLayout`
   (not `null`) and `TEXT_CHANGED pkg=com.tencent.mm`.
4. Type `hello world ?fix` and confirm the replacement lands in the input field.

---

## 6. Ethical / Play Store considerations (please read)

- The no-op services borrow Google's and Dianming's class names purely to satisfy a string
  comparison in WeChat's client. This is **local, self-use software** — no WeChat server
  is touched, no data leaves the device differently than usual.
- Shipping classes named `com.google.android.accessibility.selecttospeak.SelectToSpeakService`
  in a **public** Play Store build is risky: Play Integrity, WeChat, or OEMs may treat a
  service that impersonates a Google component as suspicious. **Keep it out of the stable
  build** (that is exactly why it lives in `preview`).
- If you want WeChat support in the stable channel without impersonation, the alternatives
  are: (a) prompt users to enable TalkBack touch-exploration or Google's real
  Select-to-Speak (both make `isAccessibilityEnabled()` return true), or (b) negotiate
  with users to keep a preview build. Option (a) needs no code but has UX overhead.

---

## 7. Appendix: quick adb enable/disable

```bash
# Enable main service + Dianming compat (recommended pair)
adb shell settings put secure enabled_accessibility_services \
  "com.musheer360.swiftslate.preview/com.musheer360.swiftslate.service.AssistantService:\
com.musheer360.swiftslate.preview/com.dianming.phoneapp.MyAccessibilityService"

# Verify binding
adb shell dumpsys accessibility | grep "Bound services"
```

(On the stable application id, substitute the package name accordingly.)
