package dev.shetty.internal;

public class ArraySegment<T> {
    /**
     * Represents a view of a sub-array within an existing array.
     * This class allows you to access a portion of an array without copying it.
     *
     * @param <T> The type of elements in the array.
     */
    private final T[] array;
    private final int startIndex;
    private final int length;

    public ArraySegment(T[] array, int startIndex, int length) {
        if (startIndex < 0 || startIndex >= array.length) {
            throw new IllegalArgumentException("Invalid start index.");
        }
        if (length <= 0 || length > array.length - startIndex) {
            throw new IllegalArgumentException("Invalid length.");
        }
        this.array = array;
        this.startIndex = startIndex;
        this.length = length;
    }

    public T get(int index) {
        if (startIndex + index >= array.length || index < 0) {
            throw new IndexOutOfBoundsException("Index out of bounds for this span.");
        }
        return array[startIndex + index];
    }

    public T[] getArray() {
        return array;
    }

    public int size() {
        return length;
    }
}
