package dev.shetty.abstractions;

import dev.shetty.internal.ArraySegment;

public interface IBufferWriter<T> {

    void advance(int count);

    ArraySegment<T> getSpan(int sizeHint);
}
