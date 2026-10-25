package io.github.jnesew.plainpoddroid.core.chapter;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ChapterTimelineTest {
    private static PodcastChapter chapter(long start,long end,boolean visible) {
        return new PodcastChapter(start,end,"Chapter "+start,null,null,visible);
    }
    @Test public void gapsAndHiddenIntervalsDoNotKeepThePreviousTitle() {
        PodcastChapter first=chapter(0,100,true), hidden=chapter(200,300,false), last=chapter(300,-1,true);
        ChapterTimeline timeline=new ChapterTimeline(new PodcastChapterDocument("1",List.of(first,hidden,last)));
        assertEquals(List.of(first,last),timeline.chapters());
        assertSame(first,timeline.at(99,500)); assertNull(timeline.at(100,500));
        assertNull(timeline.at(250,500)); assertSame(last,timeline.at(300,500));
        assertNull(timeline.at(501,500)); assertNull(timeline.at(-1,500));
    }
    @Test public void exactBoundariesUnknownDurationAndEmptyDocumentsAreSupported() {
        PodcastChapter a=chapter(100,200,true), b=chapter(200,-1,true);
        ChapterTimeline timeline=new ChapterTimeline(new PodcastChapterDocument("1",List.of(a,b)));
        assertNull(timeline.at(0,-1)); assertSame(b,timeline.at(200,-1));
        assertSame(a,timeline.at(199,200)); assertNull(timeline.at(200,200));
        assertNull(new ChapterTimeline(null).at(0,-1));
    }
}
