/*
 * Zerobun Morphe patches - GPLv3.
 *
 * The dashboard inside Instagram. New in v1.2.0.
 *
 * Asked for directly: "the app does have a dashboard inside instgram so the morphe
 * patch u did isn't even full of the feature stuff I asked abt". v1.1.0 shipped the
 * indicator but no way to act on it, so there was nothing to do when a capture came
 * up short except scroll again and hope.
 *
 * WHAT IT IS, AND WHY IT IS BUILT THIS WAY
 * ----------------------------------------
 * A full-screen overlay added to the activity's own content root, using nothing but
 * stock Android views. No Instagram classes, no fingerprints, no reflection into the
 * app - so unlike the capture hook, an Instagram update cannot break it. That is the
 * same reasoning as CaptureHud and AutoScroll: the parts that must keep working are
 * the parts that depend on nothing Instagram owns.
 *
 * WHAT IT DOES
 * ------------
 *  - says exactly where the current capture stands, including what is queued and the
 *    last error verbatim, so a screenshot is useful;
 *  - "Capture this list" starts the automatic scroll ON DEMAND, which is the real
 *    answer to "it captures 123 instead of all of them" - the list is walked to the
 *    end for you instead of by hand, without having to set a patch option;
 *  - "Track an account" sends a handle to the tracker, so an account can be added
 *    from inside Instagram as well as from the app and the dashboard;
 *  - explains, when a capture is short, that the server will treat it as partial and
 *    what that means, rather than leaving a number with no interpretation.
 */

package app.zerobun.extension.followertrack;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;

@SuppressWarnings("unused")
public final class CaptureDashboard {

    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static WeakReference<Activity> host = new WeakReference<>(null);
    private static View root;
    private static TextView body;

    private static final int INK = Color.parseColor("#E5E7EB");
    private static final int DIM = Color.parseColor("#94A3B8");
    private static final int OK = Color.parseColor("#4ADE80");
    private static final int WARN = Color.parseColor("#FBBF24");
    private static final int BAD = Color.parseColor("#F87171");
    private static final int SHEET = Color.parseColor("#F2111318");
    private static final int SCRIM = Color.parseColor("#B3000000");

    private CaptureDashboard() {}

    private static int dp(Activity a, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                a.getResources().getDisplayMetrics());
    }

    public static void show(Activity activity) {
        if (activity == null) return;
        host = new WeakReference<>(activity);
        UI.post(() -> {
            try {
                if (root != null) {
                    dismiss();
                    return;
                }
                build(activity);
                refresh();
            } catch (Throwable ignored) {
                // A dashboard that cannot be drawn must never affect a capture.
            }
        });
    }

    public static void dismiss() {
        UI.post(() -> {
            try {
                if (root != null && root.getParent() instanceof ViewGroup) {
                    ((ViewGroup) root.getParent()).removeView(root);
                }
            } catch (Throwable ignored) {
            }
            root = null;
            body = null;
        });
    }

    private static Button button(Activity a, String label, int accent, View.OnClickListener onClick) {
        Button b = new Button(a);
        b.setAllCaps(false);
        b.setText(label);
        b.setTextColor(INK);
        b.setTextSize(13f);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#1AFFFFFF"));
        bg.setStroke(dp(a, 1), accent);
        bg.setCornerRadius(dp(a, 10));
        b.setBackground(bg);
        b.setOnClickListener(onClick);
        return b;
    }

    private static void build(Activity activity) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) return;

        FrameLayout scrim = new FrameLayout(activity);
        scrim.setBackgroundColor(SCRIM);
        scrim.setClickable(true);
        scrim.setOnClickListener(v -> dismiss());

        LinearLayout sheet = new LinearLayout(activity);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setClickable(true);  // so a tap inside does not close it
        sheet.setPadding(dp(activity, 16), dp(activity, 16), dp(activity, 16), dp(activity, 16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(SHEET);
        bg.setCornerRadius(dp(activity, 16));
        sheet.setBackground(bg);

        TextView title = new TextView(activity);
        title.setText("Zerobun follower tracker");
        title.setTextColor(INK);
        title.setTextSize(16f);
        sheet.addView(title);

        body = new TextView(activity);
        body.setTextColor(DIM);
        body.setTextSize(12.5f);
        body.setPadding(0, dp(activity, 10), 0, dp(activity, 12));
        ScrollView scroller = new ScrollView(activity);
        scroller.addView(body);
        sheet.addView(scroller, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        Button capture = button(activity, "Capture this list to the end", OK, v -> {
            Activity a = host.get();
            if (a == null) return;
            if (AutoScroll.isRunning()) {
                AutoScroll.stop("you stopped it");
                ScrapeCapture.finish(a);
                toast(a, "Stopped. What was captured has been sent.");
            } else {
                captureHere(a);
            }
            refresh();
        });
        sheet.addView(capture, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout addRow = new LinearLayout(activity);
        addRow.setOrientation(LinearLayout.HORIZONTAL);
        addRow.setPadding(0, dp(activity, 10), 0, 0);
        EditText handle = new EditText(activity);
        handle.setHint("Track an account: @handle");
        handle.setHintTextColor(DIM);
        handle.setTextColor(INK);
        handle.setTextSize(13f);
        handle.setSingleLine(true);
        handle.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        addRow.addView(handle, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        addRow.addView(button(activity, "Track", WARN, v -> {
            Activity a = host.get();
            String value = handle.getText().toString().trim();
            if (a == null || value.isEmpty()) return;
            handle.setText("");
            trackAccount(a, value);
        }));
        sheet.addView(addRow);

        TextView close = new TextView(activity);
        close.setText("Close");
        close.setTextColor(DIM);
        close.setTextSize(13f);
        close.setGravity(Gravity.CENTER);
        close.setPadding(0, dp(activity, 14), 0, 0);
        close.setOnClickListener(v -> dismiss());
        sheet.addView(close);

        FrameLayout.LayoutParams sheetParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sheetParams.gravity = Gravity.BOTTOM;
        sheetParams.setMargins(dp(activity, 12), dp(activity, 48), dp(activity, 12), dp(activity, 12));
        sheet.setLayoutParams(sheetParams);
        scrim.addView(sheet);

        // Clear of the system bars, for the same reason the HUD is.
        scrim.setOnApplyWindowInsetsListener((v, insets) -> {
            int bottom = 0, top = 0;
            try {
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    android.graphics.Insets bars = insets.getInsets(
                            android.view.WindowInsets.Type.systemBars());
                    bottom = bars.bottom;
                    top = bars.top;
                } else {
                    bottom = insets.getSystemWindowInsetBottom();
                    top = insets.getSystemWindowInsetTop();
                }
            } catch (Throwable ignored) {
            }
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) sheet.getLayoutParams();
            p.bottomMargin = dp(activity, 12) + bottom;
            p.topMargin = dp(activity, 48) + top;
            sheet.setLayoutParams(p);
            return insets;
        });

        content.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root = scrim;
        // Deliberately NOT Status.setListener(): the HUD owns that single slot, and
        // taking it would leave the HUD frozen for as long as this sheet is open.
        // A one-second poll is plenty for a sheet, and it cannot break anything else.
        UI.post(POLL);
    }

    private static final Runnable POLL = new Runnable() {
        @Override
        public void run() {
            if (root == null) return;
            refresh();
            UI.postDelayed(this, 1_000L);
        }
    };

    /**
     * Captures the list that is open right now, whoever it belongs to.
     *
     * Reads the rendered rows rather than relying on the row-binder hook, which is
     * why this works on other people's lists where the hook never fires. The list
     * owner is taken from the screen; when it cannot be read the server attributes
     * the rows by the names in them, which is still better than refusing to capture.
     */
    public static void captureHere(Activity activity) {
        if (activity == null) return;
        String category = ListInspector.looksLikeFollowing(
                activity.findViewById(android.R.id.content)) ? "followings" : "followers";
        String owner = ListInspector.listOwner(
                activity.findViewById(android.R.id.content));
        ScrapeCapture.start(activity, owner, category);
        if (!AutoScroll.isRunning()) {
            toast(activity, "No scrollable list found - open a followers or "
                    + "following list, then try again.");
        } else {
            toast(activity, "Capturing "
                    + (owner == null || owner.isEmpty() ? "this list" : "@" + owner)
                    + "'s " + category + " to the end. Touch the list to take over.");
        }
    }

    private static void toast(Activity a, String message) {
        try {
            Toast.makeText(a, message, Toast.LENGTH_LONG).show();
        } catch (Throwable ignored) {
        }
    }

    private static void refresh() {
        UI.post(() -> {
            if (body == null) return;
            StringBuilder out = new StringBuilder();

            String target = Status.getTarget();
            if (target == null) {
                out.append("No capture running.\n\n")
                   .append("Open someone's followers or following list, then tap ")
                   .append("\"Capture this list to the end\".");
            } else {
                out.append(Status.headline()).append('\n');
                String upload = Status.uploadLine();
                if (!upload.isEmpty()) out.append(upload).append('\n');

                int have = Status.getCaptured();
                int want = Status.getExpected();
                out.append('\n');
                if (want > 0) {
                    out.append("List: ").append(have).append(" of ").append(want)
                       .append(" rows captured.\n");
                    if (!Status.serverWouldAccept()) {
                        // The honest interpretation of a short capture, which is what
                        // was missing when a list stopped at 123.
                        out.append("That is short of the ")
                           .append((int) Math.floor(want * 0.9d))
                           .append(" needed, so the server will record this as a ")
                           .append("PARTIAL capture: it adds people, but it cannot ")
                           .append("tell who has left. Capture to the end for that.\n");
                    } else {
                        out.append("Enough of the list to count as complete.\n");
                    }
                } else {
                    out.append("Rows captured: ").append(have).append(".\n")
                       .append("How long this list should be is not known yet, so ")
                       .append("this counts as a PARTIAL capture - it adds people ")
                       .append("but cannot detect departures. The account's profile ")
                       .append("has to be captured for that number.\n");
                }

                if (Status.getQueued() > 0) {
                    out.append('\n').append("Waiting to upload: ")
                       .append(Status.getQueued()).append(" batch(es).\n");
                }
                String error = Status.getLastError();
                if (error != null) {
                    out.append('\n').append("Last error: ").append(error).append('\n');
                }
                out.append('\n')
                   .append(AutoScroll.isRunning()
                           ? "Automatic scrolling is running. Touch the list to take over."
                           : "Automatic scrolling is off.");
            }
            body.setText(out.toString());
        });
    }

    /** Sends a watch request through the same endpoint the row capture uses. */
    private static void trackAccount(Activity activity, String raw) {
        String handle = raw.trim();
        if (handle.contains("instagram.com")) {
            handle = handle.substring(handle.indexOf("instagram.com") + "instagram.com".length());
            handle = handle.replace("/", " ").trim();
            int space = handle.indexOf(' ');
            if (space > 0) handle = handle.substring(0, space);
        }
        if (handle.startsWith("@")) handle = handle.substring(1);
        handle = handle.trim().toLowerCase();
        // Refused rather than cleaned: stripping the bad characters out of something
        // that is not a handle produces a real-looking account nobody meant, which
        // then sits on the watchlist waiting for a capture that can never arrive.
        if (handle.isEmpty() || handle.length() > 30 || !handle.matches("[a-z0-9._]+")) {
            toast(activity, "Not an Instagram handle - letters, digits, dots and underscores only.");
            return;
        }
        try {
            JSONObject extra = new JSONObject();
            extra.put("watch", handle);
            Uploader.send(activity, new JSONArray(), handle, "followers", extra);
            toast(activity, "Tracking @" + handle + " - the server applies it within a minute.");
        } catch (Throwable t) {
            toast(activity, "Could not queue that: " + t.getClass().getSimpleName());
        }
    }
}
