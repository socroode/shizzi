package dev.shizzi.conso;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private static final String BASE_URL = "http://192.0.2.1/";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        showHome();
    }

    private void showHome() {
        final int pad = dp(20);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(pad, dp(28), pad, dp(28));
        root.setLayoutParams(
            new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        );

        TextView title = new TextView(this);
        title.setText("Shizzi Conso");
        title.setTextSize(30f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(
            title,
            new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        );

        TextView subtitle = new TextView(this);
        subtitle.setText(
            "Votre compte prépayé Shizzi\n" +
            "Connexion, solde, recharge et suivi depuis la page de votre compte."
        );
        subtitle.setTextSize(16f);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        subtitleParams.setMargins(0, dp(12), 0, dp(30));
        root.addView(subtitle, subtitleParams);

        Button login = new Button(this);
        login.setText("OUVRIR LA CONNEXION COMPTE");
        login.setAllCaps(false);
        login.setTextSize(17f);
        login.setOnClickListener(v -> openBrowser(BASE_URL));

        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(56)
        );
        buttonParams.setMargins(0, dp(4), 0, 0);
        root.addView(login, buttonParams);

        TextView footer = new TextView(this);
        footer.setText(
            "La page du compte affiche directement votre forfait actif, " +
            "le solde, le débit, la validité et la recharge."
        );
        footer.setGravity(Gravity.CENTER);
        footer.setTextSize(13f);
        LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        footerParams.setMargins(0, dp(22), 0, 0);
        root.addView(footer, footerParams);

        setContentView(root);
    }

    private void openBrowser(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(intent);
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, "Aucun navigateur disponible.", Toast.LENGTH_LONG).show();
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
