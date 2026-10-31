package net.bonjiminium.phonemic;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;

public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView state;
    private Button start;
    private Button stop;
    private String permissionNote = "";
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            state.setText(MicService.status + "\n\n" + permissionNote);
            start.setEnabled(!MicService.running);
            stop.setEnabled(MicService.running);
            handler.postDelayed(this, 500);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);
        // Target 36 uses edge-to-edge; avoid placing controls behind system bars.
        layout.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
            view.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom);
            return insets;
        });
        TextView heading = new TextView(this);
        heading.setText("Phone Mic — native spike");
        heading.setTextSize(24);
        layout.addView(heading);
        state = new TextView(this);
        state.setTextSize(17);
        layout.addView(state);
        start = new Button(this);
        start.setText("Start microphone");
        start.setOnClickListener(view -> startMic());
        layout.addView(start);
        stop = new Button(this);
        stop.setText("Stop");
        stop.setOnClickListener(view -> stopService(new Intent(this, MicService.class)));
        layout.addView(stop);
        TextView note = new TextView(this);
        note.setText("Close the browser microphone first.\n\nAfter Streaming appears, lock the screen and verify speech on Windows.\n\nUses only the pinned Windows certificate. No CA installation or phone recordings.\n\nThis private test APK is paired to one running receiver. If the receiver restarts, rebuild the APK.");
        layout.addView(note);
        setContentView(layout);
    }

    private void startMic() {
        ArrayList<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33 && !getPreferences(MODE_PRIVATE).getBoolean("notificationAsked", false)
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
            getPreferences(MODE_PRIVATE).edit().putBoolean("notificationAsked", true).apply();
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), 1);
            return;
        }
        begin();
    }

    private void begin() {
        // Only a visible button press starts the microphone foreground service.
        permissionNote = "";
        try {
            startForegroundService(new Intent(this, MicService.class));
        } catch (RuntimeException e) {
            permissionNote = "Could not start: " + e.getClass().getSimpleName();
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            permissionNote = "Microphone permission granted. Tap Start again. Notification permission is optional.";
            // Do not start from a permission callback: require a visible button press.
        } else {
            permissionNote = "Microphone permission is required. You can enable it in Android app settings.";
        }
    }

    @Override public void onResume() { super.onResume(); handler.post(refresh); }
    @Override public void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
}
