package io.github.jnesew.plainpoddroid.ui;

import android.content.ClipData;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.drawable.GradientDrawable;
import android.view.DragEvent;
import android.view.View;
import android.view.ViewParent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import io.github.jnesew.plainpoddroid.R;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/** Local-only whole-row drag. The preview never mutates the saved queue. */
final class QueueRowDrag {
    private record Item(String id,String title,int position,LinearLayout view,Button grip) { }
    private record Token(QueueRowDrag owner,int generation) { }
    private final LinearLayout rows;
    private final BooleanSupplier canMove;
    private final BiConsumer<String,Integer> move;
    private final List<Item> items=new ArrayList<>();
    private final GradientDrawable gap=new GradientDrawable();
    private View surface;
    private ScrollView scroll;
    private Item dragging;
    private int generation,source,target;
    private float pointerY;
    private boolean inside,dropped;
    private final Runnable autoScroll=this::scrollFrame;

    QueueRowDrag(LinearLayout rows,BooleanSupplier canMove,BiConsumer<String,Integer> move) {
        this.rows=rows;this.canMove=canMove;this.move=move;
        gap.setCornerRadius(UiWidgets.dp(rows.getContext(),12));
        gap.setColor(rows.getContext().getColor(R.color.background));
        gap.setStroke(UiWidgets.dp(rows.getContext(),1),rows.getContext().getColor(R.color.accent));
    }
    void add(String id,String title,int position,LinearLayout view,Button grip) {
        if(surface==null) {
            surface=rows;
            for(ViewParent p=rows.getParent();p!=null;p=p.getParent()) {
                if(p instanceof ScrollView found){surface=found;scroll=found;break;}
            }
            surface.setOnDragListener(this::event);
        }
        Item item=new Item(id,title,position,view,grip);items.add(item);
        grip.setOnLongClickListener(v->start(item));
    }
    private boolean start(Item item) {
        if(dragging!=null||dropped||!canMove.getAsBoolean()||item.view().getWidth()==0)return false;
        source=items.indexOf(item);target=source;dragging=item;dropped=false;inside=true;
        RowShadow shadow=new RowShadow(item.view(),item.grip());
        boolean started=item.grip().startDragAndDrop(ClipData.newPlainText("queue",""),
                shadow,new Token(this,generation),0);
        if(!started){dragging=null;return false;}
        item.view().setAlpha(0f);
        rows.getOverlay().add(gap);preview(source);
        announce(rows.getContext().getString(R.string.ui_drag_started,item.title()));
        return true;
    }
    private boolean event(View view,DragEvent event) {
        if(!(event.getLocalState() instanceof Token token)||token.owner()!=this
                ||token.generation()!=generation)return false;
        if(event.getAction()==DragEvent.ACTION_DRAG_ENDED) {
            if(!dropped)restore();
            stopScrolling();dragging=null;inside=false;
            return true;
        }
        if(dragging==null)return false;
        if(!canMove.getAsBoolean()){cancel();return false;}
        switch(event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED -> { return true; }
            case DragEvent.ACTION_DRAG_ENTERED,DragEvent.ACTION_DRAG_LOCATION -> {
                inside=true;pointerY=event.getY();updateTarget();
                surface.removeCallbacks(autoScroll);surface.postOnAnimation(autoScroll);
            }
            case DragEvent.ACTION_DRAG_EXITED -> {inside=false;stopScrolling();}
            case DragEvent.ACTION_DROP -> {
                pointerY=event.getY();updateTarget();stopScrolling();
                String id=dragging.id();int position=items.get(target).position();
                rows.getOverlay().remove(gap);dragging.view().setAlpha(1f);dropped=true;
                if(target!=source) {
                    announce(rows.getContext().getString(R.string.ui_drag_dropped,target+1));
                    move.accept(id,position);
                } else {restore();dropped=false;}
            }
            default -> { }
        }
        return true;
    }
    private void updateTarget() {
        if(dragging==null||!inside)return;
        int[] surfaceLocation=new int[2],rowsLocation=new int[2];
        surface.getLocationOnScreen(surfaceLocation);rows.getLocationOnScreen(rowsLocation);
        float y=surfaceLocation[1]+pointerY-rowsLocation[1];
        int next=0;
        for(int i=0;i<items.size();i++) {
            if(i==source)continue;
            View row=items.get(i).view();
            if(y>=row.getTop()+row.getHeight()/2f)next++;
        }
        preview(Math.max(0,Math.min(items.size()-1,next)));
    }
    private void preview(int destination) {
        boolean changed=target!=destination;target=destination;
        List<Item> ordered=new ArrayList<>(items);ordered.remove(dragging);ordered.add(target,dragging);
        int y=items.get(0).view().getTop();
        for(Item item:ordered) {
            View row=item.view();float shift=y-row.getTop();
            if(item==dragging) {
                row.animate().cancel();row.setTranslationY(shift);
                gap.setBounds(0,y,rows.getWidth(),y+row.getHeight());
            } else if(changed)row.animate().translationY(shift).setDuration(140L).start();
            y+=row.getHeight();
        }
        rows.invalidate();
    }
    private void scrollFrame() {
        if(dragging==null||!inside||scroll==null||!canMove.getAsBoolean())return;
        int edge=UiWidgets.dp(rows.getContext(),64),step=UiWidgets.dp(rows.getContext(),10);
        int dy=pointerY<edge?-step:pointerY>surface.getHeight()-edge?step:0;
        if(dy==0)return;
        int before=scroll.getScrollY();scroll.scrollBy(0,dy);
        if(before!=scroll.getScrollY()){updateTarget();surface.postOnAnimation(autoScroll);}
    }
    @SuppressWarnings("deprecation") // Gesture feedback for API 29–35 as well as newer Android.
    private void announce(String text) { rows.announceForAccessibility(text); }
    private void stopScrolling(){if(surface!=null)surface.removeCallbacks(autoScroll);}
    private void restore() {
        rows.getOverlay().remove(gap);
        for(Item item:items){item.view().animate().cancel();item.view().setAlpha(1f);item.view().setTranslationY(0f);}
    }
    void cancel() {
        stopScrolling();
        if(dragging!=null)dragging.grip().cancelDragAndDrop();
        restore();dragging=null;inside=false;dropped=false;
    }
    void clear() {
        cancel();generation++;
        if(surface!=null)surface.setOnDragListener(null);
        surface=null;scroll=null;items.clear();
    }

    /** Full row with the grip under the finger, plus room for its rounded shadow. */
    static final class RowShadow extends View.DragShadowBuilder {
        private final View row,grip;
        private final int pad;
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        RowShadow(View row,View grip) {
            super(row);this.row=row;this.grip=grip;pad=UiWidgets.dp(row.getContext(),8);
            paint.setColor(row.getContext().getColor(R.color.surface));
            paint.setShadowLayer(pad,0,pad/3f,0x66000000);
        }
        @Override public void onProvideShadowMetrics(Point size,Point touch) {
            size.set(row.getWidth()+2*pad,row.getHeight()+2*pad);
            int[] r=new int[2],g=new int[2];row.getLocationOnScreen(r);grip.getLocationOnScreen(g);
            touch.set(pad+g[0]-r[0]+grip.getWidth()/2,pad+g[1]-r[1]+grip.getHeight()/2);
        }
        @Override public void onDrawShadow(Canvas canvas) {
            canvas.drawRoundRect(pad,pad,pad+row.getWidth(),pad+row.getHeight(),pad,pad,paint);
            int save=canvas.save();canvas.translate(pad,pad);row.draw(canvas);canvas.restoreToCount(save);
        }
    }
}
