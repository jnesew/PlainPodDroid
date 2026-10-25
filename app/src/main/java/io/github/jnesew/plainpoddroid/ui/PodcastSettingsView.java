package io.github.jnesew.plainpoddroid.ui;

import android.content.Context;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import io.github.jnesew.plainpoddroid.R;
import io.github.jnesew.plainpoddroid.core.catalog.CatalogSubscriptionSummary;
import java.util.function.BiConsumer;

/** ID-scoped settings: construction and edits never navigate or acknowledge arrivals. */
@android.annotation.SuppressLint("ViewConstructor") // Programmatic, ID-scoped binding; never inflated from XML.
public final class PodcastSettingsView extends LinearLayout {
    private Switch automatic;
    private boolean binding;
    public PodcastSettingsView(Context context,CatalogSubscriptionSummary podcast,boolean canEditAutomatic,
            boolean canEditLogin,Runnable speed,Runnable login,BiConsumer<Boolean,PodcastSettingsView> saveAutomatic){
        super(context);setOrientation(VERTICAL);setPadding(UiWidgets.dp(context,20),UiWidgets.dp(context,8),UiWidgets.dp(context,20),UiWidgets.dp(context,8));
        addView(UiWidgets.text(context,podcast.getTitle(),18,true));
        action(R.string.ui_podcast_speed,speed,true);
        if(podcast.isSubscribed()){
            automatic=new Switch(context);automatic.setText(R.string.automatic_download_selected_label);
            automatic.setMinHeight(UiWidgets.dp(context,48));automatic.setChecked(podcast.isAutoDownload());automatic.setEnabled(canEditAutomatic);
            addView(automatic);automatic.setOnCheckedChangeListener((button,enabled)->{if(!binding)saveAutomatic.accept(enabled,this);});
            action(R.string.ui_feed_login,login,canEditLogin&&podcast.getSourceUrl()!=null);
        }
    }
    private void action(int label,Runnable action,boolean enabled){
        Button button=new Button(getContext());button.setText(label);button.setMinHeight(UiWidgets.dp(getContext(),48));
        button.setLayoutParams(new LayoutParams(-1,-2));button.setEnabled(enabled);button.setOnClickListener(v->action.run());addView(button);
    }
    public void setAutomaticDownloadBusy(){if(automatic!=null)automatic.setEnabled(false);}
    public void finishAutomaticDownload(boolean enabled,boolean editable){
        if(automatic==null)return;binding=true;automatic.setChecked(enabled);automatic.setEnabled(editable);binding=false;
    }
}
