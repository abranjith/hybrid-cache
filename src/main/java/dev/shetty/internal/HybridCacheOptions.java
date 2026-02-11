package dev.shetty.internal;

/**
 * Options for configuring the default HybridCache implementation.
 */
public class HybridCacheOptions {
    private HybridCacheEntryOptions defaultEntryOptions;
    private boolean disableCompression;
    private long maximumPayloadBytes = 1 << 20; // 1 MiB
    private int maximumKeyLength = 1024;
    private boolean reportTagMetrics;

    public HybridCacheEntryOptions getDefaultEntryOptions() {
        return defaultEntryOptions;
    }

    public void setDefaultEntryOptions(HybridCacheEntryOptions defaultEntryOptions) {
        this.defaultEntryOptions = defaultEntryOptions;
    }

    public boolean isDisableCompression() {
        return disableCompression;
    }

    public void setDisableCompression(boolean disableCompression) {
        this.disableCompression = disableCompression;
    }

    public long getMaximumPayloadBytes() {
        return maximumPayloadBytes;
    }

    public void setMaximumPayloadBytes(long maximumPayloadBytes) {
        this.maximumPayloadBytes = maximumPayloadBytes;
    }

    public int getMaximumKeyLength() {
        return maximumKeyLength;
    }

    public void setMaximumKeyLength(int maximumKeyLength) {
        this.maximumKeyLength = maximumKeyLength;
    }

    public boolean isReportTagMetrics() {
        return reportTagMetrics;
    }

    public void setReportTagMetrics(boolean reportTagMetrics) {
        this.reportTagMetrics = reportTagMetrics;
    }
}