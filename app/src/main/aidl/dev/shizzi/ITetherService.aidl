package dev.shizzi;

import android.os.ParcelFileDescriptor;

interface ITetherService {

    // ipv4Only: bring the TUN up without IPv6 so tethering does not hand
    // hotspot clients routed (un-NATed) IPv6 addresses. Used in cybercafe mode,
    // where portal sessions are keyed by the client's IPv4 identity.
    String start(boolean logging, String vpnMode, boolean ipv4Only);

    void setLogging(boolean enabled);

    String stop();

    String getStatus();

    String getTrafficStats();

    void setRequireClientAttribution(boolean required);

    void setGlobalTrafficPolicy(long downloadBps, long uploadBps, long quotaBytes);

    void setDefaultClientTrafficPolicy(
        long downloadBps,
        long uploadBps,
        long quotaBytes,
        boolean blocked
    );

    void setClientTrafficPolicy(
        String ip,
        long downloadBps,
        long uploadBps,
        long quotaBytes,
        boolean blocked
    );

    void resetTrafficStats();

    void setPortalConfig(boolean required, String configJson);

    void clearPortalClaims();

    void revokePortalClient(String ip);

    String runProbes(boolean attemptTethering, int availabilityTimeoutMs);

    void clearLog();

    String checkCompatibility();

    String installTetheringApex(in ParcelFileDescriptor apex);

    String rebootDevice();

    int getContractVersion();
}
