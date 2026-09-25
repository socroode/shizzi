package dev.shizzi.conso;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private static final String STATUS_URL = "http://192.0.2.1/status";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        openStatusPage();
    }

    private void openStatusPage() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(STATUS_URL));
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(intent);
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, "Aucun navigateur disponible.", Toast.LENGTH_LONG).show();
        } finally {
            finish();
        }
    }
}
