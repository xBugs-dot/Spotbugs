package edu.umd.cs.findbugs.sarif;

import org.junit.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

public class LevelTest {
    @Test
    public void testMapHighestRankToError() {
        assertThat(BugCollectionAnalyser.toLevel(1), is(Result.Level.ERROR));
    }

    @Test
    public void testMapHighRankToError() {
        assertThat(BugCollectionAnalyser.toLevel(9), is(Result.Level.ERROR));
    }

    @Test
    public void testMapLowRankToWarning() {
        assertThat(BugCollectionAnalyser.toLevel(14), is(Result.Level.WARNING));
    }

    @Test
    public void testMapLowestRankToNote() {
        assertThat(BugCollectionAnalyser.toLevel(20), is(Result.Level.NOTE));
    }
}
