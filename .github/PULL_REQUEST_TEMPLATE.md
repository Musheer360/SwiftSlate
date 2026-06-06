## What does this PR do?

<!-- Brief description of the change -->

## Type of change

- [ ] Bug fix (Accessibility Service, API handling, UI)
- [ ] New command (built-in AI or text replacer)
- [ ] New provider integration
- [ ] UI / theme change
- [ ] Translation / localization
- [ ] Refactor (no behavior change)
- [ ] Documentation

## Testing

- [ ] Tested with Accessibility Service enabled on a real device
- [ ] Verified trigger detection still works in WhatsApp / Gmail / Notes
- [ ] Text replacer commands execute instantly (no regressions)
- [ ] AI commands return proper responses with Gemini / Groq
- [ ] Password fields are still ignored
- [ ] No new external dependencies added

## Checklist

- [ ] Builds cleanly with `./gradlew assembleDebug`
- [ ] No hardcoded API keys or secrets
- [ ] Follows existing code style (no new linters/formatters)
- [ ] Works on API 23+ (no higher-API-only calls without version checks)
