/*
 * Zerobun Morphe patches - GPLv3.
 *
 * Captures a follow list by reading what is on the screen. New in v1.2.0.
 *
 * WHY THIS EXISTS
 * ---------------
 * The row-binder hook only fires for the one adapter the patch fingerprints, which
 * turned out to be the one used on your OWN profile. Open someone else's followers
 * and nothing happened - reported as "it doesn't even show up at that time, and only
 * comes when I'm on my own profile".
 *
 * The user's instinct is the right design principle: "if I can see it on my phone it
 * should be able to capture". So this reads the rendered rows instead of hooking the
 * code that renders them. It works on ANY list that is visible, whatever adapter
 * Instagram used, and - like the HUD, the dashboard and the auto-scroll - it depends
 * on nothing Instagram owns, so an Instagram update cannot break it.
 *
 * WHAT IT COSTS, STATED PLAINLY
 * -----------------------------
 * A rendered row shows a handle and a display name; it does not show the numeric pk.
 * So these rows are keyed by username server-side. That is not a degraded mode - the
 * identity store has always keyed people by `name:<username>` until a pk is known,
 * and merges them onto `id:<pk>` the moment one arrives (from the binder hook, which
 * still runs where it can). The one thing a username cannot survive is a RENAME,
 * which reads as a departure plus an arrival. The binder hook remains the better
 * source; this is the one that always works.
 */

package app.zerobun.extension.followertrack;

import android.app.Activity;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@SuppressWarnings("unused")
public final class ScrapeCapture {

    /** Button and header words that look like handles but are not. */
    private static final Set<String> NOT_HANDLES = new HashSet<>(java.util.Arrays.asList(
            "follow", "following", "followers", "remove", "message", "requested",
            "follow back", "unfollow", "suggested", "search", "cancel", "done",
            "all", "mutual", "accounts", "friends", "subscribe", "subscribed"));

    private static String target = "";
    private static String category = "followers";
    private static String session = "";
    private static final Set<String> seen = new HashSet<>();
    private static int uploadedRows;

    private ScrapeCapture() {}

    /** Begins a fresh scrape of whatever list is on screen, and scrolls it. */
    public static void start(Activity activity, String whose, String which) {
        if (activity == null) return;
        target = whose == null ? "" : whose;
        category = which == null ? "followers" : which;
        session = "x" + Long.toString(System.currentTimeMillis(), 36);
        seen.clear();
        uploadedRows = 0;

        Status.reset();
        Status.beginCapture(target, category,
                ListInspector.expectedCount(
                        activity.findViewById(android.R.id.content), category));
        CaptureHud.attach(activity);
        sweep(activity);
        AutoScroll.start(activity);
        Log.i(Uploader.TAG, "scrape capture started for " + target + "/" + category);
    }

    public static boolean isRunning() {
        return !session.isEmpty();
    }

    public static String getTarget() {
        return target;
    }

    /**
     * Reads every row currently rendered and uploads the ones not seen before.
     *
     * Called on each scroll step rather than on a timer: a step is exactly when new
     * rows have arrived, and scraping an unchanged screen would upload nothing.
     */
    public static void sweep(Activity activity) {
        if (activity == null || session.isEmpty()) return;
        try {
            View content = activity.findViewById(android.R.id.content);
            List<JSONObject> fresh = new ArrayList<>();
            collectRows(content, 0, fresh);
            if (fresh.isEmpty()) return;

            JSONArray users = new JSONArray();
            for (JSONObject row : fresh) users.put(row);
            uploadedRows += fresh.size();
            Status.rowsCaptured(uploadedRows);

            JSONObject extra = new JSONObject();
            extra.put("session", session);
            extra.put("scraped", true);
            Uploader.send(activity, users, target, category, extra);
        } catch (Throwable t) {
            Log.w(Uploader.TAG, "sweep failed", t);
        }
    }

    /** Closes the scrape, claiming completeness only if the count agrees. */
    public static void finish(Activity activity) {
        if (session.isEmpty()) return;
        sweep(activity);
        try {
            if (activity != null && Status.serverWouldAccept()) {
                JSONObject extra = new JSONObject();
                extra.put("session", session);
                extra.put("complete", true);
                if (Status.getExpected() > 0) extra.put("expected_count", Status.getExpected());
                Uploader.send(activity, new JSONArray(), target, category, extra);
                Status.markComplete();
                Log.i(Uploader.TAG, "scrape complete: " + uploadedRows + " rows");
            } else {
                Log.i(Uploader.TAG, "scrape ended short: " + uploadedRows + " of "
                        + Status.getExpected() + " - not claiming complete");
            }
        } catch (Throwable ignored) {
        }
        session = "";
    }

    /**
     * Walks the view tree looking for rows that contain a handle.
     *
     * A "row" is any view holding a TextView whose text is handle-shaped. That is
     * loose on purpose: it has to survive Instagram restyling its list, and a false
     * positive costs one junk username that the server's own handle validation then
     * rejects, while a false negative costs a real follower.
     */
    private static void collectRows(View view, int depth, List<JSONObject> out) {
        if (view == null || depth > 30 || out.size() > 400) return;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            // A row usually holds its handle and name as direct-ish children, so try
            // the group itself before descending into it.
            JSONObject row = readRow(group);
            if (row != null) {
                out.add(row);
                return;  // do not also read the handle out of its own children
            }
            for (int i = 0; i < group.getChildCount(); i++) {
                collectRows(group.getChildAt(i), depth + 1, out);
            }
        }
    }

    private static JSONObject readRow(ViewGroup group) {
        String handle = null;
        String fullName = null;
        int texts = 0;
        for (int i = 0; i < group.getChildCount(); i++) {
            View child = group.getChildAt(i);
            if (!(child instanceof TextView) || !child.isShown()) continue;
            texts++;
            if (texts > 6) break;
            CharSequence raw = ((TextView) child).getText();
            String text = raw == null ? "" : raw.toString().trim();
            if (text.isEmpty() || text.length() > 40) continue;
            if (handle == null && isHandle(text)) {
                handle = text.toLowerCase(Locale.US);
            } else if (fullName == null && !isHandle(text) && text.length() <= 40) {
                fullName = text;
            }
        }
        if (handle == null || seen.contains(handle)) return null;
        seen.add(handle);
        try {
            JSONObject row = new JSONObject();
            row.put("username", handle);
            if (fullName != null && !NOT_HANDLES.contains(fullName.toLowerCase(Locale.US))) {
                row.put("full_name", fullName);
            }
            return row;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isHandle(String text) {
        if (text.isEmpty() || text.length() > 30) return false;
        if (NOT_HANDLES.contains(text.toLowerCase(Locale.US))) return false;
        // Instagram handles are lowercase on screen; a capital letter means this is a
        // display name or a button, not a handle.
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '_';
            if (!ok) return false;
        }
        // A bare number is a count, not a handle.
        boolean hasLetter = false;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isLetter(text.charAt(i))) {
                hasLetter = true;
                break;
            }
        }
        return hasLetter;
    }
}
