/*
 * Zerobun Morphe patches - GPLv3.
 *
 * Works out how long a follower/following list SHOULD be, so a capture can be
 * verified instead of guessed at. New in v1.2.0.
 *
 * Asked for directly: "It needs to verify if it got all the followers and following
 * too like u normally the total in profile and then captured total it needs to be
 * smart and capture it automatically".
 *
 * Without this number nothing downstream can be trusted: the server refuses to diff
 * a list as complete unless it has a count to check the rows against (because
 * recording everyone missing as "left" cannot be undone), and the auto-scroll has no
 * idea when to stop. So the number is the thing that turns "123 rows captured" into
 * either "123 of 546, keep going" or "546 of 546, done".
 *
 * TWO INDEPENDENT SOURCES, NEITHER OF WHICH NEEDS A FINGERPRINT
 * ------------------------------------------------------------
 * 1. The adapter's item count, via the public RecyclerView.getAdapter().getItemCount()
 *    by reflection. Language-independent and exact for what is LOADED - it grows as
 *    pages arrive, so it is a floor, not the total.
 * 2. The count rendered on screen ("546 followers", "1,234 following", "546" under a
 *    tab). This is the real total. Scraped from visible TextViews, so it depends on
 *    Instagram's text rather than its bytecode - which is why it is allowed to fail
 *    quietly and be retried on the next bind.
 *
 * The scrape wins when it finds something, because it is the only one of the two that
 * knows the whole list length. The adapter count is the floor that keeps the capture
 * honest when the scrape finds nothing.
 */

package app.zerobun.extension.followertrack;

import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ListInspector {

    /** "546 followers", "1,234 following", "2.3M followers". */
    private static final Pattern LABELLED = Pattern.compile(
            "([0-9][0-9.,\\s]*[0-9]|[0-9])\\s*([kKmM]?)\\s*(followers?|following|follower)",
            Pattern.CASE_INSENSITIVE);
    /** A bare number on its own, as used under the profile tabs. */
    private static final Pattern BARE = Pattern.compile(
            "^\\s*([0-9][0-9.,]*)\\s*([kKmM]?)\\s*$");

    private ListInspector() {}

    /**
     * The list's expected length, or 0 when it cannot be established.
     *
     * Zero is a real answer and must stay distinguishable from a small list: the
     * server treats "no count known" as a reason to refuse completeness, which is
     * the safe direction. Never invent a number here.
     */
    static int expectedCount(View anyChild, String category) {
        int scraped = 0;
        int adapter = 0;
        try {
            View rootView = anyChild == null ? null : anyChild.getRootView();
            if (rootView == null) return 0;
            scraped = scrapeCount(rootView, category, 0);
            adapter = adapterCount(rootView, 0);
        } catch (Throwable t) {
            Log.w(Uploader.TAG, "could not inspect the list", t);
        }
        int best = scraped > 0 ? scraped : adapter;
        if (best > 0) {
            Log.i(Uploader.TAG, "expected " + category + " = " + best
                    + " (on-screen " + scraped + ", adapter " + adapter + ")");
        }
        return best;
    }

    /** The largest adapter item count on screen - the loaded-rows floor. */
    static int adapterCount(View view, int depth) {
        if (view == null || depth > 25) return 0;
        int best = 0;
        try {
            Method getAdapter = findMethod(view.getClass(), "getAdapter");
            if (getAdapter != null) {
                Object adapter = getAdapter.invoke(view);
                if (adapter != null) {
                    Method count = findMethod(adapter.getClass(), "getItemCount");
                    if (count == null) count = findMethod(adapter.getClass(), "getCount");
                    if (count != null) {
                        Object value = count.invoke(adapter);
                        if (value instanceof Integer) best = (Integer) value;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                best = Math.max(best, adapterCount(group.getChildAt(i), depth + 1));
            }
        }
        return best;
    }

    /**
     * The count Instagram is displaying for this list.
     *
     * Prefers a label that names the category we are capturing, so a following count
     * is not mistaken for a follower count on a screen showing both. Falls back to a
     * labelled count of either kind, and only then to a bare number.
     */
    private static int scrapeCount(View view, String category, int depth) {
        if (view == null || depth > 30) return 0;
        boolean wantFollowers = !"followings".equals(category);
        int exact = 0;
        int labelled = 0;
        int bare = 0;

        if (view instanceof TextView && view.isShown()) {
            CharSequence raw = ((TextView) view).getText();
            String text = raw == null ? "" : raw.toString();
            if (text.length() <= 64) {
                Matcher m = LABELLED.matcher(text);
                while (m.find()) {
                    int value = parse(m.group(1), m.group(2));
                    if (value <= 0) continue;
                    boolean isFollowing = m.group(3).toLowerCase().startsWith("following");
                    if (isFollowing != wantFollowers) {
                        exact = Math.max(exact, value);
                    } else {
                        labelled = Math.max(labelled, value);
                    }
                }
                if (exact == 0 && labelled == 0) {
                    Matcher b = BARE.matcher(text);
                    if (b.matches()) bare = parse(b.group(1), b.group(2));
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                int found = scrapeCount(group.getChildAt(i), category, depth + 1);
                // A child's answer is already category-resolved, so take the largest.
                if (found > 0) labelled = Math.max(labelled, found);
            }
        }
        if (exact > 0) return exact;
        if (labelled > 0) return labelled;
        // A bare number is the weakest evidence, so it is only used when nothing on
        // the whole screen said what it counts.
        return bare;
    }

    private static int parse(String digits, String suffix) {
        try {
            String clean = digits.replace(",", "").replace(" ", "").trim();
            double value;
            if (suffix != null && !suffix.isEmpty()) {
                value = Double.parseDouble(clean)
                        * ("m".equalsIgnoreCase(suffix) ? 1_000_000d : 1_000d);
            } else {
                // A dot inside a plain count is a thousands separator in some locales
                // (1.234), not a decimal point - dropping it is right for a count.
                value = Double.parseDouble(clean.replace(".", ""));
            }
            // An abbreviated count ("2.3M") is not exact, and treating it as exact
            // would make completeness unreachable. Capped so it stays a sane target.
            if (value <= 0 || value > 50_000_000d) return 0;
            return (int) Math.round(value);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static Method findMethod(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getMethod(name);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }
}
