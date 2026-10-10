package com.musheer360.swiftslate.xposed;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/**
 * Immutable view of the app's command triggers, published through LSPosed remote preferences.
 * Mirrors CommandManager.findCommand so the keyboard process can decide, without any IPC,
 * whether a keystroke completed a trigger. Pure Java (no Xposed types) so it is unit-testable.
 */
public final class TriggerSnapshot {

    public static final class Match {
        public final String trigger;
        public final boolean needsClip;

        Match(String trigger, boolean needsClip) {
            this.trigger = trigger;
            this.needsClip = needsClip;
        }
    }

    public final String token;
    private final String translatePrefix;
    private final String[] triggers;
    private final boolean[] needsClip;
    private final Set<Character> lastChars = new HashSet<>();

    private TriggerSnapshot(String token, String prefix, String[] triggers, boolean[] needsClip) {
        this.token = token;
        this.translatePrefix = prefix + "translate:";
        this.triggers = triggers;
        this.needsClip = needsClip;
        for (String t : triggers) {
            if (!t.isEmpty()) lastChars.add(t.charAt(t.length() - 1));
        }
    }

    /** @return null when the JSON is missing or malformed. */
    public static TriggerSnapshot parse(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            JSONObject root = new JSONObject(json);
            String token = root.optString("token", "");
            String prefix = root.optString("prefix", "?");
            JSONArray arr = root.getJSONArray("triggers");
            String[] triggers = new String[arr.length()];
            boolean[] clip = new boolean[arr.length()];
            int n = 0;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String t = o.optString("t", "");
                if (t.isEmpty()) continue;
                triggers[n] = t;
                clip[n] = o.optInt("c", 0) == 1;
                n++;
            }
            if (token.isEmpty() || n == 0) return null;
            String[] t2 = new String[n];
            boolean[] c2 = new boolean[n];
            System.arraycopy(triggers, 0, t2, 0, n);
            System.arraycopy(clip, 0, c2, 0, n);
            return new TriggerSnapshot(token, prefix, t2, c2);
        } catch (Exception e) {
            return null;
        }
    }

    /** Same rules as CommandManager.findCommand; triggers arrive longest-first. */
    public Match match(String text) {
        if (text == null || text.isEmpty()) return null;
        char last = text.charAt(text.length() - 1);
        if (!lastChars.contains(last)) {
            if (!Character.isLetterOrDigit(last) || !text.contains(translatePrefix)) return null;
        }
        for (int i = 0; i < triggers.length; i++) {
            if (text.endsWith(triggers[i])) return new Match(triggers[i], needsClip[i]);
        }
        int idx = text.lastIndexOf(translatePrefix);
        if (idx >= 0) {
            String lang = text.substring(idx + translatePrefix.length());
            if (lang.length() >= 2 && lang.length() <= 5 && isAsciiAlnum(lang)) {
                return new Match(translatePrefix + lang, false);
            }
        }
        return null;
    }

    private static boolean isAsciiAlnum(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
            if (!ok) return false;
        }
        return true;
    }
}
