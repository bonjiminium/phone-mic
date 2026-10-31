package net.bonjiminium.phonemic;

import android.os.Bundle;
import android.view.WindowManager;
import com.journeyapps.barcodescanner.CaptureActivity;

/** Bundled, offline QR scanner. Prevent QR credentials appearing in screenshots/recents. */
public final class PairingScanActivity extends CaptureActivity {
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }
}
