/*
 * Zerobun Morphe patches - GPLv3.
 *
 * Approach learned from Piko (https://github.com/crimera/piko, GPLv3): the follow-list
 * row binder and the User-model getters it resolves. This is an independent
 * implementation in its own package so it can be applied alongside Piko without
 * clashing.
 */

package app.zerobun.extension.followertrack;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

/**
 * Called at the end of the follow-list binder's bindView for every row you scroll past.
 * Collects {pk, username, full_name, profile_pic_url} and which list it came from, and
 * hands batches to {@link Uploader}. Makes no Instagram request of its own.
 *
 * <p>v2 additions: it feeds {@link Status} so the on-screen indicator can say what is
 * happening, attaches {@link CaptureHud}, optionally starts {@link AutoScroll}, and
 * tags every batch with a capture session so the server can group them - and, when the
 * list really ends, sends a closing batch marked complete so the server may diff the
 * whole list rather than only adding to it.
 */
@SuppressWarnings("unused")
public final class FollowerTrackHook {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long FLUSH_DEBOUNCE_MS = 2_500;

    private static final Object LOCK = new Object();
    private static String currentKey = "";
    private static String currentTarget = "";
    private static String currentCategory = "followers";
    private static JSONArray buffer = new JSONArray();
    private static final Set<String> seen = new HashSet<>();
    private static Context appContext;
    private static boolean loggedReady;

    /**
     * Identifies one scroll of one list. The server groups batches by this and only
     * diffs a session that was closed as complete, so it must change when the list
     * changes and not before.
     */
    private static String session = "";
    private static boolean hudAttached;

    private static final Runnable FLUSH = FollowerTrackHook::flush;

    private FollowerTrackHook() {}

    /** Same parameters as bindView(int, View, Object, Object), with the binder first. */
    public static void onRowBound(Object binder, int position, View row, Object user) {
        try {
            if (row != null && appContext == null) {
                appContext = row.getContext().getApplicationContext();
            }
            // Use this one firing to bootstrap the capture button onto EVERY screen.
            // The hook only fires for the adapter the patch fingerprints (your own
            // profile's list, in practice), so on its own it leaves other people's
            // lists with no indicator and nothing to press. Registering here means
            // one fire anywhere is enough for the rest of the process.
            if (row != null) Bootstrap.init(row.getContext());
            if (!loggedReady) {
                loggedReady = true;
                Log.i(Uploader.TAG, "follower tracking active");
            }

            String target = "";
            String category = "followers";
            // Best effort: if this build's list-context fields were not resolved, rows are
            // still collected and the server attributes them by username.
            try {
                Object listType = Reflect.field(binder, Config.get("f.listType"));
                String raw = Reflect.str(Reflect.field(listType, Config.get("f.listTypeValue")));
                // The tracker accepts exactly "followers" or "followings".
                category = raw.toLowerCase().contains("following") ? "followings" : "followers";
                Object listConfig = Reflect.field(binder, Config.get("f.listConfig"));
                Object listData = Reflect.field(listConfig, Config.get("f.followListData"));
                target = Reflect.str(Reflect.field(listData, Config.get("f.targetId")));
                if (target.isEmpty()) {
                    Object session = Reflect.field(binder, Config.get("f.session"));
                    target = Reflect.str(Reflect.call(session, "getUserId"));
                }
            } catch (Throwable ignored) {
            }

            String pk = Reflect.str(Reflect.call(user, Config.get("m.id")));
            if (pk.isEmpty()) return;

            JSONObject entry = new JSONObject();
            entry.put("pk", pk);
            entry.put("username", Reflect.str(safeCall(user, "m.username")));
            entry.put("full_name", Reflect.str(safeCall(user, "m.fullName")));
            Object picInfo = safeCall(user, "m.picInfo");
            entry.put("profile_pic_url", picInfo == null ? "" : Reflect.str(safeCallNamed(picInfo, "getUrl")));

            add(target, category, pk, entry, position, row);
            showIndicator(row);
        } catch (Throwable t) {
            // Never break Instagram's own list.
            Log.w(Uploader.TAG, "row capture failed", t);
        }
    }

    /**
     * Brings up the HUD, and auto-scroll if it was switched on.
     *
     * Wrapped whole: an indicator that cannot be drawn must never stop a capture, which
     * is the only thing here that matters.
     */
    private static void showIndicator(View row) {
        if (hudAttached || row == null) return;
        try {
            Activity activity = activityOf(row.getContext());
            if (activity == null) return;
            hudAttached = true;
            CaptureHud.attach(activity);
            if ("true".equalsIgnoreCase(String.valueOf(Config.get("cfg.autoscroll")))) {
                AutoScroll.start(activity);
            }
        } catch (Throwable t) {
            Log.w(Uploader.TAG, "indicator unavailable", t);
        }
    }

    private static Activity activityOf(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    private static Object safeCall(Object obj, String key) {
        return safeCallNamed(obj, Config.get(key));
    }

    private static Object safeCallNamed(Object obj, String name) {
        try {
            return Reflect.call(obj, name);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The metadata every batch of one scroll carries. */
    private static JSONObject meta(String sessionId) {
        JSONObject extra = new JSONObject();
        try {
            if (sessionId != null && !sessionId.isEmpty()) extra.put("session", sessionId);
        } catch (Throwable ignored) {
        }
        return extra;
    }

    private static void add(String target, String category, String pk,
                            JSONObject entry, int position, View row) {
        int batchSize = Math.max(1, Math.min(200, Config.getInt("cfg.batch", 50)));
        JSONArray toSend = null;
        String sendTarget = null, sendCategory = null, sendSession = null;
        int total;
        synchronized (LOCK) {
            String key = target + "|" + category;
            // A bind at the very top of a list that we have already collected from is
            // a RE-ENTRY, not a continuation. Without this the dedupe set below still
            // holds everyone from last time, so re-opening the list captures nothing
            // at all and no batch is ever sent - exactly the reported "when I try
            // going there again it doesn't work".
            boolean reEntered = key.equals(currentKey) && position <= 1 && seen.size() > 2;
            if (!key.equals(currentKey) || reEntered) {
                if (buffer.length() > 0) {
                    toSend = buffer;
                    sendTarget = currentTarget;
                    sendCategory = currentCategory;
                    sendSession = session;
                }
                currentKey = key;
                currentTarget = target;
                currentCategory = category;
                buffer = new JSONArray();
                seen.clear();
                // A new list is a new capture session, and a new indicator.
                session = "s" + Long.toString(System.currentTimeMillis(), 36);
                AutoScroll.stop("the list changed");
                // reset() before beginCapture() so a re-entry starts from zero rather
                // than continuing last visit's count, which would make a fresh full
                // scroll look like it had already captured everything.
                Status.reset();
                Status.beginCapture(target, category,
                        ListInspector.expectedCount(row, category));
                hudAttached = false;
                currentKey = key;
            }
            if (seen.add(pk)) buffer.put(entry);
            total = seen.size();
            if (buffer.length() >= batchSize) {
                // Send the carried-over batch HERE rather than letting the assignment
                // below overwrite the reference to it. Overwriting silently dropped a
                // whole batch whenever a list change and a full buffer landed in the
                // same call.
                if (toSend != null && appContext != null) {
                    Uploader.send(appContext, toSend, sendTarget, sendCategory, meta(sendSession));
                }
                toSend = buffer;
                sendTarget = currentTarget;
                sendCategory = currentCategory;
                sendSession = session;
                buffer = new JSONArray();
            }
        }
        Status.rowsCaptured(total);
        if (toSend != null && appContext != null) {
            Uploader.send(appContext, toSend, sendTarget, sendCategory, meta(sendSession));
        }
        MAIN.removeCallbacks(FLUSH);
        MAIN.postDelayed(FLUSH, FLUSH_DEBOUNCE_MS);
    }

    private static void flush() {
        JSONArray toSend;
        String target, category, sessionId;
        synchronized (LOCK) {
            if (buffer.length() == 0) return;
            toSend = buffer;
            target = currentTarget;
            category = currentCategory;
            sessionId = session;
            buffer = new JSONArray();
        }
        if (appContext != null) {
            Uploader.send(appContext, toSend, target, category, meta(sessionId));
        }
    }

    /**
     * Closes the capture: the list reached its end.
     *
     * Sends any remaining rows, then a final batch marked complete, which is what
     * permits the server to diff the list as a whole rather than only adding to it.
     *
     * The claim is deliberately conditional. `Status.serverWouldAccept()` applies the
     * same 90% ratio the server uses, so if the scroll ended short of the profile's own
     * count this reports the rows WITHOUT claiming completeness - the server then treats
     * it as partial, which is exactly right, and the indicator says the list still needs
     * doing. Claiming it anyway is how a broken scroll turns into hundreds of false
     * departures.
     */
    public static void onListEnded() {
        flush();
        String target, category, sessionId;
        synchronized (LOCK) {
            target = currentTarget;
            category = currentCategory;
            sessionId = session;
        }
        if (appContext == null || sessionId == null || sessionId.isEmpty()) return;
        boolean acceptable = Status.serverWouldAccept();
        JSONObject extra = meta(sessionId);
        try {
            if (acceptable) {
                extra.put("complete", true);
                if (Status.getExpected() > 0) {
                    extra.put("expected_count", Status.getExpected());
                }
            }
        } catch (Throwable ignored) {
        }
        if (!acceptable) {
            Log.i(Uploader.TAG, "list ended but only " + Status.getCaptured() + " of "
                    + Status.getExpected() + " captured - not claiming complete");
            return;
        }
        Uploader.send(appContext, new JSONArray(), target, category, extra);
    }

    /** Lets the profile hook tell the capture how long the list should be. */
    public static void onProfileCount(String target, String category, int count) {
        if (count > 0) Status.beginCapture(target, category, count);
    }
}
