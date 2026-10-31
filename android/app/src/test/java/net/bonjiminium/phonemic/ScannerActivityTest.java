package net.bonjiminium.phonemic;

import android.content.Intent;
import android.view.WindowManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import static org.junit.Assert.*;

/** Exercise actual Android resource inflation/lifecycle, unlike plain JVM tests. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 36)
public class ScannerActivityTest {
    @Test public void scannerLaunchesWithRealResourcesAndCanCancelWithoutCameraPermission() {
        Intent intent = new Intent(org.robolectric.RuntimeEnvironment.getApplication(), PairingScanActivity.class)
                .setAction("com.google.zxing.client.android.SCAN")
                .putExtra("SCAN_FORMATS", "QR_CODE").putExtra("BEEP_ENABLED", false)
                .putExtra("BARCODE_IMAGE_ENABLED", false).putExtra("SCAN_ORIENTATION_LOCKED", false);
        try (ActivityController<PairingScanActivity> controller = Robolectric.buildActivity(PairingScanActivity.class, intent)) {
            PairingScanActivity activity = controller.setup().get();
            assertTrue((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
            assertNotNull(activity.findViewById(com.google.zxing.client.android.R.id.zxing_barcode_scanner));
            activity.finish();
        }
    }
}
