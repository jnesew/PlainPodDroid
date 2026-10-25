package io.github.jnesew.plainpoddroid.core.chapter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
/** Visible chapter lookup shared by scrub previews and the chapter sheet. */
public final class ChapterTimeline {
    private final List<PodcastChapter> chapters;
    private final List<PodcastChapter> all;
    public ChapterTimeline(PodcastChapterDocument document) {
        all=document==null?Collections.emptyList():document.getChapters();
        List<PodcastChapter> visible=new ArrayList<>();
        if(document!=null)for(PodcastChapter chapter:document.getChapters())if(chapter.isVisible())visible.add(chapter);
        chapters=Collections.unmodifiableList(visible);
    }
    public List<PodcastChapter> chapters(){return chapters;}
    public PodcastChapter at(long position,long duration){
        if(position<0 || duration>0 && position>duration)return null;
        int low=0,high=all.size()-1,result=-1;
        while(low<=high){int mid=(low+high)>>>1;
            if(all.get(mid).getStartTimeMillis()<=position){result=mid;low=mid+1;}else high=mid-1;
        }
        if(result<0)return null;
        PodcastChapter chapter=all.get(result);
        return !chapter.isVisible() || chapter.getEndTimeMillis()>=0 && position>=chapter.getEndTimeMillis()
                || duration>0 && chapter.getStartTimeMillis()>=duration ? null : chapter;
    }
}
