package io.github.jnesew.plainpoddroid;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.text.TextUtils;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import io.github.jnesew.plainpoddroid.core.opml.OpmlMigrationStore;
import io.github.jnesew.plainpoddroid.core.opml.OpmlDocument;
import io.github.jnesew.plainpoddroid.core.opml.OpmlImportPlan;
import io.github.jnesew.plainpoddroid.core.opml.OpmlParser;
import io.github.jnesew.plainpoddroid.core.opml.OpmlParseException;
import io.github.jnesew.plainpoddroid.core.opml.OpmlWriter;
import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Activity-owned SAF import/export lifetime; pending URLs/bytes never enter instance state. */
final class OpmlMigrationPanel implements AutoCloseable {
    private static final int OPML_IMPORT_REQUEST=7021,OPML_EXPORT_REQUEST=7022;
    private final Activity activity;
    private final OpmlMigrationStore opmlStore;
    private final Executor worker;
    private final Handler main;
    private final Runnable changed;
    private Button importOpmlButton,confirmOpmlButton,exportOpmlButton;
    private TextView opmlStatus;
    private OpmlDocument pendingOpmlDocument;
    private byte[] pendingOpmlExport;
    private boolean opmlBusy,closed;
    private AlertDialog warningDialog,detailsDialog;
    private int opmlGeneration;

    OpmlMigrationPanel(Activity activity,OpmlMigrationStore store,Executor worker,Handler main,
            Runnable openImport,Runnable changed){
        this.activity=activity;this.opmlStore=store;this.worker=worker;this.main=main;this.changed=changed;
        importOpmlButton = activity.findViewById(R.id.import_opml);
        confirmOpmlButton = activity.findViewById(R.id.confirm_opml);
        exportOpmlButton = activity.findViewById(R.id.export_opml);
        opmlStatus = activity.findViewById(R.id.opml_status);
        importOpmlButton.setOnClickListener(ignored -> chooseOpmlImport());
        confirmOpmlButton.setOnClickListener(ignored -> confirmOpmlImport());
        exportOpmlButton.setOnClickListener(ignored -> warnBeforeOpmlExport());
        activity.findViewById(R.id.settings_import_opml).setOnClickListener(v -> openImport.run());
        activity.findViewById(R.id.opml_details).setOnClickListener(v -> {
            if(closed||detailsDialog!=null)return;
            detailsDialog=new AlertDialog.Builder(activity).setTitle(R.string.ui_import_details)
                    .setMessage(R.string.ui_opml_invalid_details).setPositiveButton(android.R.string.ok,null).show();
            detailsDialog.setOnDismissListener(dialog->detailsDialog=null);
        });

    }
    boolean isBusy(){return opmlBusy;}
    private boolean isUnavailable(){return closed||activity.isDestroyed();}
    @Override public void close(){
        closed=true;opmlGeneration++;pendingOpmlDocument=null;pendingOpmlExport=null;
        if(warningDialog!=null)warningDialog.dismiss();if(detailsDialog!=null)detailsDialog.dismiss();
    }

    private void setOpmlStatus(int resource) { setOpmlStatus(activity.getText(resource)); }
    private void setOpmlStatus(CharSequence text) {
        opmlStatus.setText(text);opmlStatus.setVisibility(TextUtils.isEmpty(text)?View.GONE:View.VISIBLE);
    }
    private String importPreviewText(OpmlImportPlan plan) {
        List<String> parts=new ArrayList<>();
        parts.add(activity.getResources().getQuantityString(R.plurals.ui_import_podcasts,plan.getAccepted().size(),plan.getAccepted().size()));
        if(plan.getDuplicateCount()>0)parts.add(activity.getResources().getQuantityString(R.plurals.ui_import_duplicates,plan.getDuplicateCount(),plan.getDuplicateCount()));
        if(plan.getRejectedCount()>0)parts.add(activity.getResources().getQuantityString(R.plurals.ui_import_invalid,plan.getRejectedCount(),plan.getRejectedCount()));
        String summary=String.join(" · ",parts);
        if(plan.exceedsSubscriptionLimit())summary+="\n"+activity.getString(R.string.ui_opml_over_limit,SecurityLimits.MAX_SUBSCRIPTIONS);
        return summary;
    }

    private void setOpmlBusy(boolean busy) {
        opmlBusy = busy;
        importOpmlButton.setEnabled(!busy);
        exportOpmlButton.setEnabled(!busy);
        confirmOpmlButton.setVisibility(pendingOpmlDocument != null ? View.VISIBLE : View.GONE);
        confirmOpmlButton.setEnabled(!busy && pendingOpmlDocument != null);
        activity.findViewById(R.id.opml_refresh_notice).setVisibility(pendingOpmlDocument != null ? View.VISIBLE : View.GONE);
    }

    private void chooseOpmlImport() {
        if (closed || opmlBusy) return;
        pendingOpmlDocument = null;
        opmlStatus=activity.findViewById(R.id.opml_status);setOpmlStatus("");
        activity.findViewById(R.id.opml_details).setVisibility(View.GONE);
        setOpmlBusy(true);
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            activity.startActivityForResult(intent, OPML_IMPORT_REQUEST);
        } catch (android.content.ActivityNotFoundException | SecurityException exception) {
            setOpmlBusy(false);
            setOpmlStatus(R.string.opml_picker_error);
        }
    }

    private void warnBeforeOpmlExport() {
        if (closed || opmlBusy || warningDialog!=null) return;
        warningDialog=new AlertDialog.Builder(activity)
                .setTitle(R.string.opml_export_warning_title)
                .setMessage(R.string.opml_export_warning)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.export_opml, (dialog, which) -> prepareOpmlExport())
                .show();
        warningDialog.setOnDismissListener(dialog->warningDialog=null);
    }

    private void prepareOpmlExport() {
        if (closed || opmlBusy) return;
        opmlStatus=activity.findViewById(R.id.opml_export_status);
        setOpmlBusy(true);
        setOpmlStatus(R.string.opml_export_preparing);
        int generation = ++opmlGeneration;
        try {
            worker.execute(() -> {
                byte[] output;
                try {
                    output = new OpmlWriter().write(opmlStore.listForExport());
                } catch (IOException | IllegalArgumentException exception) {
                    main.post(() -> {
                        if (isUnavailable() || generation != opmlGeneration) return;
                        setOpmlBusy(false);
                        setOpmlStatus(R.string.opml_export_error);
                    });
                    return;
                }
                main.post(() -> {
                    if (isUnavailable() || generation != opmlGeneration) return;
                    pendingOpmlExport = output;
                    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("text/xml");
                    intent.putExtra(Intent.EXTRA_TITLE, "PlainPodDroid.opml");
                    try {
                        activity.startActivityForResult(intent, OPML_EXPORT_REQUEST);
                    } catch (android.content.ActivityNotFoundException | SecurityException exception) {
                        pendingOpmlExport = null;
                        setOpmlBusy(false);
                        setOpmlStatus(R.string.opml_picker_error);
                    }
                });
            });
        } catch (RejectedExecutionException exception) {
            setOpmlBusy(false);
            setOpmlStatus(R.string.opml_background_error);
        }
    }

    void onActivityResult(int requestCode, int resultCode, Intent data) {
        if(closed)return;
        if (requestCode != OPML_IMPORT_REQUEST && requestCode != OPML_EXPORT_REQUEST) return;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            pendingOpmlExport = null;
            setOpmlBusy(false);
            setOpmlStatus("");
            return;
        }
        Uri chosen = data.getData();
        // SAF returns content URIs with provider-granted authority. A file URI
        // would instead exercise this app's private filesystem privileges.
        if (!"content".equals(chosen.getScheme())
                || chosen.getAuthority() == null || chosen.getAuthority().isEmpty()
                || chosen.toString().length() > SecurityLimits.MAX_URL_CHARS) {
            pendingOpmlExport = null;
            setOpmlBusy(false);
            setOpmlStatus(R.string.opml_picker_error);
            return;
        }
        if (requestCode == OPML_IMPORT_REQUEST) {
            setOpmlBusy(true);
            readOpmlPreview(chosen);
        } else if (pendingOpmlExport != null) {
            byte[] output = pendingOpmlExport;
            pendingOpmlExport = null;
            setOpmlBusy(true);
            writeOpmlExport(chosen, output);
        } else {
            setOpmlBusy(false);
            setOpmlStatus(R.string.opml_export_error);
        }
    }

    private void readOpmlPreview(Uri chosen) {
        setOpmlStatus(R.string.opml_import_reading);
        int generation = ++opmlGeneration;
        try {
            worker.execute(() -> {
                OpmlDocument document;
                OpmlImportPlan plan;
                try (InputStream input = activity.getContentResolver().openInputStream(chosen)) {
                    if (input == null) throw new IOException("No OPML input stream.");
                    document = new OpmlParser().parse(input);
                    plan = opmlStore.preview(document);
                } catch (IOException | OpmlParseException | SecurityException exception) {
                    main.post(() -> {
                        if (isUnavailable() || generation != opmlGeneration) return;
                        setOpmlBusy(false);
                        setOpmlStatus(R.string.opml_import_error);
                    });
                    return;
                }
                main.post(() -> {
                    if (isUnavailable() || generation != opmlGeneration) return;
                    pendingOpmlDocument = plan.exceedsSubscriptionLimit()
                            || plan.getAccepted().isEmpty() ? null : document;
                    setOpmlBusy(false);
                    setOpmlStatus(importPreviewText(plan));
                    if (pendingOpmlDocument != null) confirmOpmlButton.setText(activity.getResources().getQuantityString(
                            R.plurals.ui_import_count,plan.getAccepted().size(),plan.getAccepted().size()));
                    activity.findViewById(R.id.opml_details).setVisibility(plan.getRejectedCount()>0?View.VISIBLE:View.GONE);
                });
            });
        } catch (RejectedExecutionException exception) {
            setOpmlBusy(false);
            setOpmlStatus(R.string.opml_background_error);
        }
    }

    private void confirmOpmlImport() {
        if (closed || opmlBusy || pendingOpmlDocument == null) return;
        opmlStatus=activity.findViewById(R.id.opml_status);
        OpmlDocument document = pendingOpmlDocument;
        pendingOpmlDocument = null;
        setOpmlBusy(true);
        setOpmlStatus(R.string.opml_import_saving);
        int generation = ++opmlGeneration;
        try {
            worker.execute(() -> {
                OpmlImportPlan result;
                try {
                    result = opmlStore.importConfirmed(document);
                } catch (IOException exception) {
                    main.post(() -> {
                        if (isUnavailable() || generation != opmlGeneration) return;
                        setOpmlBusy(false);
                        setOpmlStatus(R.string.opml_import_save_error);
                    });
                    return;
                }
                main.post(() -> {
                    if (isUnavailable() || generation != opmlGeneration) return;
                    setOpmlBusy(false);
                    setOpmlStatus(activity.getResources().getQuantityString(R.plurals.ui_import_added,
                            result.getAccepted().size(),result.getAccepted().size()));
                    changed.run();
                });
            });
        } catch (RejectedExecutionException exception) {
            setOpmlBusy(false);
            setOpmlStatus(R.string.opml_background_error);
        }
    }

    private void writeOpmlExport(Uri chosen, byte[] output) {
        setOpmlStatus(R.string.opml_export_writing);
        int generation = ++opmlGeneration;
        try {
            worker.execute(() -> {
                boolean success = false;
                try (OutputStream stream = activity.getContentResolver().openOutputStream(chosen, "wt")) {
                    if (stream == null) throw new IOException("No OPML output stream.");
                    stream.write(output);
                    stream.flush();
                    success = true;
                } catch (IOException | SecurityException exception) {
                    success = false;
                }
                boolean saved = success;
                main.post(() -> {
                    if (isUnavailable() || generation != opmlGeneration) return;
                    setOpmlBusy(false);
                    setOpmlStatus(saved ? R.string.opml_export_saved
                            : R.string.opml_export_write_error);
                });
            });
        } catch (RejectedExecutionException exception) {
            setOpmlBusy(false);
            setOpmlStatus(R.string.opml_background_error);
        }
    }

}
