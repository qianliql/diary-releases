package io.github.qianliql.diary;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.provider.MediaStore;
import java.io.OutputStream;

/** Separate UID and pure platform APIs: no dependency on the target APK's class loader. */
public class FixtureCameraActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Uri output = getIntent().getParcelableExtra(MediaStore.EXTRA_OUTPUT);
        ResultReceiver report = getIntent().getParcelableExtra("report");
        int result = RESULT_OK;
        Bundle payload = new Bundle();
        payload.putInt("writerUid", android.os.Process.myUid());
        try (OutputStream stream = getContentResolver().openOutputStream(output)) {
            Bitmap bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(Color.rgb(220, 150, 90));
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)) throw new java.io.IOException("JPEG write failed");
            bitmap.recycle();
        } catch (Exception error) {
            result = RESULT_CANCELED; payload.putString("error", error.getMessage());
        }
        report.send(result, payload); setResult(result); finish();
    }
}
