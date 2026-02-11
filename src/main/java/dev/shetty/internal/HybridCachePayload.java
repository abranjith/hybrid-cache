package dev.shetty.internal;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Random;

/**
 * Logic related to the binary payload format used for distributed cache storage.
 * <p>
 * FORMAT (v1):
 * <pre>
 * Fixed-size header:
 * - 2 bytes: sentinel + version
 * - 2 bytes: entropy (random value for collision detection)
 * - 8 bytes: creation time (UTC millis since epoch, little-endian)
 *
 * Dynamic part:
 * - varint: flags (little-endian)
 * - varint: payload size
 * - varint: duration (millis relative to creation time)
 * - varint: tag count
 * - varint + UTF-8: key
 * - (for each tag): varint + UTF-8: tag
 * - (payload-size bytes): payload
 * - 2 bytes: sentinel + version (repeated for reliability)
 * </pre>
 * <p>
 * All bytes must be exhausted after parsing, or it's treated as failure.
 */
public final class HybridCachePayload {
    
    private static final int MAX_VARINT_LENGTH = 10;
    private static final byte SENTINEL_PREFIX = 0x03;
    private static final byte PROTOCOL_VERSION = 0x01;
    private static final short SENTINEL_PAIR = 
        (short) ((PROTOCOL_VERSION << 8) | (SENTINEL_PREFIX & 0xFF));
    
    private static final Random ENTROPY_SOURCE = new Random();
    
    /**
     * Flags that can be included in the payload.
     */
    public enum PayloadFlags {
        NONE(0);
        
        private final int value;
        
        PayloadFlags(int value) {
            this.value = value;
        }
        
        public int getValue() {
            return value;
        }
    }
    
    /**
     * Result of parsing a payload.
     */
    public enum ParseResult {
        SUCCESS,
        FORMAT_NOT_RECOGNIZED,
        INVALID_DATA,
        INVALID_KEY,
        EXPIRED_BY_ENTRY,
        EXPIRED_BY_TAG,
        EXPIRED_BY_WILDCARD,
        PARSE_FAULT
    }
    
    /**
     * Calculates the maximum bytes needed to store a payload.
     * 
     * @param key the cache key
     * @param tags the tags
     * @param payloadSize the size of the payload data
     * @return maximum bytes needed
     */
    public static int getMaxBytes(String key, TagSet tags, int payloadSize) {
        int length = 
            2 + // sentinel + version
            2 + // entropy
            8 + // creation time
            MAX_VARINT_LENGTH + // flags
            MAX_VARINT_LENGTH + // payload size
            MAX_VARINT_LENGTH + // duration
            MAX_VARINT_LENGTH + // tag count
            2 + // trailing sentinel + version
            getMaxStringLength(key.length()) + // key
            payloadSize; // payload itself
        
        // Add space for tags
        if (!tags.isEmpty()) {
            for (String tag : tags) {
                length += getMaxStringLength(tag.length());
            }
        }
        
        return length;
    }
    
    private static int getMaxStringLength(int charCount) {
        // UTF-8 can be up to 4 bytes per character in worst case
        return MAX_VARINT_LENGTH + (charCount * 4);
    }
    
    /**
     * Writes a cache entry to a byte array.
     * 
     * @param destination the target byte array
     * @param key the cache key
     * @param creationTimeMillis creation time in milliseconds since epoch
     * @param duration cache duration
     * @param flags payload flags
     * @param tags the tags
     * @param payload the actual payload data
     * @return the number of bytes written
     */
    public static int write(
            byte[] destination,
            String key,
            long creationTimeMillis,
            Duration duration,
            PayloadFlags flags,
            TagSet tags,
            byte[] payload) {
        
        int pos = 0;
        
        // Write fixed header
        ByteBuffer buffer = ByteBuffer.wrap(destination).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putShort(pos, SENTINEL_PAIR);
        pos += 2;
        
        // Write entropy (random value)
        short entropy = (short) ENTROPY_SOURCE.nextInt(0x10000);
        buffer.putShort(pos, entropy);
        pos += 2;
        
        // Write creation time
        buffer.putLong(pos, creationTimeMillis);
        pos += 8;
        
        // Write variable-length fields
        long durationMillis = duration.toMillis();
        if (durationMillis < 0) {
            durationMillis = 0;
        }
        
        pos = write7BitEncodedLong(destination, pos, flags.getValue());
        pos = write7BitEncodedLong(destination, pos, payload.length);
        pos = write7BitEncodedLong(destination, pos, durationMillis);
        pos = write7BitEncodedLong(destination, pos, tags.count());
        pos = writeString(destination, pos, key);
        
        // Write tags
        for (String tag : tags) {
            pos = writeString(destination, pos, tag);
        }
        
        // Write payload
        System.arraycopy(payload, 0, destination, pos, payload.length);
        pos += payload.length;
        
        // Write trailing sentinel
        buffer.putShort(pos, SENTINEL_PAIR);
        pos += 2;
        
        return pos;
    }
    
    /**
     * Result of a parse operation.
     */
    public static class ParsedPayload {
        public final ParseResult result;
        public final byte[] payload;
        public final Duration remainingTime;
        public final PayloadFlags flags;
        public final short entropy;
        public final TagSet pendingTags;
        public final Exception fault;
        
        public ParsedPayload(ParseResult result, byte[] payload, Duration remainingTime,
                            PayloadFlags flags, short entropy, TagSet pendingTags, Exception fault) {
            this.result = result;
            this.payload = payload;
            this.remainingTime = remainingTime;
            this.flags = flags;
            this.entropy = entropy;
            this.pendingTags = pendingTags;
            this.fault = fault;
        }
        
        public static ParsedPayload failure(ParseResult result) {
            return new ParsedPayload(result, null, Duration.ZERO, PayloadFlags.NONE, 
                                    (short) 0, TagSet.EMPTY, null);
        }
        
        public static ParsedPayload fault(Exception ex) {
            return new ParsedPayload(ParseResult.PARSE_FAULT, null, Duration.ZERO,
                                    PayloadFlags.NONE, (short) 0, TagSet.EMPTY, ex);
        }
    }
    
    /**
     * Attempts to parse a payload from a byte array.
     * <p>
     * Note: This simplified version doesn't check tag expiration yet.
     * That requires integration with DefaultHybridCache's tag invalidation system.
     * 
     * @param source the source byte array
     * @param key the expected cache key
     * @param knownTags known tags for this entry
     * @param currentTimeMillis current time in milliseconds
     * @return parse result
     */
    public static ParsedPayload tryParse(
            byte[] source,
            String key,
            TagSet knownTags,
            long currentTimeMillis) {
        
        if (source == null || source.length < 19) {
            return ParsedPayload.failure(ParseResult.FORMAT_NOT_RECOGNIZED);
        }
        
        try {
            ByteBuffer buffer = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN);
            int pos = 0;
            
            // Read and validate sentinel
            short sentinel = buffer.getShort(pos);
            if (sentinel != SENTINEL_PAIR) {
                return ParsedPayload.failure(ParseResult.FORMAT_NOT_RECOGNIZED);
            }
            pos += 2;
            
            // Read entropy
            short entropy = buffer.getShort(pos);
            pos += 2;
            
            // Read creation time
            long creationTime = buffer.getLong(pos);
            pos += 8;
            
            // TODO: Check wildcard expiration when tag invalidation is implemented
            // if (cache.isWildcardExpired(creationTime)) {
            //     return ParsedPayload.failure(ParseResult.EXPIRED_BY_WILDCARD);
            // }
            
            // Read flags
            ReadResult<Long> flagsResult = read7BitEncodedLong(source, pos);
            if (!flagsResult.success) {
                return ParsedPayload.failure(ParseResult.INVALID_DATA);
            }
            PayloadFlags flags = PayloadFlags.NONE; // TODO: convert from int
            pos = flagsResult.newPosition;
            
            // Read payload length
            ReadResult<Long> payloadLengthResult = read7BitEncodedLong(source, pos);
            if (!payloadLengthResult.success || payloadLengthResult.value > Integer.MAX_VALUE) {
                return ParsedPayload.failure(ParseResult.INVALID_DATA);
            }
            int payloadLength = payloadLengthResult.value.intValue();
            pos = payloadLengthResult.newPosition;
            
            // Read duration
            ReadResult<Long> durationResult = read7BitEncodedLong(source, pos);
            if (!durationResult.success) {
                return ParsedPayload.failure(ParseResult.INVALID_DATA);
            }
            long durationMillis = durationResult.value;
            pos = durationResult.newPosition;
            
            // Check expiration
            long remainingMillis = (creationTime + durationMillis) - currentTimeMillis;
            if (remainingMillis <= 0) {
                return ParsedPayload.failure(ParseResult.EXPIRED_BY_ENTRY);
            }
            Duration remainingTime = Duration.ofMillis(remainingMillis);
            
            // Read tag count
            ReadResult<Long> tagCountResult = read7BitEncodedLong(source, pos);
            if (!tagCountResult.success || tagCountResult.value > Integer.MAX_VALUE) {
                return ParsedPayload.failure(ParseResult.INVALID_DATA);
            }
            int tagCount = tagCountResult.value.intValue();
            pos = tagCountResult.newPosition;
            
            // Read and validate key
            ReadResult<String> keyResult = readString(source, pos);
            if (!keyResult.success) {
                return ParsedPayload.failure(ParseResult.INVALID_DATA);
            }
            if (!key.equals(keyResult.value)) {
                return ParsedPayload.failure(ParseResult.INVALID_KEY);
            }
            pos = keyResult.newPosition;
            
            // Read tags
            // TODO: Implement tag expiration checking when DefaultHybridCache is ready
            for (int i = 0; i < tagCount; i++) {
                ReadResult<String> tagResult = readString(source, pos);
                if (!tagResult.success) {
                    return ParsedPayload.failure(ParseResult.INVALID_DATA);
                }
                pos = tagResult.newPosition;
                // String tag = tagResult.value;
                // TODO: Check tag expiration
            }
            
            // Validate we have enough bytes for payload + trailing sentinel
            if (source.length - pos != payloadLength + 2) {
                return ParsedPayload.failure(ParseResult.INVALID_DATA);
            }
            
            // Validate trailing sentinel
            short trailingSentinel = buffer.getShort(pos + payloadLength);
            if (trailingSentinel != SENTINEL_PAIR) {
                return ParsedPayload.failure(ParseResult.INVALID_DATA);
            }
            
            // Extract payload
            byte[] payload = new byte[payloadLength];
            System.arraycopy(source, pos, payload, 0, payloadLength);
            
            return new ParsedPayload(ParseResult.SUCCESS, payload, remainingTime,
                                    flags, entropy, TagSet.EMPTY, null);
            
        } catch (Exception ex) {
            return ParsedPayload.fault(ex);
        }
    }
    
    /**
     * Writes a 64-bit integer using 7-bit encoding.
     */
    private static int write7BitEncodedLong(byte[] target, int offset, long value) {
        // Write out a long 7 bits at a time. The high bit of the byte,
        // when on, tells reader to continue reading more bytes.
        while (value > 0x7FL) {
            target[offset++] = (byte) ((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        target[offset++] = (byte) value;
        return offset;
    }
    
    /**
     * Writes a string with varint length prefix + UTF-8 bytes.
     */
    private static int writeString(byte[] target, int offset, String value) {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        offset = write7BitEncodedLong(target, offset, utf8.length);
        System.arraycopy(utf8, 0, target, offset, utf8.length);
        return offset + utf8.length;
    }
    
    /**
     * Result of reading a value.
     */
    private static class ReadResult<T> {
        final boolean success;
        final T value;
        final int newPosition;
        
        ReadResult(boolean success, T value, int newPosition) {
            this.success = success;
            this.value = value;
            this.newPosition = newPosition;
        }
        
        static <T> ReadResult<T> failure() {
            return new ReadResult<>(false, null, -1);
        }
    }
    
    /**
     * Reads a 64-bit integer using 7-bit encoding.
     */
    private static ReadResult<Long> read7BitEncodedLong(byte[] buffer, int offset) {
        try {
            long result = 0;
            int shift = 0;
            int index = offset;
            
            for (int i = 0; i < 9; i++) {
                if (index >= buffer.length) {
                    return ReadResult.failure();
                }
                byte b = buffer[index++];
                result |= ((long) (b & 0x7F)) << shift;
                
                if ((b & 0x80) == 0) {
                    return new ReadResult<>(true, result, index);
                }
                shift += 7;
            }
            
            // Read 10th byte - must fit in 1 bit
            if (index >= buffer.length) {
                return ReadResult.failure();
            }
            byte b = buffer[index++];
            if (b > 0b1) {
                return ReadResult.failure();
            }
            result |= ((long) b) << (9 * 7);
            return new ReadResult<>(true, result, index);
            
        } catch (Exception e) {
            return ReadResult.failure();
        }
    }
    
    /**
     * Reads a UTF-8 string with varint length prefix.
     */
    private static ReadResult<String> readString(byte[] buffer, int offset) {
        ReadResult<Long> lengthResult = read7BitEncodedLong(buffer, offset);
        if (!lengthResult.success || lengthResult.value > Integer.MAX_VALUE) {
            return ReadResult.failure();
        }
        
        int length = lengthResult.value.intValue();
        int pos = lengthResult.newPosition;
        
        if (pos + length > buffer.length) {
            return ReadResult.failure();
        }
        
        String value = new String(buffer, pos, length, StandardCharsets.UTF_8);
        return new ReadResult<>(true, value, pos + length);
    }
    
    private HybridCachePayload() {
        // Utility class
    }
}
