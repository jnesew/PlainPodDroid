package io.github.jnesew.plainpoddroid.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.widget.SeekBar;
import io.github.jnesew.plainpoddroid.R;
import io.github.jnesew.plainpoddroid.core.chapter.ChapterTimeline;
import io.github.jnesew.plainpoddroid.core.chapter.PodcastChapter;

/** Chapter marks are visual cues; the native seek target and chapter sheet remain accessible. */
public final class ChapterSeekBar extends SeekBar {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private ChapterTimeline timeline=new ChapterTimeline(null);
    private long duration;
    public ChapterSeekBar(Context context){this(context,null);}
    public ChapterSeekBar(Context context,AttributeSet attrs){super(context,attrs);paint.setColor(context.getColor(R.color.text_secondary));}
    public void setChapters(ChapterTimeline timeline,long duration){this.timeline=timeline;this.duration=duration;invalidate();}
    @Override protected synchronized void onDraw(Canvas canvas){
        super.onDraw(canvas);if(duration<=0||getMax()<=0)return;
        float width=getWidth()-getPaddingLeft()-getPaddingRight();if(width<=0)return;
        boolean rtl=getLayoutDirection()==LAYOUT_DIRECTION_RTL;
        float thumb=getPaddingLeft()+width*(rtl ? 1f-(float)getProgress()/getMax() : (float)getProgress()/getMax());
        float last=Float.NaN, y=getHeight()/2f; paint.setAlpha(isEnabled()?255:110);paint.setStrokeWidth(UiWidgets.dp(getContext(),2));
        for(PodcastChapter chapter:timeline.chapters()){
            long start=chapter.getStartTimeMillis();if(start<=0||start>=duration)continue;
            float fraction=(float)start/duration, x=getPaddingLeft()+width*(rtl?1f-fraction:fraction);
            if(!Float.isNaN(last)&&Math.abs(x-last)<UiWidgets.dp(getContext(),6))continue;
            last=x;if(Math.abs(x-thumb)<UiWidgets.dp(getContext(),10))continue;
            canvas.drawLine(x,y-UiWidgets.dp(getContext(),5),x,y+UiWidgets.dp(getContext(),5),paint);
        }
    }
}
