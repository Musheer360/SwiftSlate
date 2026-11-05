package com.musheer360.swiftslate.xposed;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.text.InputType;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.Toast;

import com.musheer360.swiftslate.BuildConfig;
import com.musheer360.swiftslate.bridge.BridgeProtocol;

/**
 * One per live InputMethodService inside a hooked keyboard process. Replaces the old
 * accessibility service: it notices a completed trigger through onUpdateSelection, reads and
 * writes the field through the keyboard's own InputConnection, and delegates every decision to
 * the SwiftSlate app through {@link BridgeClient}.
 *
 * All methods run on the main thread (hook callbacks and Messenger replies both do).
 */
final class ImeSession implements BridgeClient.Listener {
    private static final String TAG = "SwiftSlateHook";
    private static final int MAX_CHARS = 20_000;
    private static final long ECHO_SUPPRESS_MS = 1_000L;
    private static final long SPINNER_FRAME_MS = 250L;
    private static final long WATCHDOG_MS = 100_000L;
    private static final String[] SPINNER = {"\u25D0", "\u25D3", "\u25D1", "\u25D2"};

    private final InputMethodService ims;
    private final SharedPreferences snapshotPrefs;
    private final BridgeClient bridge;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private String snapshotRaw;
    private TriggerSnapshot snapshot;
    private long nextId = 1;

    // ---- in-flight request state ----
    private boolean busy;
    private long currentId;
    private String clean;            // text without the trigger, trimmed (what a spinner shows)
    private String expectedCurrent;  // what we believe the field currently holds before the caret
    private boolean spinnerOn;
    private int spinnerFrame;
    private Runnable spinnerTick;
    private Runnable watchdog;

    // ---- echo suppression for our own writes ----
    private String lastWritten;
    private long lastWrittenAt;

    ImeSession(InputMethodService ims, SharedPreferences snapshotPrefs) {
        this.ims = ims;
        this.snapshotPrefs = snapshotPrefs;
        this.bridge = new BridgeClient(ims, this);
    }

    /** Proof of life for the app's Dashboard: only runs when LSPosed really loaded the hook. */
    void sayHello() {
        Bundle b = new Bundle();
        TriggerSnapshot snap = currentSnapshot();
        if (snap == null) return; // no token yet: the app has never published a snapshot
        b.putString(BridgeProtocol.K_TOKEN, snap.token);
        b.putString(BridgeProtocol.K_PKG, ims.getPackageName());
        bridge.send(BridgeProtocol.MSG_HELLO, b);
    }

    void destroy() {
        stopSpinner();
        cancelWatchdog();
        bridge.destroy();
        busy = false;
    }

    // =========================================================================================
    // Trigger detection
    // =========================================================================================

    void onSelectionUpdate(int newSelStart, int newSelEnd) {
        if (busy || newSelStart != newSelEnd) return;
        try {
            detect();
        } catch (Throwable t) {
            Log.w(TAG, "selection handling failed", t);
        }
    }

    private void detect() {
        EditorInfo ei = ims.getCurrentInputEditorInfo();
        InputConnection ic = ims.getCurrentInputConnection();
        if (ei == null || ic == null) return;
        if (BuildConfig.APPLICATION_ID.equals(ei.packageName)) return;
        if (isPasswordField(ei.inputType)) return;

        TriggerSnapshot snap = currentSnapshot();
        if (snap == null) return;

        CharSequence before = ic.getTextBeforeCursor(MAX_CHARS, 0);
        if (before == null || before.length() == 0) return;
        String text = before.toString();

        if (lastWritten != null && text.equals(lastWritten)
                && SystemClock.uptimeMillis() - lastWrittenAt < ECHO_SUPPRESS_MS) {
            return;
        }

        TriggerSnapshot.Match match = snap.match(text);
        if (match == null) return;

        // The original service only fired when the whole field ended with the trigger.
        CharSequence after = ic.getTextAfterCursor(1, 0);
        if (after != null && after.length() > 0) return;

        start(ei, snap, text, match);
    }

    private void start(EditorInfo ei, TriggerSnapshot snap, String text, TriggerSnapshot.Match match) {
        String preceding = text.substring(0, text.length() - match.trigger.length());
        busy = true;
        currentId = nextId++;
        clean = preceding.trim();
        expectedCurrent = text;
        spinnerOn = false;

        Bundle b = new Bundle();
        b.putString(BridgeProtocol.K_TOKEN, snap.token);
        b.putLong(BridgeProtocol.K_ID, currentId);
        b.putInt(BridgeProtocol.K_PID, Process.myPid());
        b.putString(BridgeProtocol.K_TEXT, text);
        b.putString(BridgeProtocol.K_FIELD_KEY, ei.packageName + ":" + ei.fieldId + ":" + ei.fieldName);
        if (match.needsClip) {
            String clip = readClipboard();
            if (clip != null) b.putString(BridgeProtocol.K_CLIP, clip);
        }

        bridge.setHoldOpen(true);
        armWatchdog();
        bridge.send(BridgeProtocol.MSG_RUN, b);
    }

    // =========================================================================================
    // Bridge callbacks
    // =========================================================================================

    @Override public void onStarted(long id) {
        if (!busy || id != currentId) return;
        InputConnection ic = ims.getCurrentInputConnection();
        if (ic == null) return;
        String spin = clean + " " + SPINNER[0];
        if (replaceBeforeCursor(ic, expectedCurrent, spin)) {
            expectedCurrent = spin;
            spinnerOn = true;
            spinnerFrame = 0;
            scheduleSpinner();
        }
    }

    @Override public void onResult(Bundle r) {
        if (!busy || r.getLong(BridgeProtocol.K_ID) != currentId) return;
        stopSpinner();
        cancelWatchdog();

        InputConnection ic = ims.getCurrentInputConnection();
        String write = r.getString(BridgeProtocol.K_WRITE);
        String restore = r.getString(BridgeProtocol.K_RESTORE);
        boolean wrote = true;

        if (write != null) {
            wrote = ic != null && replaceBeforeCursor(ic, expectedCurrent, write);
            if (wrote) {
                markWritten(write);
            } else if (restore != null && ic != null && replaceBeforeCursor(ic, expectedCurrent, restore)) {
                markWritten(restore);
            }
        }

        if (wrote) {
            String clip = r.getString(BridgeProtocol.K_SET_CLIP);
            if (clip != null) writeClipboard(clip);
            toast(r.getString(BridgeProtocol.K_TOAST_OK));
            haptic(r.getInt(BridgeProtocol.K_HAPTIC, BridgeProtocol.HAPTIC_NONE));
        } else {
            toast(r.getString(BridgeProtocol.K_TOAST_FAIL));
            haptic(BridgeProtocol.HAPTIC_REJECT);
        }

        Bundle ack = new Bundle();
        ack.putString(BridgeProtocol.K_TOKEN, snapshot != null ? snapshot.token : "");
        ack.putLong(BridgeProtocol.K_ID, currentId);
        ack.putInt(BridgeProtocol.K_PID, Process.myPid());
        ack.putBoolean(BridgeProtocol.K_OK, wrote);
        bridge.send(BridgeProtocol.MSG_APPLIED, ack);
        finish();
    }

    @Override public void onLost() {
        if (!busy) return;
        stopSpinner();
        cancelWatchdog();
        InputConnection ic = ims.getCurrentInputConnection();
        if (spinnerOn && ic != null && replaceBeforeCursor(ic, expectedCurrent, clean)) {
            markWritten(clean);
        }
        // The app process (and its localized strings) is unreachable, so this one message is
        // the only English-only text in the hook.
        toast("SwiftSlate: could not reach the app. Open SwiftSlate once, then try again.");
        haptic(BridgeProtocol.HAPTIC_REJECT);
        finish();
    }

    private void finish() {
        busy = false;
        spinnerOn = false;
        bridge.setHoldOpen(false);
    }

    // =========================================================================================
    // Field I/O
    // =========================================================================================

    /**
     * Replaces the text before the caret, provided it still ends with {@code expected}. Refusing
     * to touch a field the user kept typing in is what stops a slow AI result from clobbering
     * newer input.
     */
    private boolean replaceBeforeCursor(InputConnection ic, String expected, String replacement) {
        try {
            ic.finishComposingText();
            CharSequence cur = ic.getTextBeforeCursor(expected.length() + 1, 0);
            if (cur == null || !cur.toString().endsWith(expected)) return false;
            ic.beginBatchEdit();
            try {
                boolean deleted = ic.deleteSurroundingText(expected.length(), 0);
                boolean committed = replacement.isEmpty() || ic.commitText(replacement, 1);
                if (deleted && !committed) {
                    ic.commitText(expected, 1); // never leave the user's text deleted
                    return false;
                }
                if (!deleted) return false;
            } finally {
                ic.endBatchEdit();
            }
            expectedCurrent = replacement;
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "replaceBeforeCursor failed", t);
            return false;
        }
    }

    private void markWritten(String text) {
        lastWritten = text;
        lastWrittenAt = SystemClock.uptimeMillis();
    }

    private void scheduleSpinner() {
        stopSpinnerCallbacks();
        spinnerTick = new Runnable() {
            @Override public void run() {
                if (!busy || !spinnerOn) return;
                InputConnection ic = ims.getCurrentInputConnection();
                String exp = expectedCurrent;
                if (ic == null || exp == null || exp.length() < 2) { spinnerOn = false; return; }
                try {
                    CharSequence tail = ic.getTextBeforeCursor(2, 0);
                    if (tail == null || !tail.toString().equals(exp.substring(exp.length() - 2))) {
                        spinnerOn = false; // the user edited the field: stop touching it
                        return;
                    }
                    spinnerFrame = (spinnerFrame + 1) % SPINNER.length;
                    ic.beginBatchEdit();
                    ic.deleteSurroundingText(1, 0);
                    ic.commitText(SPINNER[spinnerFrame], 1);
                    ic.endBatchEdit();
                    expectedCurrent = exp.substring(0, exp.length() - 1) + SPINNER[spinnerFrame];
                } catch (Throwable t) {
                    spinnerOn = false;
                    return;
                }
                handler.postDelayed(this, SPINNER_FRAME_MS);
            }
        };
        handler.postDelayed(spinnerTick, SPINNER_FRAME_MS);
    }

    private void stopSpinnerCallbacks() {
        if (spinnerTick != null) handler.removeCallbacks(spinnerTick);
        spinnerTick = null;
    }

    /** Stops animating; the caller decides what the field should hold afterwards. */
    private void stopSpinner() {
        stopSpinnerCallbacks();
    }

    private void armWatchdog() {
        cancelWatchdog();
        watchdog = () -> {
            if (!busy) return;
            Bundle cancel = new Bundle();
            cancel.putString(BridgeProtocol.K_TOKEN, snapshot != null ? snapshot.token : "");
            cancel.putLong(BridgeProtocol.K_ID, currentId);
            cancel.putInt(BridgeProtocol.K_PID, Process.myPid());
            bridge.send(BridgeProtocol.MSG_CANCEL, cancel);
            onLost();
        };
        handler.postDelayed(watchdog, WATCHDOG_MS);
    }

    private void cancelWatchdog() {
        if (watchdog != null) handler.removeCallbacks(watchdog);
        watchdog = null;
    }

    // =========================================================================================
    // Helpers
    // =========================================================================================

    private TriggerSnapshot currentSnapshot() {
        if (snapshotPrefs == null) return null;
        String raw;
        try {
            raw = snapshotPrefs.getString(BridgeProtocol.PREF_SNAPSHOT_JSON, null);
        } catch (Throwable t) {
            return snapshot;
        }
        if (raw == null) return null;
        if (!raw.equals(snapshotRaw)) {
            snapshotRaw = raw;
            snapshot = TriggerSnapshot.parse(raw);
        }
        return snapshot;
    }

    private static boolean isPasswordField(int inputType) {
        int cls = inputType & InputType.TYPE_MASK_CLASS;
        int variation = inputType & InputType.TYPE_MASK_VARIATION;
        if (cls == InputType.TYPE_CLASS_TEXT) {
            return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                    || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD;
        }
        if (cls == InputType.TYPE_CLASS_NUMBER) {
            return variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
        }
        return false;
    }

    private String readClipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) ims.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData cd = cm.getPrimaryClip();
            if (cd == null || cd.getItemCount() == 0) return null;
            CharSequence t = cd.getItemAt(0).coerceToText(ims);
            return t == null ? null : t.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private void writeClipboard(String text) {
        try {
            ClipboardManager cm = (ClipboardManager) ims.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("SwiftSlate", text));
        } catch (Throwable t) {
            Log.w(TAG, "clipboard write failed", t);
        }
    }

    private void toast(String msg) {
        if (msg == null || msg.isEmpty()) return;
        try {
            Toast.makeText(ims.getApplicationContext(), msg, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {}
    }

    private void haptic(int kind) {
        if (kind == BridgeProtocol.HAPTIC_NONE) return;
        try {
            View v = ims.getWindow() != null && ims.getWindow().getWindow() != null
                    ? ims.getWindow().getWindow().getDecorView() : null;
            if (v == null) return;
            int type;
            if (Build.VERSION.SDK_INT >= 30) {
                type = kind == BridgeProtocol.HAPTIC_CONFIRM
                        ? HapticFeedbackConstants.CONFIRM : HapticFeedbackConstants.REJECT;
            } else {
                type = kind == BridgeProtocol.HAPTIC_CONFIRM
                        ? HapticFeedbackConstants.KEYBOARD_TAP : HapticFeedbackConstants.LONG_PRESS;
            }
            v.performHapticFeedback(type);
        } catch (Throwable ignored) {}
    }
}
