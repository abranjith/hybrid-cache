package dev.shetty.internal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TagSetTest {
    @Test
    void createEmptyFromNullOrEmpty() {
        assertSame(TagSet.EMPTY, TagSet.create(null));
        assertSame(TagSet.EMPTY, TagSet.create(List.of()));
        assertTrue(TagSet.EMPTY.isEmpty());
        assertEquals(0, TagSet.EMPTY.count());
    }

    @Test
    void singleTagBehavesAsExpected() {
        TagSet tagSet = TagSet.of("alpha");
        assertTrue(tagSet.isSingle());
        assertEquals(1, tagSet.count());
        assertEquals("alpha", tagSet.getSingle());
    }

    @Test
    void multipleTagsAreSortedIgnoringCase() {
        TagSet tagSet = TagSet.of("beta", "Alpha");
        String[] tags = tagSet.getAll();
        assertArrayEquals(new String[] {"Alpha", "beta"}, tags);
    }

    @Test
    void tryFindMatchesExactTag() {
        TagSet tagSet = TagSet.of("alpha", "beta");
        assertEquals("beta", tagSet.tryFind("beta"));
        assertNull(tagSet.tryFind("gamma"));
    }

    @Test
    void wildcardTagIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> TagSet.of("*"));
    }
}
