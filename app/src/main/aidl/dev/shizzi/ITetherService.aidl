package dev.shizzi;

import android.os.ParcelFileDescriptor;

interface ITetherService {

    String start(boolean logging, String vpnMode, String hotspotBand, String managerConfigJson);

    void setLogging(boolean enabled);

    String stop();

    String getStatus();

    String getTrafficStats();

    void setGlobalTrafficPolicy(long downloadBps, long uploadBps, long quotaBytes);

    void setClientTrafficPolicy(
        String ip,
        long downloadBps,
        long uploadBps,
        long quotaBytes,
        boolean blocked
    );

    void resetTrafficStats();

    String runProbes(boolean attemptTethering, int availabilityTimeoutMs);

    void clearLog();

    String checkCompatibility();

    String installTetheringApex(in ParcelFileDescriptor apex);

    String rebootDevice();

    int getContractVersion();
}
