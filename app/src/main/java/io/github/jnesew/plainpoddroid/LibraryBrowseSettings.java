package io.github.jnesew.plainpoddroid;

import android.content.Context;
import android.content.SharedPreferences;
import io.github.jnesew.plainpoddroid.core.catalog.CatalogSubscriptionOrder;

/** Small local presentation preference; per-podcast order belongs to the catalog. */
public final class LibraryBrowseSettings {
    private final SharedPreferences preferences;
    public LibraryBrowseSettings(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences("library_browse", Context.MODE_PRIVATE);
    }
    public CatalogSubscriptionOrder getOrder() {
        try { return CatalogSubscriptionOrder.valueOf(preferences.getString("podcast_order", "LATEST_EPISODE")); }
        catch (IllegalArgumentException | ClassCastException | NullPointerException e) { return CatalogSubscriptionOrder.LATEST_EPISODE; }
    }
    public boolean setOrder(CatalogSubscriptionOrder order) {
        return preferences.edit().putString("podcast_order", order.name()).commit();
    }
}
