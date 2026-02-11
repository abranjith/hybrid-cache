package dev.shetty.internal;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

public class HybridCachePayloadTest {
    @Test
    void roundTripPayloadPreservesData() {
        String key = "key-1";
        TagSet tags = TagSet.of("beta", "Alpha");
        byte[] payload = "value".getBytes(StandardCharsets.UTF_8);
        long creationTime = System.currentTimeMillis();

        int maxBytes = HybridCachePayload.getMaxBytes(key, tags, payload.length);
        byte[] buffer = new byte[maxBytes];

        int written = HybridCachePayload.write(
            buffer,
            key,
            creationTime,
            Duration.ofSeconds(30),
            HybridCachePayload.PayloadFlags.NONE,
            tags,
            payload
        );

        byte[] serialized = Arrays.copyOf(buffer, written);
        HybridCachePayload.ParsedPayload parsed = HybridCachePayload.tryParse(
            serialized,
            key,
            TagSet.EMPTY,
            creationTime
        );

        assertEquals(HybridCachePayload.ParseResult.SUCCESS, parsed.result);
        assertArrayEquals(payload, parsed.payload);
        assertTrue(parsed.remainingTime.toMillis() > 0);
    }

    @Test
    void expiredPayloadIsRejected() {
        String key = "expired";
        TagSet tags = TagSet.EMPTY;
        byte[] payload = "value".getBytes(StandardCharsets.UTF_8);

        int maxBytes = HybridCachePayload.getMaxBytes(key, tags, payload.length);
        byte[] buffer = new byte[maxBytes];

        int written = HybridCachePayload.write(
            buffer,
            key,
            0L,
            Duration.ofMillis(1),
            HybridCachePayload.PayloadFlags.NONE,
            tags,
            payload
        );

        byte[] serialized = Arrays.copyOf(buffer, written);
        HybridCachePayload.ParsedPayload parsed = HybridCachePayload.tryParse(
            serialized,
            key,
            TagSet.EMPTY,
            10L
        );

        assertEquals(HybridCachePayload.ParseResult.EXPIRED_BY_ENTRY, parsed.result);
    }
}
