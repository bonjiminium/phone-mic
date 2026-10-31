package net.bonjiminium.phonemic;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import java.util.ArrayList;

public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView state;
    private Button start, stop, pair, forget;
    private PairingStore store;
    private PairingConfig pairing;
    private String permissionNote = "";
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            String receiver = pairing == null ? "Not paired — scan the PC setup QR" : "Paired: " + pairing.serverUrl;
            state.setText(receiver + "\n\n" + MicService.status + "\n\n" + permissionNote);
            start.setEnabled(!MicService.running && pairing != null);
            stop.setEnabled(MicService.running);
            pair.setEnabled(!MicService.running);
            forget.setEnabled(!MicService.running);
            handler.postDelayed(this, 500);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = new PairingStore(getNoBackupFilesDir().toPath());
        reloadPairing();
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);
        layout.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
            view.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom);
            return insets;
        });
        TextView heading = new TextView(this);
        heading.setText("Phone Mic");
        heading.setTextSize(24);
        layout.addView(heading);
        state = new TextView(this);
        state.setTextSize(17);
        layout.addView(state);
        pair = new Button(this);
        pair.setText("Scan pairing QR");
        pair.setOnClickListener(view -> scan());
        layout.addView(pair);
        start = new Button(this);
        start.setText("Start microphone");
        start.setOnClickListener(view -> startMic());
        layout.addView(start);
        stop = new Button(this);
        stop.setText("Stop");
        stop.setOnClickListener(view -> stopService(new Intent(this, MicService.class)));
        layout.addView(stop);
        forget = new Button(this);
        forget.setText("Forget receiver");
        forget.setOnClickListener(view -> forgetPairing());
        layout.addView(forget);
        TextView note = new TextView(this);
        note.setText("Scan only the pairing QR displayed on your own PC. Confirm the receiver address and certificate fingerprint.\n\nPairing survives app and PC restarts. If the address or certificate changes, scan a new QR — no APK rebuild.\n\nNo CA installation, audio recordings or automatic microphone start. Camera is used only while scanning. Close the browser microphone before starting.");
        layout.addView(note);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(layout);
        setContentView(scroll);
    }

    private void reloadPairing() {
        try { pairing = store.load(); }
        catch (Exception e) {
            pairing = null;
            permissionNote = "Saved pairing could not be read. Scan the PC's pairing QR again.";
        }
    }

    private void scan() {
        if (MicService.running) return;
        new IntentIntegrator(this).setCaptureActivity(PairingScanActivity.class)
                .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                .setPrompt("Scan the pairing QR on your own Windows PC")
                .setBeepEnabled(false).setBarcodeImageEnabled(false)
                .setOrientationLocked(false).initiateScan();
    }

    @Override protected void onActivityResult(int request, int resultCode, Intent data) {
        IntentResult result = IntentIntegrator.parseActivityResult(request, resultCode, data);
        if (result == null) { super.onActivityResult(request, resultCode, data); return; }
        if (result.getContents() == null) {
            permissionNote = "Scan cancelled. Existing pairing unchanged.";
            return;
        }
        final PairingConfig scanned;
        try { scanned = PairingConfig.parse(result.getContents()); }
        catch (IllegalArgumentException e) {
            permissionNote = "Not a valid Phone Mic pairing QR. Existing pairing unchanged.";
            return;
        }
        new AlertDialog.Builder(this).setTitle("Pair with this receiver?")
                .setMessage(scanned.serverUrl + "\n\nCertificate SHA-256:\n" + scanned.certificateSha256
                        + "\n\nConfirm these match the setup page on your PC. Scanning an unknown QR could send your microphone audio to someone else.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Pair", (dialog, which) -> {
                    if (MicService.running) { permissionNote = "Stop the microphone before changing pairing."; return; }
                    try {
                        store.save(scanned);
                        pairing = scanned;
                        permissionNote = "Paired. Tap Start when you want to use the microphone.";
                    } catch (Exception e) { permissionNote = "Could not save pairing; existing pairing unchanged."; }
                }).show();
    }

    private void forgetPairing() {
        if (MicService.running) return;
        new AlertDialog.Builder(this).setTitle("Forget this receiver?")
                .setMessage("Removes pairing from this phone only. It does not revoke other devices. Scan again to reconnect.")
                .setNegativeButton("Cancel", null).setPositiveButton("Forget", (dialog, which) -> {
                    if (MicService.running) return;
                    try { store.clear(); pairing = null; permissionNote = "Pairing removed."; }
                    catch (Exception e) { permissionNote = "Could not remove pairing."; }
                }).show();
    }

    private void startMic() {
        if (pairing == null || MicService.running) return;
        ArrayList<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= 33 && !getPreferences(MODE_PRIVATE).getBoolean("notificationAsked", false)
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
            getPreferences(MODE_PRIVATE).edit().putBoolean("notificationAsked", true).apply();
        }
        if (!missing.isEmpty()) { requestPermissions(missing.toArray(new String[0]), 1); return; }
        permissionNote = "";
        try { startForegroundService(new Intent(this, MicService.class)); }
        catch (RuntimeException e) { permissionNote = "Could not start: " + e.getClass().getSimpleName(); }
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            permissionNote = "Microphone permission granted. Tap Start again. Notification permission is optional.";
        } else { permissionNote = "Microphone permission is required. You can enable it in Android app settings."; }
    }

    @Override public void onResume() { super.onResume(); handler.post(refresh); }
    @Override public void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
}
