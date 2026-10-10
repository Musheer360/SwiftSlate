package com.musheer360.swiftslate.xposed;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;

import com.musheer360.swiftslate.BuildConfig;
import com.musheer360.swiftslate.bridge.BridgeProtocol;

import java.util.ArrayDeque;

/**
 * Talks to SwiftSlate's HookBridgeService from inside the keyboard process. Binds lazily, queues
 * messages until connected, and unbinds after a quiet period (never while a request is in
 * flight). Everything runs on the main thread.
 */
final class BridgeClient {
    private static final String TAG = "SwiftSlateBridge";
    private static final long IDLE_UNBIND_MS = 30_000L;

    interface Listener {
        void onStarted(long id);
        void onResult(Bundle result);
        /** Bind failed or the SwiftSlate process went away. */
        void onLost();
    }

    private final Context context;
    private Listener listener; // not final: read from a field-initializer lambda before the constructor assigns it
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Message> queue = new ArrayDeque<>();
    private Messenger service;
    private boolean binding;
    private boolean bound;
    private boolean holdOpen;

    private final Messenger incoming = new Messenger(new Handler(Looper.getMainLooper(), msg -> {
        Bundle data = msg.getData();
        if (data == null) return true;
        if (msg.what == BridgeProtocol.MSG_STARTED) {
            listener.onStarted(data.getLong(BridgeProtocol.K_ID));
        } else if (msg.what == BridgeProtocol.MSG_RESULT) {
            listener.onResult(data);
        }
        return true;
    }));

    private final Runnable idleUnbind = new Runnable() {
        @Override public void run() {
            if (holdOpen) { scheduleIdleUnbind(); return; }
            disconnect();
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            service = new Messenger(binder);
            binding = false;
            flush();
        }

        @Override public void onServiceDisconnected(ComponentName name) { lost(); }
        @Override public void onBindingDied(ComponentName name) { lost(); }
        @Override public void onNullBinding(ComponentName name) { lost(); }
    };

    BridgeClient(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    /** Keep the binding alive while a request is running. */
    void setHoldOpen(boolean hold) {
        holdOpen = hold;
        if (!hold) scheduleIdleUnbind();
    }

    void send(int what, Bundle data) {
        Message m = Message.obtain(null, what);
        m.setData(data);
        m.replyTo = incoming;
        queue.add(m);
        main.removeCallbacks(idleUnbind);
        if (service != null) {
            flush();
        } else {
            connect();
        }
        scheduleIdleUnbind();
    }

    void destroy() {
        main.removeCallbacks(idleUnbind);
        queue.clear();
        disconnect();
    }

    private void connect() {
        if (binding) return;
        Intent intent = new Intent(BuildConfig.APPLICATION_ID + BridgeProtocol.ACTION_SUFFIX);
        intent.setPackage(BuildConfig.APPLICATION_ID);
        boolean ok = false;
        try {
            ok = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        } catch (Throwable t) {
            Log.w(TAG, "bindService threw", t);
        }
        if (!ok) {
            // Typical cause on Android 11+: the keyboard cannot see SwiftSlate (package
            // visibility). See README: install with `adb install --force-queryable`.
            Log.w(TAG, "bindService returned false (package visibility?)");
            try { context.unbindService(connection); } catch (Throwable ignored) {}
            lost();
            return;
        }
        binding = true;
        bound = true;
    }

    private void flush() {
        Message m;
        while (service != null && (m = queue.poll()) != null) {
            try {
                service.send(m);
            } catch (RemoteException e) {
                Log.w(TAG, "send failed", e);
                lost();
                return;
            }
        }
    }

    private void disconnect() {
        if (bound) {
            try { context.unbindService(connection); } catch (Throwable ignored) {}
        }
        bound = false;
        binding = false;
        service = null;
    }

    private void lost() {
        boolean hadWork = !queue.isEmpty() || holdOpen;
        queue.clear();
        service = null;
        binding = false;
        bound = false;
        if (hadWork) listener.onLost();
    }

    private void scheduleIdleUnbind() {
        main.removeCallbacks(idleUnbind);
        main.postDelayed(idleUnbind, IDLE_UNBIND_MS);
    }
}
