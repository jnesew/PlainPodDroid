package io.github.jnesew.plainpoddroid.ui;

import android.os.Handler;
import android.view.View;
import android.view.ViewTreeObserver;

/** Coalesces artwork completions, layout and scrolling into one visible binding pass. */
public final class ArtworkViewportObserver implements AutoCloseable {
    private final View root;
    private final Handler main;
    private final Runnable bind;
    private boolean pending,closed;
    private final ViewTreeObserver.OnScrollChangedListener scroll=this::request;
    private final ViewTreeObserver.OnGlobalLayoutListener layout=this::request;
    private final Runnable update=this::update;
    public ArtworkViewportObserver(View root,Handler main,Runnable bind){
        this.root=root;this.main=main;this.bind=bind;
        root.getViewTreeObserver().addOnScrollChangedListener(scroll);
        root.getViewTreeObserver().addOnGlobalLayoutListener(layout);request();
    }
    private void update(){pending=false;if(!closed&&root.getWindowVisibility()==View.VISIBLE)bind.run();}
    public void request(){if(!closed&&!pending){pending=true;main.postDelayed(update,16L);}}
    @Override public void close(){
        closed=true;main.removeCallbacks(update);
        var observer=root.getViewTreeObserver();
        if(observer.isAlive()){observer.removeOnScrollChangedListener(scroll);observer.removeOnGlobalLayoutListener(layout);}
    }
}
