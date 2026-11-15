package net.bonjiminium.phonemic;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public class MainActivityUxTest {
    @Before public void reset() throws Exception {
        new PairingStore(RuntimeEnvironment.getApplication().getNoBackupFilesDir().toPath()).clear();
        MicService.running = false;
        MicService.phase = "stopped";
        MicService.recovery = "retry";
        MicService.lastAcknowledgedAt = 0;
        RuntimeEnvironment.getApplication().getSharedPreferences("MainActivity", 0).edit().clear().commit();
    }
    private View find(View root, String text) {
        if (root instanceof TextView && ((TextView) root).getText().toString().contains(text)) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View result = find(group.getChildAt(i), text);
                if (result != null) return result;
            }
        }
        return null;
    }
    private View find(Activity activity, String text) { return find(activity.getWindow().getDecorView(), text); }
    private void scanResult(MainActivity activity) throws Exception {
        Intent data = new Intent().putExtra("SCAN_RESULT", PairingConfigTest.example("https://pc.local").serialize())
                .putExtra("SCAN_RESULT_FORMAT", "QR_CODE");
        activity.onActivityResult(49374, Activity.RESULT_OK, data);
    }
    @Test public void unpairedShowsOneObviousNextActionAndDoesNotStart() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.setup().get();
            assertNotNull(find(activity, "Scan pairing QR"));
            assertNotNull(find(activity, "./run.sh serve"));
            assertNull(Shadows.shadowOf(activity).getNextStartedService());
        }
    }
    @Test public void scanAloneDoesNotRecordAndConfirmContinuesAfterPermission() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.setup().get();
            scanResult(activity);
            assertNull(Shadows.shadowOf(activity).getNextStartedService());
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString().contains("start microphone"));
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNotNull(new PairingStore(activity.getNoBackupFilesDir().toPath()).load());
            assertNull(Shadows.shadowOf(activity).getNextStartedService());
            Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO);
            activity.onRequestPermissionsResult(1, new String[]{Manifest.permission.RECORD_AUDIO}, new int[]{PackageManager.PERMISSION_GRANTED});
            Intent started = Shadows.shadowOf(activity).getNextStartedService();
            assertNotNull("No second Start tap required", started);
            assertEquals(MicService.class.getName(), started.getComponent().getClassName());
        }
    }
    @Test public void deniedMicrophoneNeverStartsCaptureAndOffersRecovery() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.setup().get();
            scanResult(activity);
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            activity.onRequestPermissionsResult(1, new String[]{Manifest.permission.RECORD_AUDIO}, new int[]{PackageManager.PERMISSION_DENIED});
            assertNull(Shadows.shadowOf(activity).getNextStartedService());
            assertNotNull(find(activity, "Microphone access is required"));
        }
    }
    @Test public void alreadyGrantedPermissionStartsImmediatelyAfterConfirmation() throws Exception {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS);
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.setup().get();
            scanResult(activity);
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNotNull(Shadows.shadowOf(activity).getNextStartedService());
        }
    }

    @Test public void invalidScanAndCancelledReplacementPreserveExistingPairing() throws Exception {
        PairingStore store = new PairingStore(RuntimeEnvironment.getApplication().getNoBackupFilesDir().toPath());
        store.save(PairingConfigTest.example("https://old-pc.local"));
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.setup().get();
            activity.onActivityResult(49374, Activity.RESULT_OK, new Intent()
                    .putExtra("SCAN_RESULT", "https://pc.local/android.apk?token=sensitive")
                    .putExtra("SCAN_RESULT_FORMAT", "QR_CODE"));
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200));
            assertNotNull(find(activity, "Use the second QR"));
            assertEquals("https://old-pc.local", store.load().serverUrl);
            scanResult(activity);
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("https://old-pc.local", store.load().serverUrl);
            assertNull(Shadows.shadowOf(activity).getNextStartedService());
        }
    }

    @Test public void cancellingPairingDoesNotSaveOrRecord() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.setup().get();
            scanResult(activity);
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertNull(new PairingStore(activity.getNoBackupFilesDir().toPath()).load());
            assertNull(Shadows.shadowOf(activity).getNextStartedService());
        }
    }
    @Test public void expiredQrAndFailureHaveClearRecovery() throws Exception {
        PairingConfig config = PairingConfigTest.example("https://pc.local").withVerifiedExpiry(1);
        new PairingStore(RuntimeEnvironment.getApplication().getNoBackupFilesDir().toPath()).save(config);
        MicService.phase = "error";
        MicService.recovery = "pair";
        MicService.status = "Certificate changed. Scan current QR.";
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.setup().get();
            assertNotNull(find(activity, "Scan updated pairing QR"));
            assertNotNull(find(activity, "Saved certificate expired"));
        }
    }
    @Test public void capturingIsNotClaimedAsReceiverAcknowledgement() throws Exception {
        new PairingStore(RuntimeEnvironment.getApplication().getNoBackupFilesDir().toPath())
                .save(PairingConfigTest.example("https://pc.local"));
        MicService.running = true;
        MicService.phase = "streaming";
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class)) {
            MainActivity activity = controller.setup().get();
            assertNotNull(find(activity, "waiting for PC"));
            assertNotNull(find(activity, "Stop microphone"));
            MicService.lastAcknowledgedAt = System.currentTimeMillis();
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200));
            assertNotNull(find(activity, "PC receiving audio"));
        }
    }
}
