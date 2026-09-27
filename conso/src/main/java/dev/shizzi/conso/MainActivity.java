package dev.shizzi.conso;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

public final class MainActivity extends Activity {
    private static final String BASE_URL = "http://192.0.2.1/";
    private static final int REGISTRATION_PORT = 49200;
    private static final SecureRandom RANDOM = new SecureRandom();

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
        login.setOnClickListener(v -> identifyAndOpenPortal());

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

    private volatile boolean identifying;

    private void identifyAndOpenPortal() {
        if (identifying) return;
        identifying = true;
        final String token = newDeviceToken();

        new Thread(() -> {
            final boolean identified = registerOnShizziWifi(token);
            runOnUiThread(() -> {
                identifying = false;
                if (!identified) {
                    Toast.makeText(
                        this,
                        "Impossible d'identifier ce téléphone sur le Wi-Fi Shizzi. Réessayez.",
                        Toast.LENGTH_LONG
                    ).show();
                    return;
                }
                openBrowser(BASE_URL + "?device=" + Uri.encode(token));
            });
        }, "shizzi-conso-identify").start();
    }

    private boolean registerOnShizziWifi(String token) {
        ConnectivityManager manager =
            (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) return false;

        Network wifi = findWifiNetwork(manager);
        if (wifi == null) return false;

        InetAddress gateway = findWifiGateway(manager, wifi);
        if (gateway == null) return false;

        byte[] payload = token.getBytes(StandardCharsets.UTF_8);
        for (int attempt = 0; attempt < 4; attempt++) {
            try (DatagramSocket socket = new DatagramSocket()) {
                wifi.bindSocket(socket);
                socket.setSoTimeout(3500);

                DatagramPacket request = new DatagramPacket(
                    payload,
                    payload.length,
                    gateway,
                    REGISTRATION_PORT
                );
                socket.send(request);

                byte[] responseBytes = new byte[64];
                DatagramPacket response = new DatagramPacket(
                    responseBytes,
                    responseBytes.length
                );
                socket.receive(response);

                String reply = new String(
                    response.getData(),
                    response.getOffset(),
                    response.getLength(),
                    StandardCharsets.UTF_8
                );
                if ("SHIZZI-DEVICE-OK".equals(reply)) {
                    return true;
                }
            } catch (Exception ignored) {
                // The hotspot host can need a moment to expose the new client
                // in its tethering/neighbor tables. Retry locally without ever
                // sending the identification request through the shared TUN.
            }

            try {
                Thread.sleep(300L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private Network findWifiNetwork(ConnectivityManager manager) {
        for (Network network : manager.getAllNetworks()) {
            NetworkCapabilities caps = manager.getNetworkCapabilities(network);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return network;
            }
        }
        return null;
    }

    private InetAddress findWifiGateway(ConnectivityManager manager, Network wifi) {
        LinkProperties properties = manager.getLinkProperties(wifi);
        if (properties == null) return null;

        for (RouteInfo route : properties.getRoutes()) {
            InetAddress gateway = route.getGateway();
            if (route.isDefaultRoute() && gateway instanceof Inet4Address) {
                return gateway;
            }
        }

        for (RouteInfo route : properties.getRoutes()) {
            InetAddress gateway = route.getGateway();
            if (gateway instanceof Inet4Address) {
                return gateway;
            }
        }
        return null;
    }

    private String newDeviceToken() {
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        StringBuilder out = new StringBuilder(raw.length * 2);
        for (byte value : raw) {
            out.append(String.format("%02x", value & 0xff));
        }
        return out.toString();
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
