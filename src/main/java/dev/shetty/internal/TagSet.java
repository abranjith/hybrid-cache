package dev.shetty.internal;

import java.util.*;

/**
 * Represents zero (null), one (string) or more (string[]) tags, avoiding the additional
 * array overhead when necessary.
 * <p>
 * Optimizes for the common cases:
 * - Empty (no tags): singleton EMPTY instance
 * - Single tag: stores String directly
 * - Multiple tags: stores sorted String[]
 */
public final class TagSet implements Iterable<String> {
    
    /**
     * Singleton for empty tag set.
     */
    public static final TagSet EMPTY = new TagSet((String) null);

    /**
     * Reserved wildcard tag that cannot be used in tag sets.
     */
    static final String WILDCARD_TAG = "*";

    /**
     * The underlying storage: null (empty), String (single), or String[] (multiple).
     */
    private final Object tagOrTags;

    /**
     * Private constructor for single tag.
     */
    private TagSet(String tag) {
        if (tag != null) {
            validate(tag);
        }
        this.tagOrTags = tag;
    }

    /**
     * Private constructor for multiple tags.
     * Tags array must be non-empty and will be sorted.
     */
    private TagSet(String[] tags) {
        if (tags == null || tags.length == 0) {
            throw new IllegalArgumentException("Tags array cannot be empty for multi-tag constructor");
        }
        
        for (String tag : tags) {
            validate(tag);
        }
        
        // Sort for consistent ordering
        Arrays.sort(tags, String::compareToIgnoreCase);
        this.tagOrTags = tags;
    }

    /**
     * Creates a TagSet from a collection of tags.
     * 
     * @param tags the collection of tags, or null for empty
     * @return a TagSet instance (EMPTY, single tag, or multiple tags)
     */
    public static TagSet create(Collection<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return EMPTY;
        }

        int count = tags.size();
        if (count == 1) {
            String singleTag = tags instanceof List<String> list 
                ? list.get(0) 
                : tags.iterator().next();
            return new TagSet(singleTag);
        }

        // Multiple tags - create defensive copy
        String[] array = tags.toArray(new String[0]);
        return new TagSet(array);
    }

    /**
     * Creates a TagSet from a single tag.
     * 
     * @param tag the tag
     * @return a TagSet containing the single tag
     */
    public static TagSet of(String tag) {
        if (tag == null) {
            return EMPTY;
        }
        return new TagSet(tag);
    }

    /**
     * Creates a TagSet from multiple tags.
     * 
     * @param tags the tags
     * @return a TagSet containing the tags
     */
    public static TagSet of(String... tags) {
        if (tags == null || tags.length == 0) {
            return EMPTY;
        }
        if (tags.length == 1) {
            return new TagSet(tags[0]);
        }
        return new TagSet(tags.clone());
    }

    /**
     * Returns true if this set contains no tags.
     */
    public boolean isEmpty() {
        return tagOrTags == null;
    }

    /**
     * Returns the number of tags in this set.
     */
    public int count() {
        return switch (tagOrTags) {
            case null -> 0;
            case String _ -> 1;
            case String[] arr -> arr.length;
            default -> 0;
        };
    }

    /**
     * Returns true if this set contains exactly one tag.
     */
    public boolean isSingle() {
        return tagOrTags instanceof String;
    }

    /**
     * Returns true if this set contains multiple tags (stored as array).
     */
    public boolean isArray() {
        return tagOrTags instanceof String[];
    }

    /**
     * Gets the single tag. Only valid when isSingle() is true.
     * 
     * @return the single tag
     * @throws IllegalStateException if this is not a single-tag set
     */
    public String getSingle() {
        if (!(tagOrTags instanceof String s)) {
            throw new IllegalStateException("Not a single-tag set");
        }
        return s;
    }

    /**
     * Gets all tags as an array. Creates a defensive copy for multi-tag sets.
     * 
     * @return array of all tags
     */
    public String[] getAll() {
        return switch (tagOrTags) {
            case null -> new String[0];
            case String s -> new String[] { s };
            case String[] arr -> arr.clone();
            default -> new String[0];
        };
    }

    /**
     * Gets the tag at the specified index.
     * 
     * @param index the index
     * @return the tag at the index
     * @throws IndexOutOfBoundsException if index is invalid
     */
    public String get(int index) {
        return switch (tagOrTags) {
            case String s when index == 0 -> s;
            case String[] arr -> arr[index];
            case null -> throw new IndexOutOfBoundsException("Empty tag set");
            default -> throw new IndexOutOfBoundsException("Invalid index: " + index);
        };
    }

    /**
     * Tries to find a tag matching the given string.
     * 
     * @param target the string to search for
     * @return the matching tag, or null if not found
     */
    public String tryFind(String target) {
        if (target == null) {
            return null;
        }

        return switch (tagOrTags) {
            case String s when s.equals(target) -> s;
            case String[] arr -> {
                for (String tag : arr) {
                    if (tag.equals(target)) {
                        yield tag;
                    }
                }
                yield null;
            }
            default -> null;
        };
    }

    /**
     * Returns the maximum length of any tag in this set.
     */
    public int maxLength() {
        return switch (tagOrTags) {
            case String s -> s.length();
            case String[] arr -> {
                int max = 0;
                for (String tag : arr) {
                    max = Math.max(max, tag.length());
                }
                yield max;
            }
            default -> 0;
        };
    }

    /**
     * Copies all tags to the target array.
     * 
     * @param target the target array (must be large enough)
     */
    public void copyTo(String[] target) {
        switch (tagOrTags) {
            case String s -> target[0] = s;
            case String[] arr -> System.arraycopy(arr, 0, target, 0, arr.length);
            case null -> {} // nothing to copy
            default -> {} // unknown type, do nothing
        }
    }

    @Override
    public Iterator<String> iterator() {
        return switch (tagOrTags) {
            case null -> Collections.emptyIterator();
            case String s -> Collections.singleton(s).iterator();
            case String[] arr -> Arrays.asList(arr).iterator();
            case Object _ -> Collections.emptyIterator();
        };
    }

    @Override
    public String toString() {
        return switch (tagOrTags) {
            case String s -> s;
            case String[] arr -> String.join(", ", arr);
            default -> "(no tags)";
        };
    }

    /**
     * Validates a tag string.
     * 
     * @param tag the tag to validate
     * @throws IllegalArgumentException if tag is invalid
     */
    private static void validate(String tag) {
        if (tag == null || tag.isBlank()) {
            throw new IllegalArgumentException("Tags cannot be null or empty");
        }
        if (WILDCARD_TAG.equals(tag)) {
            throw new IllegalArgumentException(
                "The tag '" + WILDCARD_TAG + "' is reserved and cannot be used");
        }
    }

    // Note: equals() and hashCode() are intentionally not overridden.
    // TagSet is meant to be used by value, not as a hash key.
}
