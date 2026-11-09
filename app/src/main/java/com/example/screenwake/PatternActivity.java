package com.example.screenwake;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;

import java.util.Locale;

public class PatternActivity extends Activity {

    private Handler h = new Handler(Looper.getMainLooper());

    private long until;

    private TextView timer;

    private Runnable tick = new Runnable() {

        public void run() {

            long left = Math.max(
                0,
                until - System.currentTimeMillis()
            );

            if(timer != null) {

                timer.setText(
                    String.format(
                        Locale.getDefault(),
                        "开机亮屏中 · %02d:%02d",
                        left / 60000,
                        (left / 1000) % 60
                    )
                );
            }

            if(left <= 0) {
                finish();
            } else {
                h.postDelayed(this,1000);
            }
        }
    };

    @Override
    protected void onCreate(Bundle b) {

        super.onCreate(b);

        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        );

        if(Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }

        until = System.currentTimeMillis() + 300000;

        LinearLayout root = new LinearLayout(this);

        root.setOrientation(1);

        root.setGravity(Gravity.CENTER);

        root.setBackground(
            new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{
                    Color.rgb(12,28,65),
                    Color.rgb(30,90,130),
                    Color.rgb(90,35,110)
                }
            )
        );

        TextView title = new TextView(this);

        title.setText("SCREEN WAKE");
        title.setTextColor(Color.WHITE);
        title.setTextSize(28);
        title.setTypeface(null,1);
        title.setGravity(Gravity.CENTER);

        root.addView(title);

        timer = new TextView(this);

        timer.setTextColor(Color.WHITE);
        timer.setTextSize(18);
        timer.setGravity(Gravity.CENTER);
        timer.setPadding(0,24,0,24);

        root.addView(timer);

        TextView note = new TextView(this);

        note.setText("屏幕将在 5 分钟后自动恢复");
        note.setTextColor(Color.LTGRAY);
        note.setTextSize(14);

        root.addView(note);

        setContentView(root);

        tick.run();
    }

    @Override
    protected void onDestroy() {

        h.removeCallbacks(tick);

        super.onDestroy();
    }
}
