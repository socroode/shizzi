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
    private static final String STATUS_URL = BASE_URL + "status";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        showHome();
    }

    private void showHome() {
        final int pad = dp(20);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(pad, dp(28), pad, pad);
        root.setLayoutParams(
            new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        );

        TextView title = new TextView(this);
        title.setText("Shizzi Conso");
        title.setTextSize(28f);
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
            "La connexion compte + PIN se fait normalement dans la fenêtre Wi-Fi automatique."
        );
        subtitle.setTextSize(16f);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        subtitleParams.setMargins(0, dp(12), 0, dp(30));
        root.addView(subtitle, subtitleParams);

        Button status = new Button(this);
        status.setText("MA CONSOMMATION");
        status.setAllCaps(false);
        status.setTextSize(17f);
        status.setOnClickListener(v -> openBrowser(STATUS_URL));
        root.addView(status, buttonParams());

        TextView statusHelp = new TextView(this);
        statusHelp.setText("Solde, validité, débit et suivi en temps réel");
        statusHelp.setGravity(Gravity.CENTER);
        statusHelp.setTextSize(14f);
        LinearLayout.LayoutParams helpParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        helpParams.setMargins(0, dp(4), 0, dp(18));
        root.addView(statusHelp, helpParams);

        Button recharge = new Button(this);
        recharge.setText("RECHARGER MON COMPTE");
        recharge.setAllCaps(false);
        recharge.setTextSize(17f);
        recharge.setOnClickListener(v -> openBrowser(BASE_URL));
        root.addView(recharge, buttonParams());

        TextView rechargeHelp = new TextView(this);
        rechargeHelp.setText(
            "Ouvre la page locale Shizzi pour saisir un coupon de recharge. " +
            "Même avec 0 Mo, le compte reste accessible sur le réseau local."
        );
        rechargeHelp.setGravity(Gravity.CENTER);
        rechargeHelp.setTextSize(14f);
        LinearLayout.LayoutParams rechargeHelpParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        rechargeHelpParams.setMargins(0, dp(4), 0, dp(18));
        root.addView(rechargeHelp, rechargeHelpParams);

        Button login = new Button(this);
        login.setText("OUVRIR LA CONNEXION COMPTE");
        login.setAllCaps(false);
        login.setTextSize(15f);
        login.setOnClickListener(v -> openBrowser(BASE_URL));
        root.addView(login, buttonParams());

        TextView footer = new TextView(this);
        footer.setText(
            "Compte permanent · coupons à usage unique · aucune adresse à saisir"
        );
        footer.setGravity(Gravity.CENTER);
        footer.setTextSize(13f);
        LinearLayout.LayoutParams footerParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        footerParams.setMargins(0, dp(26), 0, 0);
        root.addView(footer, footerParams);

        setContentView(root);
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(54)
        );
        params.setMargins(0, dp(4), 0, 0);
        return params;
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
