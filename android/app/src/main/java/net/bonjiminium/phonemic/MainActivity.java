package net.bonjiminium.phonemic;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;

/** One primary next action per state. Scanning alone never starts capture. */
public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView state, receiver, hint, expiry;
    private ProgressBar meter;
    private Button primary, settings;
    private PairingStore store;
    private PairingConfig pairing;
    private String permissionNote = "";
    private boolean pendingStart = false;
    private final Runnable refresh = new Runnable() {
        @Override public void run() { render(); handler.postDelayed(this, 200); }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        store = new PairingStore(getNoBackupFilesDir().toPath());
        reloadPairing();
        if (saved != null) pendingStart = saved.getBoolean("pendingStart", false);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad, pad, pad);
        layout.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars());
            view.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom);
            return insets;
        });
        text(layout, "Phone Mic", 28);
        receiver = text(layout, "", 16);
        state = text(layout, "", 22);
        state.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);
        hint = text(layout, "", 17);
        text(layout, "Microphone level — speak to test", 16);
        meter = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        meter.setMax(100);
        meter.setContentDescription("Microphone input level");
        layout.addView(meter);
        primary = new Button(this);
        primary.setOnClickListener(view -> {
            if (MicService.running) {
                pendingStart = false;
                stopService(new Intent(this, MicService.class));
            } else if (pairing == null || ("error".equals(MicService.phase) && "pair".equals(MicService.recovery))) {
                scan();
            } else { startMic(); }
        });
        layout.addView(primary);
        settings = new Button(this);
        settings.setText("Settings & help");
        settings.setOnClickListener(view -> showSettings());
        layout.addView(settings);
        expiry = text(layout, "", 14);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(layout);
        setContentView(scroll);
        render();
    }

    private TextView text(LinearLayout layout, String value, int size) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setPadding(0, 8, 0, 12);
        layout.addView(text);
        return text;
    }

    private void render() {
        receiver.setText(pairing == null ? "Not paired" : "Your PC: " + pairing.serverUrl);
        String title, help, action;
        if (pairing == null) {
            title = "Connect to your Windows PC";
            help = "On your PC, run ./run.sh serve and open .local/connect.html. Then scan its pairing QR here.";
            action = "Scan pairing QR";
        } else if (MicService.running) {
            boolean streaming = "streaming".equals(MicService.phase);
            boolean acknowledged = MicService.lastAcknowledgedAt > 0
                    && System.currentTimeMillis() - MicService.lastAcknowledgedAt < 3000;
            title = streaming ? (acknowledged ? "Microphone live — PC receiving audio" : "Microphone capturing — waiting for PC")
                    : "Connecting to your PC…";
            help = streaming ? "You can lock your phone. In your Windows app, select CABLE Output (VB-Audio Virtual Cable) and check its microphone meter."
                    : "Leave the receiver running on your PC. You can cancel at any time.";
            action = streaming ? "Stop microphone" : "Cancel connection";
        } else if ("error".equals(MicService.phase)) {
            title = "Microphone stopped";
            help = MicService.status;
            action = "pair".equals(MicService.recovery) ? "Scan updated pairing QR" : "Retry microphone";
        } else {
            title = "Ready to connect";
            help = "Start the receiver on your PC with ./run.sh serve, then start your microphone here.";
            action = "Start microphone";
        }
        if (MicService.running && Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            help += "\n\nNotifications are off. Reopen this app to stop, or enable notifications in Settings & help for a notification Stop button.";
        }
        if (!permissionNote.isEmpty()) help += "\n\n" + permissionNote;
        state.setText(title);
        hint.setText(help);
        primary.setText(action);
        meter.setProgress(Math.round(MicService.level * 100));
        long until = pairing == null ? 0 : pairing.certificateExpiresAt;
        if (MicService.running && MicService.verifiedExpiresAt > 0) until = MicService.verifiedExpiresAt;
        if (until == 0) expiry.setText(pairing == null ? "Audio goes only to the PC you confirm. Scanning alone does not start the microphone."
                : "Certificate expiry will be checked when you connect.");
        else {
            long days = (long) Math.ceil((until - System.currentTimeMillis()) / 86400000.0);
            expiry.setText(days <= 0 ? "Saved certificate expired. Run ./run.sh serve on the PC, then scan the updated QR."
                    : "Certificate expires " + DateFormat.getDateInstance().format(new Date(until))
                    + (days <= 30 ? " (in " + days + " days). The PC renews it when expired; scan its new QR afterward." : "."));
        }
    }

    private void reloadPairing() {
        try { pairing = store.load(); }
        catch (Exception e) {
            pairing = null;
            permissionNote = "Saved pairing could not be read. Scan the PC’s pairing QR again.";
        }
    }

    private void scan() {
        if (MicService.running) return;
        new IntentIntegrator(this).setCaptureActivity(PairingScanActivity.class)
                .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                .setPrompt("Scan the pairing QR from .local/connect.html on your own PC")
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
            permissionNote = "This isn’t a Phone Mic pairing QR. Use the second QR, labelled Pair, on your PC’s setup page. Existing pairing unchanged.";
            return;
        }
        LinearLayout confirmation = new LinearLayout(this);
        confirmation.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        confirmation.setPadding(padding, 0, padding, 0);
        text(confirmation, "Receiver: " + scanned.serverUrl, 17);
        text(confirmation, "Only continue if you scanned the pairing QR on your own PC. Your microphone will start sending audio to this PC after you confirm and allow permission.", 17);
        Button showFingerprint = new Button(this);
        showFingerprint.setText("Show certificate fingerprint");
        confirmation.addView(showFingerprint);
        TextView fingerprint = text(confirmation, "Certificate SHA-256 (compare in the PC page’s Security details):\n" + scanned.certificateSha256, 14);
        fingerprint.setVisibility(android.view.View.GONE);
        showFingerprint.setOnClickListener(view -> {
            boolean hidden = fingerprint.getVisibility() == android.view.View.GONE;
            fingerprint.setVisibility(hidden ? android.view.View.VISIBLE : android.view.View.GONE);
            showFingerprint.setText(hidden ? "Hide certificate fingerprint" : "Show certificate fingerprint");
        });
        ScrollView confirmationScroll = new ScrollView(this);
        confirmationScroll.addView(confirmation);
        new AlertDialog.Builder(this).setTitle("Pair & start microphone?")
                .setView(confirmationScroll)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Pair & start microphone", (dialog, which) -> {
                    if (MicService.running) return;
                    try {
                        store.save(scanned);
                        pairing = scanned;
                        permissionNote = "";
                        MicService.phase = "stopped";
                        MicService.recovery = "retry";
                        startMic();
                    } catch (Exception e) { permissionNote = "Could not save pairing. Retry scanning; existing pairing unchanged."; }
                }).show();
    }

    private void showSettings() {
        new AlertDialog.Builder(this).setTitle("Settings & help")
                .setItems(new String[]{"Scan a new pairing QR", "Forget receiver", "Connection help & diagnostics", "Android app permissions"},
                        (dialog, which) -> {
                            if (which == 0) {
                                if (MicService.running) permissionNote = "Stop the microphone before changing pairing.";
                                else scan();
                            } else if (which == 1) forgetPairing();
                            else if (which == 2) new AlertDialog.Builder(this).setTitle("Connection help")
                                    .setMessage("Leave ./run.sh serve running on your PC. Both devices need the same Wi-Fi/LAN; guest networks or VPNs can block connections. Run ./run.sh doctor on the PC for guided checks.\n\nSelect CABLE Output in your Windows app. A moving phone meter alone doesn’t prove that app is receiving audio.\n\nNo disk audio recordings or internet uploads. You can stop from the notification while the phone is locked.\n\nTechnical details:\n"
                                            + (MicService.diagnostics.isEmpty() ? "No diagnostic details yet." : MicService.diagnostics))
                                    .setPositiveButton("Close", null).show();
                            else openAppSettings();
                        }).setNegativeButton("Close", null).show();
    }

    private void forgetPairing() {
        if (MicService.running) { permissionNote = "Stop the microphone before forgetting the receiver."; return; }
        new AlertDialog.Builder(this).setTitle("Forget this receiver?")
                .setMessage("Removes pairing from this phone only. It does not revoke other devices. Scan again to reconnect.")
                .setNegativeButton("Cancel", null).setPositiveButton("Forget", (dialog, which) -> {
                    if (MicService.running) return;
                    try { store.clear(); pairing = null; permissionNote = "Pairing removed."; MicService.phase = "stopped"; }
                    catch (Exception e) { permissionNote = "Could not remove pairing. Try again."; }
                }).show();
    }

    private void openAppSettings() {
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
    }

    private void startMic() {
        if (pairing == null || MicService.running) return;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
                && getPreferences(MODE_PRIVATE).getBoolean("microphoneAsked", false)
                && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            pendingStart = false;
            new AlertDialog.Builder(this).setTitle("Allow microphone access")
                    .setMessage("Enable Microphone in Android app permissions, then return and tap Start microphone.")
                    .setPositiveButton("Open settings", (dialog, which) -> openAppSettings())
                    .setNegativeButton("Cancel", null).show();
            return;
        }
        ArrayList<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.RECORD_AUDIO);
            getPreferences(MODE_PRIVATE).edit().putBoolean("microphoneAsked", true).apply();
        }
        if (Build.VERSION.SDK_INT >= 33 && !getPreferences(MODE_PRIVATE).getBoolean("notificationAsked", false)
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
            getPreferences(MODE_PRIVATE).edit().putBoolean("notificationAsked", true).apply();
        }
        if (!missing.isEmpty()) {
            pendingStart = true;
            requestPermissions(missing.toArray(new String[0]), 1);
            return;
        }
        pendingStart = false;
        permissionNote = "";
        try { startForegroundService(new Intent(this, MicService.class)); }
        catch (RuntimeException e) { permissionNote = "Could not start microphone. Check Android app permissions and retry."; }
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code != 1 || !pendingStart) return;
        pendingStart = false;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startMic();
        } else {
            permissionNote = "Microphone access is required. Tap Start to allow access, or open Settings & help → Android app permissions. Notifications are optional.";
        }
        render();
    }

    @Override public void onSaveInstanceState(Bundle saved) {
        saved.putBoolean("pendingStart", pendingStart);
        super.onSaveInstanceState(saved);
    }
    @Override public void onResume() { super.onResume(); reloadPairing(); handler.post(refresh); }
    @Override public void onPause() { handler.removeCallbacks(refresh); super.onPause(); }
}
