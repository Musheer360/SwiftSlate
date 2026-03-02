<div align="center">

<br>

<img src="playstore-icon.png" width="140" alt="SwiftSlate Icon" />

<br>

# SwiftSlate

### System-wide AI text assistant for Android — powered by Gemini and custom providers

Type a trigger like **`?fix`** at the end of any text, in any app, and watch it get replaced with AI-enhanced content — instantly.

<br>

[![Android](https://img.shields.io/badge/Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)](#-getting-started)
[![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](#%EF%B8%8F-tech-stack)
[![Gemini](https://img.shields.io/badge/Gemini_AI-8E75B2?style=for-the-badge&logo=googlegemini&logoColor=white)](#-powered-by-gemini--custom-providers)
[![License: MIT](https://img.shields.io/badge/MIT-blue?style=for-the-badge&logo=opensourceinitiative&logoColor=white)](LICENSE)

[![Latest Release](https://img.shields.io/github/v/release/Musheer360/SwiftSlate?style=flat-square&label=Latest&color=brightgreen)](https://github.com/Musheer360/SwiftSlate/releases/latest)
[![APK Size](https://img.shields.io/badge/APK_Size-~1.3_MB-blue?style=flat-square)](#)
[![API 23+](https://img.shields.io/badge/Min_SDK-API_23-orange?style=flat-square)](#)
[![GitHub Stars](https://img.shields.io/github/stars/Musheer360/SwiftSlate?style=flat-square&color=yellow)](https://github.com/Musheer360/SwiftSlate/stargazers)

<br>

[<img src="https://img.shields.io/badge/⬇_Download_APK-282828?style=for-the-badge" alt="Download APK" height="36">](https://github.com/Musheer360/SwiftSlate/releases/latest)
&nbsp;&nbsp;
[<img src="https://img.shields.io/badge/🐛_Report_Bug-282828?style=for-the-badge" alt="Report Bug" height="36">](https://github.com/Musheer360/SwiftSlate/issues)
&nbsp;&nbsp;
[<img src="https://img.shields.io/badge/💡_Request_Feature-282828?style=for-the-badge" alt="Request Feature" height="36">](https://github.com/Musheer360/SwiftSlate/issues)

<br>

</div>

> [!NOTE]
> **SwiftSlate works everywhere** — WhatsApp, Gmail, Twitter/X, Messages, Notes, Chrome, and every other app with a text field. No copy-pasting. No app switching. Just type and go.

<br>

## 📋 Table of Contents

- [Quick Demo](#-quick-demo)
- [Features](#-features)
- [Built-in Commands](#-built-in-commands)
- [Getting Started](#-getting-started)
- [How It Works](#%EF%B8%8F-how-it-works)
- [Custom Commands](#-custom-commands)
- [API Key Management](#-api-key-management)
- [App Screens](#-app-screens)
- [Privacy & Security](#-privacy--security)
- [Tech Stack](#%EF%B8%8F-tech-stack)
- [Building from Source](#-building-from-source)
- [Contributing](#-contributing)
- [License](#-license)

<br>

## ⚡ Quick Demo

```
📝  You type       →  "i dont no whats hapening ?fix"
⏳  SwiftSlate      →  ◐ ◓ ◑ ◒  (processing...)
✅  Result         →  "I don't know what's happening."
```

```
📝  You type       →  "hey can u send me that file ?formal"
⏳  SwiftSlate      →  ◐ ◓ ◑ ◒  (processing...)
✅  Result         →  "Could you please share the file at your earliest convenience?"
```

```
📝  You type       →  "Hello, how are you? ?translate:es"
⏳  SwiftSlate      →  ◐ ◓ ◑ ◒  (processing...)
✅  Result         →  "Hola, ¿cómo estás?"
```

<br>

## ✨ Features

<table>
<tr>
<td width="50%">

### 🌐 Works Everywhere
Integrates at the system level via Android's Accessibility Service. Works in **any app** — messaging, email, social media, notes, browsers, and more.

### ⚡ Instant Inline Replacement
Type, trigger, done. The AI response replaces your text directly in the same field — no copy-pasting, no app switching. A spinner (`◐ ◓ ◑ ◒`) shows progress.

### 🔑 Multi-Key Rotation
Add multiple API keys for automatic round-robin rotation. If one key hits a rate limit, SwiftSlate seamlessly switches to the next.

### 🌙 AMOLED Dark Theme
Pure black (`#000000`) Material 3 interface designed for OLED screens — saves battery and looks stunning.

</td>
<td width="50%">

### 🤖 Powered by Gemini & Custom Providers
Ships with Google's Gemini API (`gemini-2.5-flash-lite` or `gemini-3-flash-preview`). Or connect **any OpenAI-compatible endpoint** — use your own provider, model, and base URL.

### 🎨 Custom Commands
Create your own trigger → prompt pairs. Define `?poem` to turn text into poetry, `?eli5` to simplify for a five-year-old, or anything you can imagine.

### 🔒 Encrypted Key Storage
API keys are encrypted with **AES-256-GCM** using the Android Keystore. Your keys never leave your device unencrypted.

### 🛡️ Privacy-First
No analytics. No telemetry. No intermediary servers. Text is sent directly to the configured provider's API and only when a trigger is detected.

</td>
</tr>
</table>

<br>

## 🧩 Built-in Commands

SwiftSlate ships with **9 commands** plus dynamic translation — ready to use out of the box:

| Trigger | Action | Example |
|:--------|:-------|:--------|
| **`?fix`** | Fix grammar, spelling & punctuation | `i dont no whats hapening` → `I don't know what's happening.` |
| **`?improve`** | Improve clarity and readability | `The thing is not working good` → `The feature isn't functioning properly.` |
| **`?shorten`** | Shorten while keeping meaning | `I wanted to let you know that I will not be able to attend the meeting tomorrow` → `I can't attend tomorrow's meeting.` |
| **`?expand`** | Expand with more detail | `Meeting postponed` → `The meeting has been postponed to a later date. We will share the updated schedule soon.` |
| **`?formal`** | Rewrite in professional tone | `hey can u send me that file` → `Could you please share the file at your earliest convenience?` |
| **`?casual`** | Rewrite in friendly tone | `Please confirm your attendance at the event` → `Hey, you coming to the event? Let me know!` |
| **`?emoji`** | Add relevant emojis | `I love this new feature` → `I love this new feature! 🎉❤️✨` |
| **`?reply`** | Generate a contextual reply | `Do you want to grab lunch tomorrow?` → `Sure, I'd love to! What time works for you?` |
| **`?undo`** | Restore text from before the last replacement | Reverts to your original text before AI modified it |
| **`?translate:XX`** | Translate to any language | `Hello, how are you?` **`?translate:es`** → `Hola, ¿cómo estás?` |

<details>
<summary>🌍 <strong>Supported language codes for translation</strong></summary>

<br>

Use any standard language code with `?translate:XX`:

| Code | Language | Code | Language | Code | Language |
|:-----|:---------|:-----|:---------|:-----|:---------|
| `es` | Spanish | `fr` | French | `de` | German |
| `ja` | Japanese | `ko` | Korean | `zh` | Chinese |
| `hi` | Hindi | `ar` | Arabic | `pt` | Portuguese |
| `it` | Italian | `ru` | Russian | `nl` | Dutch |
| `tr` | Turkish | `pl` | Polish | `sv` | Swedish |

…and many more. Any language code recognized by Google Translate works.

</details>

<br>

## 🚀 Getting Started

### Prerequisites

| Requirement | Details |
|:------------|:--------|
| **Android Device** | Android 6.0+ (API 23 or higher) |
| **API Key** | Free Gemini key at [aistudio.google.com](https://aistudio.google.com), or a key from any OpenAI-compatible provider |

### Installation

> [!TIP]
> The APK is only ~1.3 MB — lightweight with zero external dependencies for networking or JSON.

**1.** Download the latest APK from the [**Releases**](https://github.com/Musheer360/SwiftSlate/releases/latest) page

**2.** Install the APK on your device (allow installation from unknown sources if prompted)

**3.** Open SwiftSlate and follow the setup below

### Setup in 3 Steps

<table>
<tr>
<td width="33%" align="center">

**Step 1**

🔑 **Add API Key**

Open the **Keys** tab, enter your API key. It's validated before saving. Add multiple keys for rotation.

</td>
<td width="33%" align="center">

**Step 2**

♿ **Enable Service**

On the **Dashboard**, tap **"Enable"** → find **"SwiftSlate Assistant"** in Accessibility Settings → toggle it on.

</td>
<td width="33%" align="center">

**Step 3**

✍️ **Start Typing!**

Open any app, type your text, add a trigger like `?fix` at the end, and watch the magic happen.

</td>
</tr>
</table>

<br>

## ⚙️ How It Works

```mermaid
flowchart TD
    A["📝 You type: 'Hello wrld, how r u ?fix'"] --> B{"🔍 Accessibility Service\ndetects trigger"}
    B --> C["✂️ Extracts text before trigger:\n'Hello wrld, how r u'"]
    C --> D["🔑 Selects next available\nAPI key (round-robin)"]
    D --> E["🤖 Sends text + prompt\nto AI provider"]
    E --> F["⏳ Shows inline spinner\n◐ ◓ ◑ ◒"]
    F --> G["✅ Replaces text in-place:\n'Hello world, how are you?'"]

    style A fill:#1a1a2e,stroke:#e94560,color:#fff
    style B fill:#1a1a2e,stroke:#0f3460,color:#fff
    style C fill:#1a1a2e,stroke:#0f3460,color:#fff
    style D fill:#1a1a2e,stroke:#0f3460,color:#fff
    style E fill:#1a1a2e,stroke:#0f3460,color:#fff
    style F fill:#1a1a2e,stroke:#e94560,color:#fff
    style G fill:#16213e,stroke:#00b894,color:#fff
```

<details>
<summary>🔧 <strong>Technical deep-dive</strong></summary>

<br>

1. **Event Listening** — SwiftSlate registers an Accessibility Service that listens for `TYPE_VIEW_TEXT_CHANGED` events across all apps
2. **Fast Exit Optimization** — For performance, it first checks if the last character of typed text matches any known trigger's last character before doing a full scan
3. **Longest Match** — When a potential match is found, it searches for the longest matching trigger at the end of the text
4. **Text Extraction** — The text before the trigger is extracted and paired with the command's prompt
5. **API Call** — The text + prompt is sent to the configured AI provider using the next available key in the round-robin rotation
6. **Inline Spinner** — While waiting for the response, a spinner animation (`◐ ◓ ◑ ◒`) replaces the text to provide visual feedback
7. **Text Replacement** — The AI response replaces the original text using `ACTION_SET_TEXT`
8. **Fallback Strategy** — If `ACTION_SET_TEXT` fails (some apps don't support it), SwiftSlate falls back to a clipboard-based paste approach

</details>

<br>

## 🎨 Custom Commands

Go beyond the built-ins — create your own trigger → prompt pairs in the **Commands** tab.

### How to Create One

1. Open the **Commands** screen
2. Enter a **Trigger** (e.g., `?poem`)
3. Enter a **Prompt** — the instruction sent to the AI
4. Tap **"Add Command"**

### Example Ideas

| Trigger | Prompt | Use Case |
|:--------|:-------|:---------|
| `?eli5` | `Explain this like I'm five years old and return ONLY the modified text.` | Simplify complex topics |
| `?bullet` | `Convert this text into bullet points and return ONLY the modified text.` | Quick formatting |
| `?headline` | `Rewrite this as a catchy headline and return ONLY the modified text.` | Social media posts |
| `?code` | `Convert this description into pseudocode and return ONLY the modified text.` | Developer shorthand |
| `?tldr` | `Summarize this text in one sentence and return ONLY the modified text.` | Quick summaries |

> [!IMPORTANT]
> Always include **"return ONLY the modified text"** in your prompt to ensure clean output without extra commentary from the AI.

<br>

## 🔑 API Key Management

SwiftSlate supports multiple API keys with intelligent rotation:

| Feature | Details |
|:--------|:--------|
| **Round-Robin Rotation** | Keys are used in turn to spread usage evenly across all configured keys |
| **Rate-Limit Handling** | If a key gets rate-limited (HTTP 429), SwiftSlate tracks the cooldown and skips it automatically |
| **Invalid Key Detection** | Keys returning 403 errors are marked invalid and excluded from rotation |
| **Encrypted Storage** | All keys encrypted with AES-256-GCM via Android Keystore before being saved locally |

> [!TIP]
> Adding **2–3 API keys** helps avoid rate limits during heavy use. Each API key has its own quota.

<br>

## 🖥️ App Screens

SwiftSlate has **four screens** accessible via the bottom navigation bar:

<table>
<tr>
<td width="25%" valign="top">

#### 📊 Dashboard
- Service status indicator (green/red)
- Enable/disable toggle
- API key count
- Quick-start guide

</td>
<td width="25%" valign="top">

#### 🔑 Keys
- Add new keys (validated live)
- Delete existing keys
- AES-256-GCM encryption
- Multi-key management

</td>
<td width="25%" valign="top">

#### 📝 Commands
- 9 built-in commands (read-only)
- Add custom commands
- Delete custom commands
- Trigger + prompt pairs

</td>
<td width="25%" valign="top">

#### ⚙️ Settings
- Provider selection
  - Google Gemini (default)
  - Custom (OpenAI Compatible)
- Gemini model picker
  - `gemini-2.5-flash-lite` (default)
  - `gemini-3-flash-preview`
- Custom endpoint URL & model name

</td>
</tr>
</table>

<br>

## 🔒 Privacy & Security

> [!NOTE]
> SwiftSlate is built with privacy as a **core architectural principle**, not an afterthought.

| | Concern | How SwiftSlate Handles It |
|:--|:--------|:------------------------|
| 👁️ | **Text Monitoring** | Only processes text when a trigger command is detected at the end. All other typing is completely ignored. |
| 📡 | **Data Transmission** | Text is sent **only** to the configured AI provider (Google Gemini or your custom endpoint). No other servers are ever contacted. |
| 🔐 | **Key Storage** | API keys are encrypted with AES-256-GCM using the Android Keystore system before being saved locally. |
| 📊 | **Analytics** | **None.** Zero telemetry, zero tracking, zero crash reporting. |
| 📖 | **Open Source** | The entire codebase is open for inspection under the MIT License. |
| 🔑 | **Permissions** | Only requires the Accessibility Service permission — nothing else. |

<br>

## 🏗️ Tech Stack

<table>
<tr><td><strong>Language</strong></td><td>Kotlin 2.1</td></tr>
<tr><td><strong>UI</strong></td><td>Jetpack Compose · Material 3</td></tr>
<tr><td><strong>Navigation</strong></td><td>Navigation Compose</td></tr>
<tr><td><strong>Async</strong></td><td>Kotlin Coroutines</td></tr>
<tr><td><strong>HTTP</strong></td><td><code>HttpURLConnection</code> (zero external dependencies)</td></tr>
<tr><td><strong>JSON</strong></td><td><code>org.json</code> (Android built-in)</td></tr>
<tr><td><strong>Storage</strong></td><td>SharedPreferences (encrypted via Android Keystore)</td></tr>
<tr><td><strong>Core Service</strong></td><td>Android Accessibility Service</td></tr>
<tr><td><strong>Build System</strong></td><td>Gradle with Kotlin DSL</td></tr>
<tr><td><strong>Java Target</strong></td><td>JDK 17</td></tr>
<tr><td><strong>Min SDK</strong></td><td>API 23 (Android 6.0)</td></tr>
<tr><td><strong>Target SDK</strong></td><td>API 36</td></tr>
</table>

> **Zero third-party dependencies** for networking or JSON parsing — SwiftSlate uses only Android's built-in APIs.

<br>

## 🔨 Building from Source

### Prerequisites

- [**Android Studio**](https://developer.android.com/studio) (latest stable)
- **JDK 17**
- **Android SDK** with API level 36

### Build

```bash
# Clone the repository
git clone https://github.com/Musheer360/SwiftSlate.git
cd SwiftSlate

# Build debug APK
./gradlew assembleDebug

# Output: app/build/outputs/apk/debug/app-debug.apk
```

<details>
<summary>📦 <strong>Signed release build</strong></summary>

<br>

```bash
export KEYSTORE_FILE=/path/to/your/keystore.jks
export KEYSTORE_PASSWORD=your_keystore_password
export KEY_ALIAS=your_key_alias
export KEY_PASSWORD=your_key_password

./gradlew assembleRelease
```

</details>

<br>

## 🤝 Contributing

Contributions are welcome! Here's how to get involved:

```bash
# 1. Fork the repository, then:
git clone https://github.com/YOUR_USERNAME/SwiftSlate.git
cd SwiftSlate

# 2. Create a feature branch
git checkout -b feature/amazing-feature

# 3. Make your changes and commit
git commit -m "Add amazing feature"

# 4. Push and open a Pull Request
git push origin feature/amazing-feature
```

### Ideas for Contributions

- 🧩 New built-in commands
- 🤖 Additional AI provider integrations
- 🎨 UI improvements and new themes
- 🌍 Localization / translations
- 📖 Documentation improvements

<br>

## 📄 License

This project is licensed under the **MIT License** — see the [LICENSE](LICENSE) file for details.

<br>

---

<div align="center">

<br>

Made with ❤️ by [**Musheer Alam**](https://github.com/Musheer360)

If SwiftSlate makes your typing life easier, consider giving it a ⭐

<br>

</div>
