# Contributing to SwiftSlate

Thanks for wanting to contribute! Here's everything you need to know.

## Project Philosophy

- **Zero external dependencies** for networking/JSON — use `HttpURLConnection` and `org.json`
- **Minimal APK size** — currently ~1.2 MB, keep it that way
- **API 23+ compatibility** — every feature must work on Android 6.0+
- **Privacy first** — no analytics, no telemetry, no data leaves the device except to the user's configured AI provider

## Development Setup

```bash
git clone https://github.com/YOUR_USERNAME/SwiftSlate.git
cd SwiftSlate
```

Open in Android Studio (latest stable), sync Gradle, and build:

```bash
./gradlew assembleDebug
```

You'll need a real device to test — the Accessibility Service doesn't work properly in emulators.

## What You Can Contribute

| Area | What's needed |
|:-----|:-------------|
| **Commands** | New built-in AI commands with useful prompts |
| **Providers** | Integrations with OpenAI-compatible endpoints |
| **Translations** | New language strings in `app/src/main/res/values-XX/strings.xml` |
| **Bug fixes** | Especially around text replacement edge cases in specific apps |
| **UI** | Material 3 improvements that respect both AMOLED dark and light themes |

## Architecture Overview

```
service/AssistantService.kt  → Core accessibility event handling
api/*Client.kt               → AI provider communication
manager/KeyManager.kt        → Encrypted key storage + rotation
manager/CommandManager.kt    → Command CRUD + trigger matching
ui/*Screen.kt                → Jetpack Compose screens
```

Key behaviors to understand before touching core code:
- **Trigger detection** uses a fast-exit optimization (last character check) before full scan
- **Longest match** wins when multiple triggers could match
- **Text replacement** tries `ACTION_SET_TEXT` first, falls back to clipboard paste
- **Spinner animation** runs inline in the text field during AI processing

## Testing Guidelines

Since there's no test suite yet, manual testing is critical:

1. **Enable the Accessibility Service** on your device
2. **Test trigger detection** — type triggers in WhatsApp, Gmail, Notes, and Chrome
3. **Verify password fields are skipped** — type a trigger in a password field, nothing should happen
4. **Test both command types** — AI commands (need API key) and text replacer (offline)
5. **Check the undo command** — `?undo` should restore previous text
6. **Verify on API 23** — if you use any newer API, guard it with a version check

## Code Style

- Kotlin, following the existing conventions in the codebase
- No auto-formatting tools — just match what's already there
- Compose UI follows the existing component patterns in `ui/components/`
- Keep functions focused and small

## Submitting a PR

1. Fork and create a branch: `feature/your-thing` or `fix/the-bug`
2. Make your changes
3. Test on a real device with the Accessibility Service enabled
4. Run `./gradlew assembleDebug` — it must build cleanly
5. Open a PR against `master` — fill in the template

## What I Won't Merge

- PRs that add third-party networking/JSON libraries
- Changes that break API 23 compatibility without version guards
- Features that collect user data or add telemetry
- Code that doesn't build cleanly

## Questions?

Open a Discussion on the repo or reach out to me directly. Happy coding!
