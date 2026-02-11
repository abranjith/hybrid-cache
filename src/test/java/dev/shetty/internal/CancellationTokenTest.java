package dev.shetty.internal;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;

public class CancellationTokenTest {
    @Test
    void cancelMarksTokenAndThrows() {
        CancellationToken token = new CancellationToken();
        token.cancel();
        assertTrue(token.isCancelled());
        assertThrows(CancellationException.class, token::throwIfCancelled);
    }

    @Test
    void noneTokenDoesNotThrow() {
        assertFalse(CancellationToken.NONE.isCancelled());
        assertDoesNotThrow(CancellationToken.NONE::throwIfCancelled);
    }
}
