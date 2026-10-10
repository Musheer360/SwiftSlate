package com.musheer360.swiftslate.bridge;

/**
 * Wire format between the hooked keyboard process (xposed.MainHook / ImeSession) and the
 * SwiftSlate app process (service.HookBridgeService).
 *
 * The keyboard process is deliberately a thin I/O driver: it reads the text before the caret,
 * asks the app what to do, and applies the answer. API keys, prompts, provider selection, undo
 * state, usage stats and every localized string stay inside the SwiftSlate process.
 */
public final class BridgeProtocol {
    private BridgeProtocol() {}

    /** Bind action is "<applicationId>.BRIDGE" so the preview build stays side-by-side safe. */
    public static final String ACTION_SUFFIX = ".BRIDGE";

    /** LSPosed remote-preferences file holding the trigger snapshot (app writes, hook reads). */
    public static final String PREFS_SNAPSHOT = "swiftslate_snapshot";
    public static final String PREF_SNAPSHOT_JSON = "snapshot";

    // keyboard -> app
    public static final int MSG_HELLO = 1;    // heartbeat: "a hooked keyboard is alive"
    public static final int MSG_RUN = 2;      // a trigger was typed
    public static final int MSG_APPLIED = 3;  // result was written to the field (or not)
    public static final int MSG_CANCEL = 4;   // keyboard gave up on a request

    // app -> keyboard
    public static final int MSG_STARTED = 10; // first network attempt begins: show spinner
    public static final int MSG_RESULT = 11;  // plan to apply

    // request keys
    public static final String K_TOKEN = "token";
    public static final String K_ID = "id";
    public static final String K_PID = "pid";
    public static final String K_PKG = "pkg";
    public static final String K_TEXT = "text";          // all text before the caret (incl. trigger)
    public static final String K_FIELD_KEY = "field";    // identity of the edited field (for undo)
    public static final String K_CLIP = "clip";          // clipboard text, only for paste/replace
    public static final String K_OK = "ok";              // MSG_APPLIED

    // result keys (a "plan")
    public static final String K_WRITE = "write";        // new text for the whole field, if present
    public static final String K_RESTORE = "restore";    // text to put back if K_WRITE fails
    public static final String K_SET_CLIP = "setclip";   // clipboard to set after a successful write
    public static final String K_TOAST_OK = "toast_ok";  // shown after success (or when no write)
    public static final String K_TOAST_FAIL = "toast_fail";
    public static final String K_HAPTIC = "haptic";      // HAPTIC_* on success

    public static final int HAPTIC_NONE = 0;
    public static final int HAPTIC_CONFIRM = 1;
    public static final int HAPTIC_REJECT = 2;
}
