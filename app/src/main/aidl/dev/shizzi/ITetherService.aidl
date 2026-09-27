package dev.shizzi;

import android.os.ParcelFileDescriptor;

interface ITetherService {

    String start(boolean logging, String vpnMode);

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
