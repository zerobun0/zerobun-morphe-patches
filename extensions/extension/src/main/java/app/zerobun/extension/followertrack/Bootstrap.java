/*
 * Zerobun Morphe patches - GPLv3.
 *
 * Puts a capture button on every screen. New in v1.2.0.
 *
 * WHY
 * ---
 * Everything in v1.1.0 hung off the row-binder hook firing, and that hook only fires
 * for the one adapter the patch fingerprints. On someone else's followers list it
 * never fired, so there was no indicator, no dashboard, and no way to do anything at
 * all - reported as "it doesn't even show up at that time, and only comes when I'm on
 * my own profile. Tf? ... maybe add a button to capture automatically".
 *
 * So the button is not tied to the hook. The hook is used ONCE, as a bootstrap: the
 * first time it fires anywhere (your own profile is enough), this registers activity
 * lifecycle callbacks on the Application, and from then on every screen gets the
 * button - including profiles and follow lists the hook knows nothing about.
 *
 * A Morphe extension is code only, with no manifest of its own, so there is no
 * ContentProvider trick available to run at process start; one hook firing once is
 * the available bootstrap. Once registered, it survives for the life of the process.
 *
 * The button itself depends on nothing Instagram owns - just an Activity and
 * android.R.id.content - which is why it keeps working when the hooks do not.
 */

package app.zerobun.extension.followertrack;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;

@SuppressWarnings("unused")
public final class Bootstrap {

    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static final String TAG_BUBBLE = "zb-capture-bubble";
    private static boolean registered;
    private static WeakReference<Activity> current = new WeakReference<>(null);

    private Bootstrap() {}

    public static Activity currentActivity() {
        return current.get();
    }

    /** Idempotent; safe to call from every single row bind. */
    public static void init(Context context) {
        if (registered || context == null) return;
        Context app = context.getApplicationContext();
        if (!(app instanceof Application)) return;
        registered = true;
        try {
            ((Application) app).registerActivityLifecycleCallbacks(
                    new Application.ActivityLifecycleCallbacks() {
                        @Override
                        public void onActivityResumed(Activity activity) {
                            current = new WeakReference<>(activity);
                            UI.postDelayed(() -> addBubble(activity), 400);
                        }

                        @Override
                        public void onActivityPaused(Activity activity) {}

                        @Override
                        public void onActivityCreated(Activity a, Bundle b) {}

                        @Override
                        public void onActivityStarted(Activity a) {}

                        @Override
                        public void onActivityStopped(Activity a) {}

                        @Override
                        public void onActivitySaveInstanceState(Activity a, Bundle b) {}

                        @Override
                        public void onActivityDestroyed(Activity a) {}
                    });
            Log.i(Uploader.TAG, "capture button registered for every screen");
        } catch (Throwable t) {
            registered = false;
            Log.w(Uploader.TAG, "could not register lifecycle callbacks", t);
        }
    }

    private static int dp(Activity a, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                a.getResources().getDisplayMetrics());
    }

    /**
     * A small round button, bottom-right, above the system bars.
     *
     * Deliberately small and out of the way: it is on every screen of someone's
     * Instagram, so it must not get between them and the app. Tapping it opens the
     * dashboard, which is where capturing is actually started.
     */
    private static void addBubble(Activity activity) {
        try {
            ViewGroup content = activity.findViewById(android.R.id.content);
            if (content == null || content.findViewWithTag(TAG_BUBBLE) != null) return;

            TextView bubble = new TextView(activity);
            bubble.setTag(TAG_BUBBLE);
            bubble.setText("⤓");
            bubble.setTextColor(Color.WHITE);
            bubble.setTextSize(19f);
            bubble.setGravity(Gravity.CENTER);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(Color.parseColor("#CC1F2937"));
            bg.setStroke(dp(activity, 1), Color.parseColor("#8081C784"));
            bubble.setBackground(bg);
            bubble.setAlpha(0.75f);

            int size = dp(activity, 42);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
            lp.gravity = Gravity.BOTTOM | Gravity.END;
            lp.setMargins(0, 0, dp(activity, 14), dp(activity, 96));
            bubble.setLayoutParams(lp);

            bubble.setOnApplyWindowInsetsListener((v, insets) -> {
                int bottom = 0;
                try {
                    if (android.os.Build.VERSION.SDK_INT >= 30) {
                        bottom = insets.getInsets(
                                android.view.WindowInsets.Type.systemBars()).bottom;
                    } else {
                        bottom = insets.getSystemWindowInsetBottom();
                    }
                } catch (Throwable ignored) {
                }
                FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) v.getLayoutParams();
                p.bottomMargin = dp(activity, 96) + bottom;
                v.setLayoutParams(p);
                return insets;
            });

            bubble.setOnClickListener(v -> CaptureDashboard.show(activity));
            // Long-press captures straight away, for when the dashboard is one tap
            // too many - the list is usually already open when you want this.
            bubble.setOnLongClickListener(v -> {
                CaptureDashboard.captureHere(activity);
                return true;
            });

            content.addView(bubble);
            bubble.requestApplyInsets();
        } catch (Throwable t) {
            Log.w(Uploader.TAG, "could not add the capture button", t);
        }
    }
}
