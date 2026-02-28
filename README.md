<div align="center">

<img src="playstore-icon.png" width="120" alt="TypeSlate Icon" />

# TypeSlate

**System-wide AI text assistant for Android — powered by Gemini**

Type a trigger like `?fix` at the end of any text, anywhere on your phone, and watch it get replaced with AI-enhanced content instantly.

[![Android](https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white)](#)
[![API 23+](https://img.shields.io/badge/API-23%2B-brightgreen)](#)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Latest Release](https://img.shields.io/github/v/release/Musheer360/TypeSlate)](https://github.com/Musheer360/TypeSlate/releases/latest)

[Download APK](https://github.com/Musheer360/TypeSlate/releases/latest) · [Report Bug](https://github.com/Musheer360/TypeSlate/issues) · [Request Feature](https://github.com/Musheer360/TypeSlate/issues)

</div>

---

## What is TypeSlate?

TypeSlate is an open-source AI writing assistant that works **everywhere on your Android device** — WhatsApp, Twitter/X, Messages, Gmail, Notes, or any app with a text field. It uses Android's Accessibility Service to monitor your typing, and when you end your text with a trigger command (like `?fix` or `?casual`), it replaces your text with AI-generated content right in place. No copy-pasting, no app switching.

---

## ✨ Features

### 🌐 Works Everywhere
TypeSlate integrates at the system level using Android's Accessibility Service. It works in **any app** — messaging apps, email, social media, notes, browsers, and more.

### 🤖 Powered by Gemini
Uses Google's Gemini API to process your text. Choose between `gemini-2.5-flash-lite` (fast, lightweight) and `gemini-3-flash-preview` (more capable) in the Settings screen.

### ⚡ Instant Inline Replacement
No need to switch apps. Type your text, add a trigger, and the response replaces your text directly in the same field. A spinner animation (`◐ ◓ ◑ ◒`) shows while the AI is generating a response.

### 🎨 Custom Commands
Create your own trigger → prompt pairs beyond the built-in ones. For example, define `?poem` to turn any text into a poem, or `?eli5` to simplify text for a five-year-old.

### 🔑 Multi-Key Support
Add multiple Gemini API keys for automatic round-robin rotation and built-in rate-limit handling. If one key hits a rate limit, TypeSlate seamlessly switches to the next available key.

### 🔒 Encrypted Key Storage
API keys are encrypted with **AES-256-GCM** using the Android Keystore before being saved to local storage. Your keys never leave your device unencrypted.

### 🌙 AMOLED Dark Theme
A pure black (`#000000`) Material 3 interface designed for OLED screens — saves battery and looks great.

### 🛡️ Privacy-First
- Text is **only read** when a trigger command is detected — TypeSlate ignores all other typing
- Data is sent **directly** to the Google Gemini API — no intermediary servers, no analytics, no telemetry
- Fully open-source — inspect every line of code

---

## 📖 How It Works

```
┌─────────────────────────────────────────────────────┐
│  You type:  "Hello wrld, how r u ?fix"              │
└──────────────────────┬──────────────────────────────┘
                       │
                       ▼
        ┌──────────────────────────┐
        │   Accessibility Service  │
        │   detects "?fix" trigger │
        └──────────┬───────────────┘
                   │
                   ▼
        ┌──────────────────────────┐
        │  Extracts text before    │
        │  trigger: "Hello wrld,   │
        │  how r u"                │
        └──────────┬───────────────┘
                   │
                   ▼
        ┌──────────────────────────┐
        │  Sends text + prompt to  │
        │  Gemini API with next    │
        │  available API key       │
        └──────────┬───────────────┘
                   │
                   ▼
        ┌──────────────────────────┐
        │  Replaces text in-place: │
        │  "Hello world, how are   │
        │  you?"                   │
        └──────────────────────────┘
```

**Under the hood:**

1. TypeSlate registers an Accessibility Service that listens for `TYPE_VIEW_TEXT_CHANGED` events
2. For performance, it first checks if the last character of the typed text matches any known trigger's last character (fast exit)
3. If a match is found, it searches for the longest matching trigger at the end of the text
4. The text before the trigger is extracted and sent to the Gemini API along with the command's prompt
5. While waiting for the API response, a spinner animation is shown inline in the text field
6. Once the response arrives, the original text is replaced with the AI-generated result
7. If the primary text replacement method (`ACTION_SET_TEXT`) fails, TypeSlate falls back to a clipboard-based approach

---

## 🧩 Built-in Commands

TypeSlate comes with **9 built-in commands** plus a dynamic translation command:

| Trigger | What it does | Example |
|---------|-------------|---------|
| `?fix` | Fixes grammar, spelling, and punctuation | `i dont no whats hapening ?fix` → `I don't know what's happening.` |
| `?improve` | Improves clarity and readability | `The thing is not working good ?improve` → `The feature isn't functioning properly.` |
| `?shorten` | Shortens text while keeping meaning | `I wanted to let you know that I will not be able to attend the meeting tomorrow ?shorten` → `I can't attend tomorrow's meeting.` |
| `?expand` | Expands text with more detail | `Meeting postponed ?expand` → `The meeting has been postponed to a later date. We will share the updated schedule soon.` |
| `?formal` | Rewrites in a formal, professional tone | `hey can u send me that file ?formal` → `Could you please share the file at your earliest convenience?` |
| `?casual` | Rewrites in a casual, friendly tone | `Please confirm your attendance at the event ?casual` → `Hey, you coming to the event? Let me know!` |
| `?emoji` | Adds relevant emojis to the text | `I love this new feature ?emoji` → `I love this new feature! 🎉❤️✨` |
| `?reply` | Generates a contextual reply | `Do you want to grab lunch tomorrow? ?reply` → `Sure, I'd love to! What time works for you?` |
| `?undo` | Restores the original text before the last replacement | After `i dont no ?fix` → `I don't know.`, type `I don't know.?undo` → `i dont no` |
| `?translate:XX` | Translates text to language code `XX` | `Hello, how are you? ?translate:es` → `Hola, ¿cómo estás?` |

> **Translation tip:** Use standard language codes — `es` (Spanish), `fr` (French), `de` (German), `ja` (Japanese), `ar` (Arabic), `hi` (Hindi), `zh` (Chinese), `ko` (Korean), `pt` (Portuguese), etc.

---

## 🚀 Getting Started

### Prerequisites

- An Android device running **Android 6.0 (API 23)** or higher
- A Gemini API key — get one for free at [aistudio.google.com](https://aistudio.google.com)

### Installation

1. **Download the APK** from the [latest release](https://github.com/Musheer360/TypeSlate/releases/latest)
2. **Install the APK** on your device (you may need to allow installation from unknown sources)
3. **Open TypeSlate**

### Setup

#### Step 1 — Add an API Key

Navigate to the **Keys** tab and enter your Gemini API key. TypeSlate validates the key before saving it. You can add multiple keys for automatic rotation.

#### Step 2 — Enable the Accessibility Service

Go to the **Dashboard** tab and tap the **"Enable"** button. This opens Android's Accessibility Settings. Find **"TypeSlate Assistant"** in the list and toggle it on. Grant the required permissions when prompted.

#### Step 3 — Start Typing!

Open any app with a text field and type your text followed by a trigger command:

```
Hello wrld, how r u ?fix
```

TypeSlate will show a brief spinner animation and then replace the text with the corrected version.

---

## 🎛️ App Screens

TypeSlate has **four screens** accessible via the bottom navigation bar:

### Dashboard
The home screen showing:
- **Service Status** — whether the Accessibility Service is active (green) or inactive (red), with an "Enable" button
- **API Keys** — count of configured keys, with a prompt to add one if none exist
- **How to Use** — quick-start guide

### Keys
Manage your Gemini API keys:
- Add new keys (validated before saving)
- Delete existing keys
- Keys are encrypted with AES-256-GCM before storage

### Commands
View and manage text commands:
- Browse all **9 built-in commands** (read-only, marked with a "Built-in" label)
- **Add custom commands** with your own trigger and prompt
- Delete custom commands

### Settings
Configure the AI model:
- **Model Selection** — choose between `gemini-2.5-flash-lite` (default, faster) and `gemini-3-flash-preview` (more capable)

---

## ✏️ Custom Commands

Beyond the built-in triggers, you can create your own in the **Commands** tab.

### Creating a Custom Command

1. Go to the **Commands** screen
2. Enter a **Trigger** (e.g., `?poem`)
3. Enter a **Prompt** — this is the instruction sent to the AI (e.g., `Rewrite the text as a short poem and return ONLY the modified text.`)
4. Tap **"Add Command"**

### Tips for Good Custom Commands

- **Always** include "return ONLY the modified text" in your prompt to ensure clean output
- Triggers must start with `?` by convention
- Use descriptive trigger names you'll remember
- The prompt should clearly describe the transformation you want

### Example Custom Commands

| Trigger | Prompt |
|---------|--------|
| `?eli5` | `Explain this like I'm five years old and return ONLY the modified text.` |
| `?bullet` | `Convert this text into bullet points and return ONLY the modified text.` |
| `?headline` | `Rewrite this as a catchy headline and return ONLY the modified text.` |
| `?code` | `Convert this description into pseudocode and return ONLY the modified text.` |
| `?tldr` | `Summarize this text in one sentence and return ONLY the modified text.` |

---

## 🔑 API Key Management

TypeSlate supports multiple Gemini API keys with intelligent rotation:

- **Round-robin rotation** — keys are used in turn to spread usage evenly
- **Rate-limit handling** — if a key is rate-limited (HTTP 429), TypeSlate automatically tracks the cooldown period and skips it until it's available again
- **Invalid key detection** — keys that return a 403 error are marked as invalid and excluded from rotation
- **Encrypted storage** — all keys are encrypted with AES-256-GCM via Android Keystore before being saved

> **Tip:** Adding 2–3 API keys helps avoid rate limits during heavy use.

---

## 🏗️ Tech Stack

| Component | Technology |
|-----------|-----------|
| **Language** | Kotlin 2.1 |
| **UI Framework** | Jetpack Compose with Material 3 |
| **Navigation** | Navigation Compose |
| **Async** | Kotlin Coroutines |
| **HTTP Client** | `HttpURLConnection` (no external dependency) |
| **JSON Parsing** | `org.json` (Android built-in) |
| **Storage** | SharedPreferences (encrypted via Android Keystore) |
| **Core Service** | Android Accessibility Service |
| **Build System** | Gradle (Kotlin DSL) |
| **Java Target** | JDK 17 |
| **Min SDK** | API 23 (Android 6.0) |
| **Target SDK** | API 36 |

---

## 🔒 Privacy & Security

TypeSlate is built with privacy as a core principle:

| Concern | How TypeSlate handles it |
|---------|------------------------|
| **Text monitoring** | Only processes text when a trigger command (e.g., `?fix`) is detected at the end. All other typing is ignored. |
| **Data transmission** | Text is sent **only** to the Google Gemini API (`generativelanguage.googleapis.com`). No other servers are contacted. |
| **API key storage** | Keys are encrypted with AES-256-GCM using the Android Keystore system before being saved locally. |
| **Analytics** | None. Zero telemetry, no tracking, no crash reporting. |
| **Open source** | The entire codebase is open for inspection under the MIT License. |
| **Permissions** | Only requires the Accessibility Service permission — no internet permission beyond what's needed for Gemini API calls. |

---

## 🛠️ Building from Source

### Prerequisites

- **Android Studio** (latest stable version recommended)
- **JDK 17**
- **Android SDK** with API level 36

### Steps

```bash
# Clone the repository
git clone https://github.com/Musheer360/TypeSlate.git
cd TypeSlate

# Build debug APK
./gradlew assembleDebug

# The APK will be at:
# app/build/outputs/apk/debug/app-debug.apk
```

For a signed release build, set the following environment variables:

```bash
export KEYSTORE_FILE=/path/to/your/keystore.jks
export KEYSTORE_PASSWORD=your_keystore_password
export KEY_ALIAS=your_key_alias
export KEY_PASSWORD=your_key_password

./gradlew assembleRelease
```

---

## 🤝 Contributing

Contributions are welcome! Here's how you can help:

1. **Fork** the repository
2. **Create** a feature branch (`git checkout -b feature/amazing-feature`)
3. **Commit** your changes (`git commit -m 'Add amazing feature'`)
4. **Push** to the branch (`git push origin feature/amazing-feature`)
5. **Open** a Pull Request

### Ideas for Contributions

- New built-in commands
- Additional Gemini model support
- UI improvements and new themes
- Localization / translations
- Documentation improvements

---

## 📄 License

This project is licensed under the **MIT License** — see the [LICENSE](LICENSE) file for details.

---

<div align="center">

Made with ❤️ by [Musheer Alam](https://github.com/Musheer360)

If you find TypeSlate useful, consider giving it a ⭐

</div>
