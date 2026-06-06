# Security Policy

## Reporting a Vulnerability

If you discover a security vulnerability in SwiftSlate, please report it privately:

**Email:** me@musheer360.com

**Subject line:** `[SwiftSlate Security] Brief description`

Please include:
- Description of the vulnerability
- Steps to reproduce
- Affected component (e.g., `KeyManager`, `AssistantService`, API clients)
- Potential impact

I'll acknowledge your report within 48 hours and provide a fix timeline.

## Scope

Security issues I'm particularly interested in:

| Area | Examples |
|:-----|:--------|
| **Key storage** | Bypassing AES-256-GCM encryption, extracting keys from SharedPreferences |
| **Accessibility service** | Unintended text capture outside trigger detection, password field leakage |
| **API communication** | Man-in-the-middle risks, credential exposure in logs or error messages |
| **Data handling** | Text persisted when it shouldn't be, backup/export including sensitive data |

## Out of Scope

- Vulnerabilities in upstream AI providers (Gemini, Groq, etc.)
- Issues requiring physical device access with USB debugging enabled
- Social engineering attacks
- Denial of service against the app itself

## Disclosure

Please do **not** open a public GitHub issue for security vulnerabilities. I'll coordinate disclosure with you once a fix is available.
