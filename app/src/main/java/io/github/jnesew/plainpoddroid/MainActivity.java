package io.github.jnesew.plainpoddroid;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.RequiresApi;
import io.github.jnesew.plainpoddroid.core.catalog.CatalogBrowserController;
import io.github.jnesew.plainpoddroid.core.catalog.CatalogBrowseQuery;
import io.github.jnesew.plainpoddroid.core.catalog.CatalogEpisodeSummary;
import io.github.jnesew.plainpoddroid.core.catalog.CatalogSubscriptionSummary;
import io.github.jnesew.plainpoddroid.core.catalog.CatalogSubscriptionSearch;
import io.github.jnesew.plainpoddroid.core.artwork.BoundedArtworkAcquirer;
import io.github.jnesew.plainpoddroid.artwork.ArtworkThumbnailLoader;
import io.github.jnesew.plainpoddroid.core.chapter.ChapterLoadController;
import io.github.jnesew.plainpoddroid.core.chapter.PodcastChapter;
import io.github.jnesew.plainpoddroid.core.chapter.PodcastChapterDocument;
import io.github.jnesew.plainpoddroid.core.download.EpisodeDownloadActions;
import io.github.jnesew.plainpoddroid.core.download.EpisodeDownloadController;
import io.github.jnesew.plainpoddroid.core.episode.EpisodeActionController;
import io.github.jnesew.plainpoddroid.core.episode.EpisodeDetails;
import io.github.jnesew.plainpoddroid.core.episode.EpisodeStateMutation;
import io.github.jnesew.plainpoddroid.core.network.CanonicalHttpsUrl;
import io.github.jnesew.plainpoddroid.core.network.NetworkPolicyException;
import io.github.jnesew.plainpoddroid.core.refresh.ManualRefreshAllController;
import io.github.jnesew.plainpoddroid.core.refresh.ManualRefreshAllResult;
import io.github.jnesew.plainpoddroid.core.queue.EpisodeQueueController;
import io.github.jnesew.plainpoddroid.core.queue.EpisodeQueueEntry;
import io.github.jnesew.plainpoddroid.core.queue.EpisodeQueueMutation;
import io.github.jnesew.plainpoddroid.core.security.SecurityLimits;
import io.github.jnesew.plainpoddroid.core.subscription.SubscriptionPreview;
import io.github.jnesew.plainpoddroid.core.subscription.SubscriptionRemovalCoordinator;
import io.github.jnesew.plainpoddroid.core.subscription.SubscriptionRefreshController;
import io.github.jnesew.plainpoddroid.core.subscription.SubscriptionUiController;
import io.github.jnesew.plainpoddroid.playback.PlaybackPrivacySettings;
import io.github.jnesew.plainpoddroid.ui.EpisodeMetadataFormatter;
import io.github.jnesew.plainpoddroid.ui.UiWorkState;
import io.github.jnesew.plainpoddroid.ui.PlaybackPanel;
import io.github.jnesew.plainpoddroid.ui.ChapterPanel;
import io.github.jnesew.plainpoddroid.ui.NavigationShell;
import io.github.jnesew.plainpoddroid.ui.PodcastLibraryView;
import io.github.jnesew.plainpoddroid.ui.EpisodeListView;
import io.github.jnesew.plainpoddroid.ui.UiWidgets;
import io.github.jnesew.plainpoddroid.core.ui.NavigationState.Destination;
import android.widget.PopupMenu;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

public final class MainActivity extends Activity {
    private final EpisodeMetadataFormatter episodeMetadata =
            new EpisodeMetadataFormatter(this::getString);
    private NavigationShell navigationShell;
    private PodcastLibraryView podcastLibrary, archiveLibrary;
    private EpisodeListView podcastEpisodeList, queueList;
    private LibraryListPanel downloadsPanel, favoritesPanel, historyPanel, newEpisodesPanel, inProgressPanel;
    private EpisodeBatchUiController batchController;
    private String pendingEpisodeId = "";
    private String miniDetailsId = "";
    private EpisodeDetails miniDetails;
    private int miniDetailsGeneration, rowPlayGeneration;
    private Bundle initialSavedState;
    private CatalogBrowseQuery restoredQuery;
    private int renderedEpisodeFilter = -1;
    private SettingsPanel settingsPanel;
    private PodcastSettingsEditor podcastSettings;
    private PlaybackPanel playbackPanel;
    private PlaybackPrivacySettings playbackPrivacySettings;
    private io.github.jnesew.plainpoddroid.ui.FeedLoginEditor subscriptionLoginEditor;
    private ExecutorService screenWorker;
    private ExecutorService feedWorker;
    private ExecutorService downloadWorker;
    private Handler mainHandler;
    private SubscriptionUiController subscriptionController;
    private EditText subscriptionUrlInput;
    private Button previewFeedButton;
    private Button cancelPreviewButton;
    private Button confirmSubscriptionButton;
    private ProgressBar subscriptionProgress;
    private TextView subscriptionStatus;
    private LinearLayout subscriptionPreview;
    private TextView previewTitle;
    private ImageView previewArtwork;
    private TextView previewAuthor;
    private TextView previewOriginalOrigin;
    private TextView previewFinalOrigin;
    private TextView previewRedirects;
    private TextView previewCounts;
    private TextView previewAppleSource;
    private OpmlMigrationPanel opmlPanel;
    private boolean removalBusy;
    private long removingPodcastId;
    private int removalGeneration;
    private TextView subscriptionRemovalStatus;

    private CatalogBrowserController catalogBrowserController;
    private SubscriptionRefreshController subscriptionRefreshController;
    private ManualRefreshAllController manualRefreshAllController;
    private EpisodeDownloadController episodeDownloadController; // Guarded deletion only.
    private ManualDownloadManager manualDownloads;
    private ManualDownloadPanel manualDownloadPanel;
    private List<io.github.jnesew.plainpoddroid.core.download.ManualDownloadQueue.Entry> manualDownloadEntries=List.of();
    private final Runnable manualDownloadListener=this::onManualDownloadsChanged;
    private EpisodeActionController episodeActionController;
    private ChapterLoadController chapterLoadController;
    private Button loadEpisodeChaptersButton;
    private ProgressBar episodeChapterProgress;
    private TextView episodeChapterStatus;
    private TextView episodeChapterPrivacyNotice;
    private ChapterPanel chapterPanel;
    private Button refreshCatalogButton;
    private Button refreshAllSubscriptionsButton;
    private Button cancelRefreshAllButton;
    private Button loadMoreEpisodesButton;
    private ProgressBar catalogProgress;
    private ProgressBar subscriptionRefreshProgress;
    private ProgressBar refreshAllProgress;
    private TextView catalogStatus;
    private TextView subscriptionRefreshStatus;
    private TextView refreshAllStatus;
    private ProgressBar episodeDownloadProgress;
    private TextView episodeDownloadStatus;
    private ProgressBar episodeActionProgress;
    private TextView episodeActionStatus;
    private LinearLayout episodeDetailsPanel;
    private TextView episodeDetailsTitle;
    private TextView episodeDetailsAuthor;
    private TextView episodeDetailsMetadata;
    private TextView episodeDetailsNotes;
    private Button episodePlaybackButton;
    private Button episodePlayedButton;
    private Button episodeFavoriteButton;
    private Button openEpisodePageButton;
    private Button closeEpisodeDetailsButton;
    private ArtworkThumbnailLoader artworkThumbnailLoader,detailArtworkLoader;
    private io.github.jnesew.plainpoddroid.ui.ArtworkViewportObserver artworkViewport;
    private EditText catalogPodcastSearch;
    private io.github.jnesew.plainpoddroid.ui.LiveSearchField podcastLiveSearch;
    private io.github.jnesew.plainpoddroid.ui.LiveSearchField episodeLiveSearch;
    private CatalogBrowseQuery pendingSearchQuery;
    private String appliedPodcastSearch = "";
    private EditText catalogEpisodeSearch;
    private CatalogBrowseQuery.Filter episodeFilter=CatalogBrowseQuery.Filter.ALL;
    private List<CatalogSubscriptionSummary> renderedLibrarySubscriptions = Collections.emptyList();
    private boolean libraryRendered,renderedPodcastSearching;
    private List<CatalogSubscriptionSummary> renderedArchiveSubscriptions = Collections.emptyList();
    private boolean archivesRendered;
    private List<CatalogEpisodeSummary> renderedCatalogEpisodes = Collections.emptyList();
    private long pendingCatalogReloadSubscriptionId = -1L;

    private EpisodeQueueController episodeQueueController;
    private ProgressBar episodeQueueProgress;
    private TextView episodeQueueStatus;
    private LinearLayout episodeQueueEntries;
    private Button refreshEpisodeQueueButton;
    private Button previousQueuePageButton;
    private Button nextQueuePageButton;
    private int queuePageOffset;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(DisplayThemeSettings.themedContext(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureEdgeToEdge();
        setContentView(R.layout.activity_main);
        View root = findViewById(R.id.root);
        applySystemBarInsets(root);

        TextView version = findViewById(R.id.version);
        version.setText(getString(
                R.string.version_format,
                BuildConfig.VERSION_NAME));

        initialSavedState = savedInstanceState;
        navigationShell = new NavigationShell(this, root, savedInstanceState, this::onDestinationChanged,
                () -> startActivity(new Intent(this, AboutActivity.class)));
        restoreCatalogQuery(savedInstanceState);
        playbackPrivacySettings = new PlaybackPrivacySettings(this);
        configureSubscriptionUi();
        playbackPanel = new PlaybackPanel(this, mainHandler, playbackPrivacySettings,
                this::refreshEpisodePlaybackButton);
        chapterPanel = new ChapterPanel(this, findViewById(R.id.episode_current_chapter),
                findViewById(R.id.episode_seek), new ChapterPanel.Actions() {
                    public void select(String id, PodcastChapter chapter) { seekChapter(id, chapter); }
                    public void play(String id, PodcastChapter chapter) { playChapter(id, chapter); }
                    public void page(String id, PodcastChapter chapter) { openChapterPage(id, chapter); }
                    public void load() { loadEpisodeChaptersButton.performClick(); }
                });
        playbackPanel.setChapterPanel(chapterPanel);
        configureCatalogUi();
        configureEpisodeQueueUi();
        settingsPanel = new SettingsPanel(this, screenWorker, mainHandler,
                playbackPrivacySettings, ()->isEpisodeDownloadBusy()||isManualDownloadActive(),
                () -> renderCatalogState(catalogBrowserController.getState()),
                this::onAudioSettingsChanged);
        findViewById(R.id.open_about).setOnClickListener(ignored ->
                startActivity(new Intent(this, AboutActivity.class)));
        configureDestinationActions();
        artworkViewport=new io.github.jnesew.plainpoddroid.ui.ArtworkViewportObserver(
                root,mainHandler,this::refreshVisibleArtwork);
        onDestinationChanged();
    }

    @Override
    protected void onStart() {
        super.onStart();
        playbackPanel.start();
        artworkThumbnailLoader.start();
        detailArtworkLoader.start();
        notifyCatalogArtworkReady();
        if(manualDownloads!=null)manualDownloads.addListener(manualDownloadListener);
    }

    @Override
    protected void onResume() {
        super.onResume();
        settingsPanel.resume();
        playbackPanel.resume();
        refreshSelectedEpisode();
        refreshLibraryLists();
        if (episodeQueueController != null) {
            episodeQueueController.refresh();
        }
    }

    @Override
    protected void onStop() {
        if(manualDownloads!=null)manualDownloads.removeListener(manualDownloadListener);
        artworkThumbnailLoader.stop();
        detailArtworkLoader.stop();
        chapterPanel.dismiss();
        playbackPanel.stop();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if(manualDownloads!=null)manualDownloads.removeListener(manualDownloadListener);
        if (subscriptionLoginEditor != null) subscriptionLoginEditor.clear();
        if(opmlPanel!=null)opmlPanel.close();
        removalGeneration++;
        if (podcastLiveSearch != null) podcastLiveSearch.close();
        if (episodeLiveSearch != null) episodeLiveSearch.close();
        if (queueList != null) queueList.clear();
        miniDetailsGeneration++; rowPlayGeneration++;
        if (downloadsPanel != null) downloadsPanel.close();
        if(batchController!=null)batchController.close();
        cancelEpisodeSelection();
        if (favoritesPanel != null) favoritesPanel.close();
        if (historyPanel != null) historyPanel.close();
        if (newEpisodesPanel != null) newEpisodesPanel.close();
        if (inProgressPanel != null) inProgressPanel.close();
        if (subscriptionController != null) {
            subscriptionController.close();
        }
        if (catalogBrowserController != null) {
            catalogBrowserController.close();
        }
        if(artworkViewport!=null)artworkViewport.close();
        if(detailArtworkLoader!=null)detailArtworkLoader.close();
        if (artworkThumbnailLoader != null) artworkThumbnailLoader.close();
        if (subscriptionRefreshController != null) {
            subscriptionRefreshController.close();
        }
        if (manualRefreshAllController != null) {
            manualRefreshAllController.close();
        }
        if (episodeDownloadController != null) {
            episodeDownloadController.close();
        }
        if (episodeActionController != null) {
            episodeActionController.close();
        }
        if (chapterLoadController != null) {
            chapterLoadController.close();
        }
        if (episodeQueueController != null) {
            episodeQueueController.close();
        }
        if (settingsPanel != null) settingsPanel.close();
        if (podcastSettings != null) podcastSettings.close();
        if (screenWorker != null) {
            screenWorker.shutdownNow();
        }
        if (downloadWorker != null) downloadWorker.shutdownNow();
        if (feedWorker != null) feedWorker.shutdownNow();
        super.onDestroy();
    }

    private void refreshEpisodePlaybackButton() {
        if (episodePlaybackButton == null || episodeActionController == null) {
            return;
        }
        EpisodeActionController.State state = episodeActionController.getState();
        EpisodeDetails selected = currentEpisodeDetails();
        episodePlaybackButton.setEnabled(selected != null
                && (!episodeArchived(selected) || selected.isDownloaded())
                && !state.isBusy()
                && !workState().hasPlaybackCompetingWork()
                && playbackPanel.isConnected());
        UiWidgets.icon(episodePlaybackButton, selected != null && playbackPanel.isPlaying(selected.getMediaId())
                ? R.drawable.ic_pause : R.drawable.ic_play, getString(selected != null
                && playbackPanel.isPlaying(selected.getMediaId()) ? R.string.ui_pause : R.string.ui_play), true);
        refreshRowPlayback();
        refreshMiniDetails();
        renderChapterLoadState();
    }

    private void configureSubscriptionUi() {
        subscriptionUrlInput = findViewById(R.id.subscription_url);
        previewFeedButton = findViewById(R.id.preview_feed);
        cancelPreviewButton = findViewById(R.id.cancel_subscription_preview);
        confirmSubscriptionButton = findViewById(R.id.confirm_subscription);
        subscriptionProgress = findViewById(R.id.subscription_progress);
        subscriptionStatus = findViewById(R.id.subscription_status);
        subscriptionPreview = findViewById(R.id.subscription_preview);
        previewTitle = findViewById(R.id.subscription_preview_title);
        previewArtwork = findViewById(R.id.subscription_preview_artwork);
        previewAuthor = findViewById(R.id.subscription_preview_author);
        previewOriginalOrigin = findViewById(R.id.subscription_preview_original_origin);
        previewFinalOrigin = findViewById(R.id.subscription_preview_final_origin);
        previewRedirects = findViewById(R.id.subscription_preview_redirects);
        previewCounts = findViewById(R.id.subscription_preview_counts);
        previewAppleSource = findViewById(R.id.subscription_preview_apple_source);
        findViewById(R.id.subscription_preview_feed_address).setOnClickListener(ignored -> {
            SubscriptionPreview preview = subscriptionController.getState().getPreview();
            if (preview == null) return;
            AlertDialog dialog = new AlertDialog.Builder(this).setTitle(R.string.subscription_feed_address)
                    .setMessage(preview.getFeedUrl().toString())
                    .setPositiveButton(android.R.string.ok, null).show();
            TextView message = dialog.findViewById(android.R.id.message);
            if (message != null) message.setTextIsSelectable(true);
        });

        subscriptionLoginEditor = new io.github.jnesew.plainpoddroid.ui.FeedLoginEditor(this, this::invalidateSubscriptionPreview);
        ((ViewGroup) findViewById(R.id.subscription_login_controls)).addView(subscriptionLoginEditor);
        subscriptionUrlInput.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { }
            public void afterTextChanged(android.text.Editable text) { invalidateSubscriptionPreview(); }
        });

        screenWorker = Executors.newSingleThreadExecutor(command ->
                new Thread(command, "catalog-screen-worker"));
        feedWorker = Executors.newSingleThreadExecutor(command ->
                new Thread(command, "feed-acquisition-worker"));
        downloadWorker = Executors.newSingleThreadExecutor(command ->
                new Thread(command, "manual-download-worker"));
        mainHandler = new Handler(Looper.getMainLooper());
        PlainPodDroidApplication application =
                (PlainPodDroidApplication) getApplication();
        subscriptionController = new SubscriptionUiController(
                application.getSubscriptionCoordinator(),
                feedWorker,
                command -> mainHandler.post(command),
                this::renderSubscriptionState);
        opmlPanel=new OpmlMigrationPanel(this,application.getOpmlMigrationStore(),screenWorker,mainHandler,
                navigationShell::addPodcast,()->catalogBrowserController.loadSubscriptions());

        previewFeedButton.setOnClickListener(ignored -> startSubscriptionPreview());
        cancelPreviewButton.setOnClickListener(ignored -> subscriptionController.cancel());
        confirmSubscriptionButton.setOnClickListener(ignored ->
                subscriptionController.confirm());
        subscriptionUrlInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_GO) {
                return false;
            }
            startSubscriptionPreview();
            return true;
        });

        renderSubscriptionState(subscriptionController.getState());
        applySharedText(getIntent());
    }

    @Override
    protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(opmlPanel!=null)opmlPanel.onActivityResult(requestCode,resultCode,data);
    }

    private boolean isOpmlBusy(){return opmlPanel!=null&&opmlPanel.isBusy();}

    private void configureCatalogUi() {
        refreshCatalogButton = findViewById(R.id.refresh_catalog);
        subscriptionRemovalStatus = findViewById(R.id.subscription_remove_status);
        refreshAllSubscriptionsButton = findViewById(R.id.refresh_all_subscriptions);
        cancelRefreshAllButton = findViewById(R.id.cancel_refresh_all);
        loadMoreEpisodesButton = findViewById(R.id.load_more_episodes);
        catalogProgress = findViewById(R.id.catalog_progress);
        subscriptionRefreshProgress = findViewById(R.id.subscription_refresh_progress);
        refreshAllProgress = findViewById(R.id.refresh_all_progress);
        catalogStatus = findViewById(R.id.catalog_status);
        subscriptionRefreshStatus = findViewById(R.id.subscription_refresh_status);
        refreshAllStatus = findViewById(R.id.refresh_all_status);
        refreshAllStatus.setOnClickListener(v -> showRefreshFailures());
        episodeDownloadProgress = findViewById(R.id.episode_download_progress);
        episodeDownloadStatus = findViewById(R.id.episode_download_status);
        episodeActionProgress = findViewById(R.id.episode_action_progress);
        episodeActionStatus = findViewById(R.id.episode_action_status);
        episodeDetailsPanel = findViewById(R.id.episode_details_panel);
        episodeDetailsTitle = findViewById(R.id.episode_details_title);
        episodeDetailsAuthor = findViewById(R.id.episode_details_author);
        episodeDetailsMetadata = findViewById(R.id.episode_details_metadata);
        episodeDetailsNotes = findViewById(R.id.episode_details_notes);
        episodePlaybackButton = findViewById(R.id.episode_playback_action);
        episodePlayedButton = findViewById(R.id.episode_played_action);
        episodeFavoriteButton = findViewById(R.id.episode_favorite_action);
        openEpisodePageButton = findViewById(R.id.open_episode_page);
        closeEpisodeDetailsButton = findViewById(R.id.close_episode_details);
        loadEpisodeChaptersButton = findViewById(R.id.load_episode_chapters);
        episodeChapterProgress = findViewById(R.id.episode_chapter_progress);
        episodeChapterStatus = findViewById(R.id.episode_chapter_status);
        episodeChapterPrivacyNotice = findViewById(R.id.episode_chapter_privacy_notice);
        catalogPodcastSearch = findViewById(R.id.catalog_podcast_search);
        catalogEpisodeSearch = findViewById(R.id.catalog_episode_search);
        PlainPodDroidApplication application =
                (PlainPodDroidApplication) getApplication();
        podcastSettings=new PodcastSettingsEditor(this,application,screenWorker,mainHandler,new PodcastSettingsEditor.Host(){
            public boolean canChangePodcast(){return MainActivity.this.canChangePodcast();}
            public boolean canEditFeedLogin(){return MainActivity.this.canEditFeedLogin();}
            public void changed(){reloadCatalogAfterLocalAction();}
        });
        artworkThumbnailLoader = new ArtworkThumbnailLoader(
                new BoundedArtworkAcquirer(application.getArtworkTransport()),
                mainHandler, 128, getCacheDir().toPath().resolve("artwork-v1"),
                playbackPrivacySettings::isOfflineModeEnabled);
        detailArtworkLoader = new ArtworkThumbnailLoader(
                new BoundedArtworkAcquirer(application.getArtworkTransport()),
                mainHandler, 512, getCacheDir().toPath().resolve("artwork-v1"),
                playbackPrivacySettings::isOfflineModeEnabled);
        podcastLibrary = new PodcastLibraryView(this, findViewById(R.id.podcast_library_rows),
                artworkThumbnailLoader, this::notifyCatalogArtworkReady, this::openPodcast, this::showPodcastMenu);
        archiveLibrary = new PodcastLibraryView(this, findViewById(R.id.archives_rows),
                artworkThumbnailLoader, this::notifyCatalogArtworkReady, this::openPodcast, this::showPodcastMenu);
        batchController=new EpisodeBatchUiController(this,screenWorker,mainHandler,new EpisodeBatchUiController.Host(){
            public boolean canMutate(){return !hasEpisodeActionCompetingWork()&&!isEpisodeActionBusy();}
            public boolean queueDownloads(List<EpisodeListView.Row> rows){return queueSelectedDownloads(rows);}
            public void clearSelection(){cancelEpisodeSelection();}
            public void changed(){
                if(!episodeQueueController.getState().isBusy())episodeQueueController.refresh();
                reloadCatalogAfterLocalAction();refreshLibraryLists();refreshSelectedEpisode();
            }
        });
        podcastEpisodeList = newEpisodeList(R.id.catalog_episodes);
        queueList = newEpisodeList(R.id.episode_queue_entries);
        downloadsPanel = newLibraryPanel(Destination.DOWNLOADS);
        manualDownloads=application.getManualDownloads();
        manualDownloadPanel=new ManualDownloadPanel(this,manualDownloads,this::loadEpisodeDetails,
                id->enqueueManualDownloads(List.of(id),null));
        favoritesPanel = newLibraryPanel(Destination.FAVORITES);
        historyPanel = newLibraryPanel(Destination.HISTORY);
        newEpisodesPanel = newLibraryPanel(Destination.NEW_EPISODES);
        inProgressPanel = newLibraryPanel(Destination.IN_PROGRESS);
        catalogBrowserController = new CatalogBrowserController(
                application.getCatalogBrowserStore(),
                screenWorker,
                command -> mainHandler.post(command),
                this::renderCatalogState);
        subscriptionRefreshController = new SubscriptionRefreshController(
                application.getSubscriptionCoordinator(),
                feedWorker,
                command -> mainHandler.post(command),
                this::renderSubscriptionRefreshState);
        manualRefreshAllController = new ManualRefreshAllController(
                application.getSubscriptionCoordinator(),
                application.getScheduledRefreshStateStore(),
                feedWorker,
                command -> mainHandler.post(command),
                this::renderManualRefreshAllState);
        EpisodeDownloadActions downloadActions = application.getEpisodeDownloadActions();
        if (downloadActions != null) {
            episodeDownloadController = new EpisodeDownloadController(
                    downloadActions,
                    downloadWorker,
                    command -> mainHandler.post(command),
                    this::onEpisodeDownloadStateChanged);
        }
        episodeActionController = new EpisodeActionController(
                application.getEpisodeActionStore(),
                screenWorker,
                command -> mainHandler.post(command),
                this::onEpisodeActionStateChanged);
        chapterLoadController = new ChapterLoadController(
                application.getEpisodeChapterCoordinator(), screenWorker,
                command -> {
                    if (!mainHandler.post(command)) {
                        throw new RejectedExecutionException("Chapter UI callback unavailable.");
                    }
                }, this::onChapterLoadStateChanged);

        refreshCatalogButton.setOnClickListener(ignored -> reloadSavedCatalog());
        refreshAllSubscriptionsButton.setOnClickListener(
                ignored -> refreshAllSubscriptions());
        cancelRefreshAllButton.setOnClickListener(
                ignored -> manualRefreshAllController.cancel());
        loadMoreEpisodesButton.setOnClickListener(ignored -> loadMoreCatalogEpisodes());
        if (restoredQuery != null) {
            episodeFilter=restoredQuery.getFilter();
            catalogEpisodeSearch.setText(restoredQuery.getTitleSearch());
        }
        catalogPodcastSearch.setText(appliedPodcastSearch);
        podcastLiveSearch = new io.github.jnesew.plainpoddroid.ui.LiveSearchField(
                catalogPodcastSearch, mainHandler, this::applyPodcastSearch);
        episodeLiveSearch = new io.github.jnesew.plainpoddroid.ui.LiveSearchField(
                catalogEpisodeSearch, mainHandler, this::applyCatalogQuery,
                catalogBrowserController::invalidateEpisodeRead);
        episodePlaybackButton.setOnClickListener(ignored -> playbackPanel.playEpisode(
                currentEpisodeDetails(), workState().hasPlaybackCompetingWork()));
        findViewById(R.id.episode_queue_header).setOnClickListener(ignored -> {
            EpisodeDetails details = currentEpisodeDetails();
            if (details == null) return;
            if (findQueuePosition(details.getMediaId()) >= 0) removeQueuedEpisode(details.getMediaId());
            else appendQueuedEpisode(details.getMediaId());
        });
        episodePlayedButton.setOnClickListener(ignored -> changeEpisodePlayedState());
        episodeFavoriteButton.setOnClickListener(ignored -> changeEpisodeFavoriteState());
        openEpisodePageButton.setOnClickListener(ignored -> openEpisodePage());
        closeEpisodeDetailsButton.setOnClickListener(ignored -> closeEpisodeDetails());
        loadEpisodeChaptersButton.setOnClickListener(ignored -> loadEpisodeChapters());
        renderCatalogState(catalogBrowserController.getState());
        renderSubscriptionRefreshState(subscriptionRefreshController.getState());
        renderManualRefreshAllState(manualRefreshAllController.getState());
        renderEpisodeDownloadState();
        renderEpisodeActionState(episodeActionController.getState());
        catalogBrowserController.loadSubscriptions();
    }

    private void configureEpisodeQueueUi() {
        episodeQueueProgress = findViewById(R.id.episode_queue_progress);
        episodeQueueStatus = findViewById(R.id.episode_queue_status);
        episodeQueueEntries = findViewById(R.id.episode_queue_entries);
        refreshEpisodeQueueButton = findViewById(R.id.refresh_episode_queue);
        previousQueuePageButton = findViewById(R.id.previous_queue_page);
        nextQueuePageButton = findViewById(R.id.next_queue_page);

        PlainPodDroidApplication application =
                (PlainPodDroidApplication) getApplication();
        episodeQueueController = new EpisodeQueueController(
                application.getEpisodeQueueStore(),
                screenWorker,
                command -> mainHandler.post(command),
                this::onEpisodeQueueStateChanged);
        refreshEpisodeQueueButton.setOnClickListener(ignored -> refreshEpisodeQueue());
        previousQueuePageButton.setOnClickListener(ignored -> showPreviousQueuePage());
        nextQueuePageButton.setOnClickListener(ignored -> showNextQueuePage());

        renderEpisodeQueueState(episodeQueueController.getState());
        episodeQueueController.refresh();
    }

    private void refreshEpisodeQueue() {
        if (episodeQueueController == null
                || episodeQueueController.getState().isBusy()
                || hasQueueCompetingWork()) {
            return;
        }
        episodeQueueController.refresh();
    }

    private void showPreviousQueuePage() {
        if (episodeQueueController == null
                || episodeQueueController.getState().getPhase()
                        != EpisodeQueueController.Phase.READY) {
            return;
        }
        queuePageOffset = Math.max(
                0,
                queuePageOffset - SecurityLimits.QUEUE_BROWSE_PAGE_SIZE);
        renderEpisodeQueueState(episodeQueueController.getState());
    }

    private void showNextQueuePage() {
        if (episodeQueueController == null
                || episodeQueueController.getState().getPhase()
                        != EpisodeQueueController.Phase.READY) {
            return;
        }
        int size = episodeQueueController.getState().getEntries().size();
        int nextOffset = queuePageOffset + SecurityLimits.QUEUE_BROWSE_PAGE_SIZE;
        if (nextOffset < size) {
            queuePageOffset = nextOffset;
            renderEpisodeQueueState(episodeQueueController.getState());
        }
    }

    private void onEpisodeQueueStateChanged(EpisodeQueueController.State state) {
        if (state.getPhase() == EpisodeQueueController.Phase.READY) {
            if (state.getOperation() == EpisodeQueueController.Operation.PLAY_NEXT
                    && state.getMutation() != EpisodeQueueMutation.UNCHANGED) {
                queuePageOffset = 0;
            } else if (state.getOperation() == EpisodeQueueController.Operation.APPEND
                    && state.getMutation() == EpisodeQueueMutation.ADDED
                    && !state.getEntries().isEmpty()) {
                queuePageOffset = ((state.getEntries().size() - 1)
                        / SecurityLimits.QUEUE_BROWSE_PAGE_SIZE)
                        * SecurityLimits.QUEUE_BROWSE_PAGE_SIZE;
            }
        }
        renderEpisodeQueueState(state);
        if (state.getPhase() == EpisodeQueueController.Phase.READY) {
            refreshLibraryLists();
            if (episodeActionController != null) renderEpisodeActionState(episodeActionController.getState());
        }
        renderedCatalogEpisodes = Collections.emptyList();
        if (catalogBrowserController != null) {
            renderCatalogState(catalogBrowserController.getState());
        }
    }

    private void renderEpisodeQueueState(EpisodeQueueController.State state) {
        episodeQueueProgress.setVisibility(state.isBusy() ? View.VISIBLE : View.GONE);
        renderEpisodeQueueEntries(state);

        refreshEpisodeQueueButton.setEnabled(
                !state.isBusy() && !hasQueueCompetingWork());
        previousQueuePageButton.setVisibility(queuePageOffset > 0 ? View.VISIBLE : View.GONE);
        nextQueuePageButton.setVisibility(queuePageOffset + SecurityLimits.QUEUE_BROWSE_PAGE_SIZE
                < state.getEntries().size() ? View.VISIBLE : View.GONE);
        boolean competingWork = hasQueueCompetingWork();
        boolean queueReady = state.getPhase() == EpisodeQueueController.Phase.READY;
        previousQueuePageButton.setEnabled(
                queueReady && !competingWork && queuePageOffset > 0);
        nextQueuePageButton.setEnabled(
                queueReady
                        && !competingWork
                        && queuePageOffset + SecurityLimits.QUEUE_BROWSE_PAGE_SIZE
                                < state.getEntries().size());

        episodeQueueStatus.setVisibility(state.getPhase() == EpisodeQueueController.Phase.ERROR
                || state.getPhase() == EpisodeQueueController.Phase.READY && state.getEntries().isEmpty()
                ? View.VISIBLE : View.GONE);
        episodeQueueProgress.setContentDescription(getString(R.string.episode_queue_status_loading));
        switch (state.getPhase()) {
            case IDLE:
                episodeQueueStatus.setText(R.string.episode_queue_status_idle);
                break;
            case LOADING:
                episodeQueueStatus.setText(R.string.episode_queue_status_loading);
                break;
            case MUTATING:
                episodeQueueStatus.setText(R.string.episode_queue_status_updating);
                break;
            case READY:
                showReadyQueueStatus(state);
                break;
            case ERROR:
                showEpisodeQueueFailure(state.getFailure());
                break;
            default:
                throw new AssertionError("Unknown episode queue phase.");
        }
    }

    private void renderEpisodeQueueEntries(EpisodeQueueController.State state) {
        if (state.isBusy() && queueList.hasRows()) return;
        List<EpisodeQueueEntry> entries = state.getEntries();
        if (entries.isEmpty()) queuePageOffset = 0;
        else if (queuePageOffset >= entries.size()) queuePageOffset = ((entries.size() - 1)
                / SecurityLimits.QUEUE_BROWSE_PAGE_SIZE) * SecurityLimits.QUEUE_BROWSE_PAGE_SIZE;
        int end = Math.min(entries.size(), queuePageOffset + SecurityLimits.QUEUE_BROWSE_PAGE_SIZE);
        queueList.clear();
        for (int index = queuePageOffset; index < end; index++) {
            EpisodeQueueEntry entry = entries.get(index);
            queueList.add(new EpisodeListView.Row(entry.getMediaId(), displayTitle(entry.getTitle()),
                    episodeMetadata.queue(entry), entry.getPodcast(), entry.isDownloaded(), entry.isPlayed(),
                    entry.getDurationMillis(), entry.getPlaybackPositionMillis(), entry.getPosition()), true, true);
        }
        queueList.refreshArtwork(); queueList.refreshPlayback();
    }

    private void showReadyQueueStatus(EpisodeQueueController.State state) {
        episodeQueueStatus.setText(R.string.episode_queue_status_empty);
        TextView page = findViewById(R.id.queue_page_summary);
        boolean paged = state.getEntries().size() > SecurityLimits.QUEUE_BROWSE_PAGE_SIZE;
        page.setVisibility(paged ? View.VISIBLE : View.GONE);
        if (paged) page.setText(getString(R.string.ui_page_number,
                queuePageOffset / SecurityLimits.QUEUE_BROWSE_PAGE_SIZE + 1,
                (state.getEntries().size() + SecurityLimits.QUEUE_BROWSE_PAGE_SIZE - 1)
                        / SecurityLimits.QUEUE_BROWSE_PAGE_SIZE));
    }

    private void showEpisodeQueueFailure(EpisodeQueueController.Failure failure) {
        switch (failure) {
            case INVALID_MEDIA_ID:
            case INVALID_POSITION:
                episodeQueueStatus.setText(R.string.episode_queue_error_selection);
                break;
            case EPISODE_NOT_FOUND:
                episodeQueueStatus.setText(R.string.episode_queue_error_missing);
                break;
            case QUEUE_FULL:
                episodeQueueStatus.setText(R.string.episode_queue_error_full);
                break;
            case NOT_QUEUED:
                episodeQueueStatus.setText(R.string.episode_queue_error_not_queued);
                break;
            case CORRUPT_STATE:
            case STORAGE_FAILED:
                episodeQueueStatus.setText(R.string.episode_queue_error_storage);
                break;
            case BACKGROUND_WORK_UNAVAILABLE:
                episodeQueueStatus.setText(R.string.episode_queue_error_background);
                break;
            case NONE:
            default:
                episodeQueueStatus.setText(R.string.episode_queue_error_unknown);
                break;
        }
    }

    private void appendQueuedEpisode(String mediaId) {
        if (episodeQueueController == null || hasQueueCompetingWork()) {
            return;
        }
        episodeQueueController.append(mediaId);
    }

    private void playQueuedEpisodeNext(String mediaId) {
        if (episodeQueueController == null || hasQueueCompetingWork()) {
            return;
        }
        episodeQueueController.playNext(mediaId);
    }

    private void removeQueuedEpisode(String mediaId) {
        if (episodeQueueController == null || hasQueueCompetingWork()) {
            return;
        }
        episodeQueueController.remove(mediaId);
    }

    private void moveQueuedEpisode(String mediaId, int targetPosition) {
        if (episodeQueueController == null || hasQueueCompetingWork()) {
            return;
        }
        episodeQueueController.move(mediaId, targetPosition);
    }

    private void reloadSavedCatalog() {
        if (isSavingPodcastSettings() || isAnyFeedRefreshBusy()
                || isSubscriptionWorkflowBusy()
                || isAnyMediaWorkBusy()) {
            return;
        }
        resetEpisodeDetails();
        subscriptionRefreshController.reset();
        manualRefreshAllController.reset();
        catalogBrowserController.loadSubscriptions();
    }

    private boolean canChangePodcast() {
        return !removalBusy&&!isOpmlBusy()&&!isAnyFeedRefreshBusy()&&!isSubscriptionWorkflowBusy()
                &&!isAnyMediaWorkBusy()&&!isCatalogBusy()&&!isSavingPodcastSettings();
    }

    private void confirmPodcastRemoval(CatalogSubscriptionSummary podcast,boolean archive) {
        if(!canChangePodcast()){Toast.makeText(this,R.string.ui_work_busy,Toast.LENGTH_SHORT).show();return;}
        new AlertDialog.Builder(this)
                .setTitle(archive?R.string.ui_archive_title:R.string.ui_delete_podcast_title)
                .setMessage(displayTitle(podcast.getTitle())+"\n\n"+getString(archive?R.string.ui_archive_message:R.string.ui_delete_podcast_message))
                .setNegativeButton(android.R.string.cancel,null)
                .setPositiveButton(archive?R.string.ui_archive_podcast:R.string.ui_delete_confirm,
                        (dialog,which)->removeSubscription(podcast.getSubscriptionId(),archive)).show();
    }

    private void removeSubscription(long subscriptionId, boolean keepDownloads) {
        if (!canChangePodcast()) { Toast.makeText(this,R.string.ui_work_busy,Toast.LENGTH_SHORT).show();return; }
        if (!keepDownloads && !playbackPanel.isConnected()) {
            subscriptionRemovalStatus.setVisibility(View.VISIBLE);
            subscriptionRemovalStatus.setText(R.string.subscription_remove_error);
            Toast.makeText(this,R.string.subscription_remove_error,Toast.LENGTH_LONG).show();
            return;
        }
        EpisodeDetails displayed=currentEpisodeDetails();
        if(displayed!=null&&displayed.getPodcast()!=null&&displayed.getPodcast().subscriptionId()==subscriptionId)resetEpisodeDetails();
        removalBusy = true;removingPodcastId=subscriptionId;
        int generation = ++removalGeneration;
        subscriptionRemovalStatus.setVisibility(View.VISIBLE);
        subscriptionRemovalStatus.setText(R.string.subscription_remove_working);
        renderCatalogState(catalogBrowserController.getState());
        PlainPodDroidApplication application =
                (PlainPodDroidApplication) getApplication();
        new io.github.jnesew.plainpoddroid.core.playback.PodcastRemovalPlaybackGuard(
                screenWorker,mainHandler::post,playbackPanel::currentMediaId,id->{
                    EpisodeDetails playing=application.getEpisodeActionStore().findDetails(id);
                    if(playing==null||playing.getPodcast()==null)throw new IOException("Playback membership unavailable.");
                    return playing.getPodcast().subscriptionId();
                },playbackPanel::stopPlayback).prepare(subscriptionId,
                ()->!isDestroyed()&&generation==removalGeneration,
                ()->performSubscriptionRemoval(application,subscriptionId,keepDownloads,generation),
                ()->finishSubscriptionRemoval(generation,R.string.subscription_remove_error));
    }

    private void performSubscriptionRemoval(PlainPodDroidApplication application,long subscriptionId,boolean keepDownloads,int generation){
        try {
            screenWorker.execute(() -> {
                boolean removed;
                try {
                    SubscriptionRemovalCoordinator coordinator =
                            new SubscriptionRemovalCoordinator(
                                    application.getSubscriptionRemovalStore(),
                                    mediaId -> application.getPlaybackDownloadStore()
                                            .delete(mediaId));
                    removed = coordinator.remove(subscriptionId, keepDownloads);
                } catch (io.github.jnesew.plainpoddroid.core.download.DownloadInUseException exception) {
                    mainHandler.post(() -> finishSubscriptionRemoval(generation, R.string.subscription_remove_in_use));
                    return;
                } catch (IOException | RuntimeException exception) {
                    mainHandler.post(() -> finishSubscriptionRemoval(generation,
                            R.string.subscription_remove_error));
                    return;
                }
                mainHandler.post(() -> finishSubscriptionRemoval(generation,
                        removed ? (keepDownloads ? R.string.subscription_remove_kept
                                : R.string.subscription_remove_deleted)
                                : R.string.subscription_remove_missing));
            });
        } catch (RejectedExecutionException exception) {
            finishSubscriptionRemoval(generation, R.string.subscription_remove_background);
        }
    }

    private void finishSubscriptionRemoval(int generation, int messageId) {
        if (isDestroyed() || generation != removalGeneration) return;
        removalBusy = false;
        subscriptionRemovalStatus.setText(messageId);
        Toast.makeText(this,messageId,Toast.LENGTH_SHORT).show();
        catalogBrowserController.loadSubscriptions();
        if (episodeQueueController != null) episodeQueueController.refresh();
        if (settingsPanel != null) settingsPanel.refreshStorage();
        renderCatalogState(catalogBrowserController.getState());
    }

    private void refreshAllSubscriptions() {
        if (!allowNetworkAction()) return;
        if (isSelectedSubscriptionRefreshBusy()
                || isSubscriptionWorkflowBusy()
                || isAnyMediaWorkBusy()) {
            return;
        }
        resetEpisodeDetails();
        subscriptionRefreshController.reset();
        manualRefreshAllController.refreshAll();
    }

    private void loadMoreCatalogEpisodes() {
        if (workState().hasCatalogReadCompetingWork()) {
            return;
        }
        CatalogBrowserController.State state = catalogBrowserController.getState();
        if (state.getEpisodes().size()
                >= SecurityLimits.CATALOG_BROWSE_VISIBLE_EPISODE_LIMIT) {
            return;
        }
        catalogBrowserController.loadMore();
    }

    private void applyCatalogQuery() {
        CatalogBrowseQuery query;
        try {
            query = new CatalogBrowseQuery(episodeFilter,
                    catalogEpisodeSearch.getText().toString());
        } catch (IllegalArgumentException exception) {
            catalogEpisodeSearch.setError(getString(R.string.catalog_search_invalid));
            // End an invalidated read by restoring the last valid local query.
            CatalogBrowseQuery previous=catalogBrowserController.getQuery();
            if(workState().hasCatalogReadCompetingWork())pendingSearchQuery=previous;
            else {pendingSearchQuery=null;catalogBrowserController.applyQuery(previous);}
            return;
        }
        catalogEpisodeSearch.setError(null);
        restoredQuery = null;
        if (workState().hasCatalogReadCompetingWork()) {
            pendingSearchQuery = query;
            return;
        }
        pendingSearchQuery = null;
        catalogBrowserController.applyQuery(query);
    }

    private void applyPodcastSearch() {
        try {
            appliedPodcastSearch = CatalogSubscriptionSearch.validate(
                    catalogPodcastSearch.getText().toString());
        } catch (IllegalArgumentException exception) {
            catalogPodcastSearch.setError(getString(R.string.catalog_search_invalid));
            return;
        }
        catalogPodcastSearch.setError(null);
        renderCatalogSubscriptions(catalogBrowserController.getState());
    }

    private void renderCatalogState(CatalogBrowserController.State state) {
        CatalogBrowserController.Phase phase = state.getPhase();
        if (newEpisodesPanel != null && (phase == CatalogBrowserController.Phase.SUBSCRIPTIONS_READY
                || phase == CatalogBrowserController.Phase.EMPTY)) newEpisodesPanel.refresh();
        boolean busy = phase == CatalogBrowserController.Phase.LOADING_SUBSCRIPTIONS
                || phase == CatalogBrowserController.Phase.LOADING_EPISODES
                || phase == CatalogBrowserController.Phase.LOADING_MORE;
        boolean networkBusy = isAnyFeedRefreshBusy()
                || isSubscriptionWorkflowBusy()
                || isAnyMediaWorkBusy();
        refreshCatalogButton.setEnabled(!busy && !networkBusy);
        catalogProgress.setVisibility(busy ? View.VISIBLE : View.GONE);

        renderCatalogSubscriptions(state);
        renderCatalogEpisodes(state.getEpisodes());
        if (navigationShell.current().destination() == Destination.PODCAST_EPISODES
                && navigationShell.current().podcastId() == state.getSelectedSubscriptionId())
            catalogBrowserController.acknowledgeViewed();

        catalogPodcastSearch.setEnabled(true);
        boolean canQuery = !workState().hasCatalogReadCompetingWork()
                && state.getSelectedSubscriptionId() > 0L;
        // Typing is independent of conflicting work; validated reads can be deferred.
        catalogEpisodeSearch.setEnabled(true);
        int[] chips = {R.id.filter_all, R.id.filter_unplayed, R.id.filter_downloaded};
        for (int i = 0; i < chips.length; i++) {
            Button chip = findViewById(chips[i]);
            boolean selected = catalogBrowserController.getQuery().getFilter().ordinal() == i;
            chip.setSelected(selected); chip.setEnabled(canQuery);
            chip.setBackgroundResource(selected ? R.drawable.button_surface : R.drawable.icon_surface);
            chip.setTypeface(null, selected ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
        int activeFilter=catalogBrowserController.getQuery().getFilter().ordinal();
        if(activeFilter!=renderedEpisodeFilter){
            renderedEpisodeFilter=activeFilter;View selectedChip=findViewById(chips[activeFilter]);
            selectedChip.post(()->{if(selectedChip.isShown())selectedChip.requestRectangleOnScreen(
                    new android.graphics.Rect(0,0,selectedChip.getWidth(),selectedChip.getHeight()),true);});
        }
        boolean canLoadMore = phase == CatalogBrowserController.Phase.EPISODES_READY
                && state.hasMore()
                && !workState().hasCatalogReadCompetingWork()
                && state.getEpisodes().size()
                        < SecurityLimits.CATALOG_BROWSE_VISIBLE_EPISODE_LIMIT;
        loadMoreEpisodesButton.setVisibility(canLoadMore ? View.VISIBLE : View.GONE);
        loadMoreEpisodesButton.setEnabled(canLoadMore);
        showCatalogStatus(state);
        catalogStatus.setVisibility(phase == CatalogBrowserController.Phase.ERROR ? View.VISIBLE : View.GONE);
        findViewById(R.id.podcast_library_rows).setVisibility(
                phase == CatalogBrowserController.Phase.LOADING_SUBSCRIPTIONS && state.getSubscriptions().isEmpty()
                        ? View.GONE : View.VISIBLE);
        catalogProgress.setContentDescription(getString(R.string.catalog_status_loading_subscriptions));
        TextView episodeStatus = findViewById(R.id.podcast_episode_status);
        episodeStatus.setText(catalogStatus.getText());
        episodeStatus.setVisibility(!busy && (phase == CatalogBrowserController.Phase.ERROR
                || phase == CatalogBrowserController.Phase.EPISODES_READY && state.getEpisodes().isEmpty())
                ? View.VISIBLE : View.GONE);
        findViewById(R.id.podcast_episode_progress).setContentDescription(getString(R.string.catalog_status_loading_episodes));
        findViewById(R.id.podcast_episode_progress).setVisibility(busy ? View.VISIBLE : View.GONE);
        if (navigationShell.current().destination() == Destination.PODCAST_EPISODES) {
            ensurePodcastSelection();
        }
        if (restoredQuery != null && phase == CatalogBrowserController.Phase.EPISODES_READY
                && catalogBrowserController.getState() == state) {
            CatalogBrowseQuery requested = restoredQuery; restoredQuery = null; catalogBrowserController.applyQuery(requested);
        }
        renderChapterLoadState();
        if (subscriptionRefreshController != null) {
            renderSubscriptionRefreshControls(
                    subscriptionRefreshController.getState(),
                    state);
        }
        if (manualRefreshAllController != null) {
            renderManualRefreshAllControls(
                    manualRefreshAllController.getState(),
                    state);
        }
        updateSubscriptionInputEnabled();
        continuePendingCatalogReload(state);
        if (pendingSearchQuery != null && !workState().hasCatalogReadCompetingWork()) {
            CatalogBrowseQuery requested = pendingSearchQuery;
            pendingSearchQuery = null;
            catalogBrowserController.applyQuery(requested);
        }
        if (episodeActionController != null) renderEpisodeActionState(episodeActionController.getState());
    }

    private boolean isSelectedSubscriptionRefreshBusy() {
        return subscriptionRefreshController != null
                && subscriptionRefreshController.getState().getPhase()
                        == SubscriptionRefreshController.Phase.REFRESHING;
    }

    private boolean isManualRefreshAllBusy() {
        return manualRefreshAllController != null
                && manualRefreshAllController.getState().getPhase()
                        == ManualRefreshAllController.Phase.REFRESHING;
    }

    private boolean isAnyFeedRefreshBusy() {
        return isSelectedSubscriptionRefreshBusy() || isManualRefreshAllBusy();
    }

    private boolean isManualDownloadActive(){
        return manualDownloads!=null&&manualDownloads.snapshot().entries().stream()
                .anyMatch(entry->entry.state()==io.github.jnesew.plainpoddroid.core.download.ManualDownloadQueue.State.ACTIVE);
    }
    private boolean isEpisodeDownloadBusy() {
        return episodeDownloadController != null
                && episodeDownloadController.getState().isBusy();
    }

    private boolean isDownloadStorageBusy() {
        return settingsPanel != null && settingsPanel.isStorageBusy();
    }

    private boolean isEpisodeQueueBusy() {
        return episodeQueueController != null
                && episodeQueueController.getState().isBusy();
    }

    private boolean isEpisodeActionBusy() {
        if(batchController!=null&&batchController.isBusy())return true;
        return episodeActionController != null
                && episodeActionController.getState().isBusy();
    }

    private boolean isCatalogBusy() {
        if (catalogBrowserController == null) {
            return false;
        }
        CatalogBrowserController.Phase phase = catalogBrowserController
                .getState()
                .getPhase();
        return phase == CatalogBrowserController.Phase.LOADING_SUBSCRIPTIONS
                || phase == CatalogBrowserController.Phase.LOADING_EPISODES
                || phase == CatalogBrowserController.Phase.LOADING_MORE;
    }

    private UiWorkState workState() {
        return new UiWorkState(isAnyFeedRefreshBusy(), isSubscriptionWorkflowBusy(),
                isCatalogBusy(), isEpisodeDownloadBusy(), isDownloadStorageBusy(),
                isEpisodeQueueBusy(), isEpisodeActionBusy(), isChapterLoadBusy(), removalBusy);
    }

    private boolean hasQueueCompetingWork() {
        return workState().hasQueueCompetingWork();
    }

    private boolean hasEpisodeActionCompetingWork() {
        return workState().hasEpisodeActionCompetingWork();
    }

    private boolean isAnyMediaWorkBusy() {
        return workState().isAnyMediaWorkBusy();
    }

    private boolean isChapterLoadBusy() {
        return chapterLoadController != null && chapterLoadController.getState().isBusy();
    }

    private boolean isSubscriptionWorkflowBusy() {
        if (subscriptionController == null) {
            return false;
        }
        SubscriptionUiController.Phase phase = subscriptionController.getState().getPhase();
        return phase == SubscriptionUiController.Phase.PREVIEWING
                || phase == SubscriptionUiController.Phase.CONFIRMING;
    }

    private void renderSubscriptionRefreshState(
            SubscriptionRefreshController.State state) {
        renderCatalogState(catalogBrowserController.getState());
        if (state.getPhase() == SubscriptionRefreshController.Phase.SUCCEEDED) {
            catalogBrowserController.loadSubscriptions();
        } else if(state.getPhase()==SubscriptionRefreshController.Phase.ERROR){
            Toast.makeText(this,subscriptionRefreshStatus.getText(),Toast.LENGTH_SHORT).show();
        }
    }

    private void renderManualRefreshAllState(
            ManualRefreshAllController.State state) {
        renderCatalogState(catalogBrowserController.getState());
        if (state.getPhase() == ManualRefreshAllController.Phase.COMPLETED) {
            catalogBrowserController.loadSubscriptions();
        }
    }

    private void renderSubscriptionRefreshControls(
            SubscriptionRefreshController.State refreshState,
            CatalogBrowserController.State catalogState) {
        boolean refreshing = refreshState.getPhase()
                == SubscriptionRefreshController.Phase.REFRESHING;
        boolean selected=refreshState.getSubscriptionId()==catalogState.getSelectedSubscriptionId();
        subscriptionRefreshProgress.setVisibility(refreshing&&selected ? View.VISIBLE : View.GONE);
        subscriptionRefreshStatus.setVisibility(selected&&refreshState.getPhase() == SubscriptionRefreshController.Phase.ERROR
                ? View.VISIBLE : View.GONE);
        subscriptionRefreshProgress.setContentDescription(getString(R.string.subscription_refresh_status_refreshing));

        switch (refreshState.getPhase()) {
            case IDLE:
                subscriptionRefreshStatus.setText(null);
                break;
            case REFRESHING:
                subscriptionRefreshStatus.setText(
                        R.string.subscription_refresh_status_refreshing);
                break;
            case SUCCEEDED:
                subscriptionRefreshStatus.setText(
                        R.string.subscription_refresh_status_succeeded);
                break;
            case ERROR:
                showSubscriptionRefreshFailure(refreshState.getFailure());
                break;
            default:
                throw new AssertionError("Unknown subscription refresh phase.");
        }
    }

    private void renderManualRefreshAllControls(
            ManualRefreshAllController.State refreshState,
            CatalogBrowserController.State catalogState) {
        boolean refreshing = refreshState.getPhase()
                == ManualRefreshAllController.Phase.REFRESHING;
        boolean catalogBusy = catalogState.getPhase()
                        == CatalogBrowserController.Phase.LOADING_SUBSCRIPTIONS
                || catalogState.getPhase()
                        == CatalogBrowserController.Phase.LOADING_EPISODES
                || catalogState.getPhase()
                        == CatalogBrowserController.Phase.LOADING_MORE;
        boolean hasSubscriptions = false;
        for (CatalogSubscriptionSummary subscription : catalogState.getSubscriptions()) {
            if (subscription.isSubscribed()) {
                hasSubscriptions = true;
                break;
            }
        }
        boolean competingWork = isSelectedSubscriptionRefreshBusy()
                || isSubscriptionWorkflowBusy()
                || isAnyMediaWorkBusy();

        refreshAllSubscriptionsButton.setVisibility(
                hasSubscriptions || refreshing ? View.VISIBLE : View.GONE);
        refreshAllSubscriptionsButton.setEnabled(
                hasSubscriptions && !catalogBusy && !competingWork && !refreshing);
        cancelRefreshAllButton.setVisibility(refreshing ? View.VISIBLE : View.GONE);
        cancelRefreshAllButton.setEnabled(refreshing);
        refreshAllProgress.setVisibility(refreshing ? View.VISIBLE : View.GONE);
        refreshAllStatus.setVisibility(refreshState.getPhase() == ManualRefreshAllController.Phase.ERROR
                ? View.VISIBLE : View.GONE);
        refreshAllStatus.setEnabled(!catalogBusy && !competingWork && !refreshing);
        refreshAllProgress.setContentDescription(getString(R.string.refresh_all_status_refreshing));
        boolean details=refreshState.getPhase()==ManualRefreshAllController.Phase.COMPLETED
                && refreshState.getResult()!=null && !refreshState.getResult().getFailureDetails().isEmpty();
        refreshAllStatus.setClickable(details);refreshAllStatus.setFocusable(details);

        switch (refreshState.getPhase()) {
            case IDLE:
                refreshAllStatus.setText(null);
                break;
            case REFRESHING:
                refreshAllStatus.setText(R.string.refresh_all_status_refreshing);
                break;
            case COMPLETED:
                showManualRefreshAllResult(refreshState.getResult());
                break;
            case ERROR:
                refreshAllStatus.setText(refreshState.getFailure()
                        == ManualRefreshAllController.Failure.BACKGROUND_WORK_UNAVAILABLE
                        ? R.string.refresh_all_error_background
                        : R.string.refresh_all_error_storage);
                break;
            default:
                throw new AssertionError("Unknown refresh-all phase.");
        }
    }

    private void showManualRefreshAllResult(ManualRefreshAllResult result) {
        if (result == null) throw new AssertionError("Completed refresh result is absent.");
        int failures=result.getFailureDetails().size();
        refreshAllStatus.setVisibility(failures>0?View.VISIBLE:View.GONE);
        if(failures>0) refreshAllStatus.setText(getResources().getQuantityString(
                R.plurals.ui_refresh_failed,failures,failures));
    }

    private void showRefreshFailures() {
        var state=manualRefreshAllController.getState();
        if(state.getPhase()!=ManualRefreshAllController.Phase.COMPLETED || state.getResult()==null
                || state.getResult().getFailureDetails().isEmpty()) return;
        List<String> labels=new ArrayList<>();
        for(var failure:state.getResult().getFailureDetails()) {
            String title=getString(R.string.ui_removed_podcast);
            for(var podcast:catalogBrowserController.getState().getSubscriptions())
                if(podcast.getSubscriptionId()==failure.subscriptionId()){title=displayTitle(podcast.getTitle());break;}
            int reason=switch(failure.reason()) {
                case FETCH_FAILED -> R.string.ui_refresh_fetch_failed;
                case LOGIN_REQUIRED -> R.string.login_needed;
                case ACCESS_DENIED -> R.string.login_denied;
                case INVALID_FEED -> R.string.ui_refresh_invalid_feed;
                case STORAGE_FAILED -> R.string.ui_refresh_storage_failed;
                case ALREADY_RUNNING -> R.string.ui_refresh_already_running;
                case SUBSCRIPTION_NOT_FOUND -> R.string.ui_refresh_removed;
            };
            labels.add(getString(R.string.ui_refresh_failure_item,title,getString(reason)));
        }
        new AlertDialog.Builder(this).setTitle(R.string.ui_refresh_details)
                .setItems(labels.toArray(new String[0]),(dialog,index)-> {
                    long id=state.getResult().getFailureDetails().get(index).subscriptionId();
                    for(var podcast:catalogBrowserController.getState().getSubscriptions())
                        if(podcast.getSubscriptionId()==id){openPodcast(podcast);break;}
                })
                .setNegativeButton(android.R.string.cancel,null)
                .setPositiveButton(R.string.ui_retry,(dialog,which)-> {
                    if(isAnyFeedRefreshBusy()||isSubscriptionWorkflowBusy()||isAnyMediaWorkBusy())return;
                    subscriptionRefreshController.reset();manualRefreshAllController.retryFailures();
                }).show();
    }

    private void showSubscriptionRefreshFailure(
            SubscriptionRefreshController.Failure failure) {
        switch (failure) {
            case INVALID_SELECTION:
                subscriptionRefreshStatus.setText(
                        R.string.subscription_refresh_error_selection);
                break;
            case SUBSCRIPTION_NOT_FOUND:
                subscriptionRefreshStatus.setText(
                        R.string.subscription_refresh_error_missing);
                break;
            case ALREADY_RUNNING:
                subscriptionRefreshStatus.setText(
                        R.string.subscription_refresh_error_running);
                break;
            case LOGIN_REQUIRED:
                subscriptionRefreshStatus.setText(R.string.login_needed); break;
            case ACCESS_DENIED:
                subscriptionRefreshStatus.setText(R.string.login_denied); break;
            case FETCH_FAILED:
                subscriptionRefreshStatus.setText(
                        R.string.subscription_refresh_error_fetch);
                break;
            case INVALID_FEED:
                subscriptionRefreshStatus.setText(
                        R.string.subscription_refresh_error_feed);
                break;
            case STORAGE_FAILED:
                subscriptionRefreshStatus.setText(
                        R.string.subscription_refresh_error_storage);
                break;
            case BACKGROUND_WORK_UNAVAILABLE:
                subscriptionRefreshStatus.setText(
                        R.string.subscription_refresh_error_background);
                break;
            case NONE:
            default:
                subscriptionRefreshStatus.setText(
                        R.string.subscription_refresh_error_unknown);
                break;
        }
    }

    private void renderCatalogSubscriptions(CatalogBrowserController.State state) {
        List<CatalogSubscriptionSummary> visible = CatalogSubscriptionSearch.filter(
                state.getSubscriptions(),appliedPodcastSearch,-1L);
        visible = new LibraryBrowseSettings(this).getOrder().sorted(visible);
        boolean searching=!appliedPodcastSearch.isEmpty();
        if (!libraryRendered || renderedPodcastSearching!=searching || !renderedLibrarySubscriptions.equals(visible)) {
            podcastLibrary.render(visible,false,searching);
            renderedLibrarySubscriptions=new ArrayList<>(visible);libraryRendered=true;renderedPodcastSearching=searching;
        }
        var archives = new LibraryBrowseSettings(this).getOrder().sorted(state.getSubscriptions());
        if(!archivesRendered || !renderedArchiveSubscriptions.equals(archives)) {
            archiveLibrary.render(archives,true);
            renderedArchiveSubscriptions=new ArrayList<>(archives);archivesRendered=true;
        }
        int active = 0;
        for (CatalogSubscriptionSummary podcast : state.getSubscriptions()) if (podcast.isSubscribed()) active++;
        navigationShell.setPodcastCount(active);
        for (CatalogSubscriptionSummary podcast : state.getSubscriptions()) {
            if (podcast.getSubscriptionId() != state.getSelectedSubscriptionId()) continue;
            ((TextView) findViewById(R.id.podcast_header_title)).setText(displayTitle(podcast.getTitle()));
            ((TextView) findViewById(R.id.podcast_header_summary)).setText(getResources().getQuantityString(R.plurals.ui_podcast_counts,
                    podcast.getEpisodeCount(), podcast.getEpisodeCount(), podcast.getUnplayedCount()));
            if(findViewById(R.id.podcast_header_art).isShown())
                UiWidgets.setArtwork(findViewById(R.id.podcast_header_art),detailArtworkLoader.getOrRequest(
                        podcast.getArtworkUrl(),this::notifyCatalogArtworkReady));
        }
    }

    private void notifyCatalogArtworkReady(){if(artworkViewport!=null)artworkViewport.request();}

    private void refreshVisibleArtwork() {
        if (isDestroyed()) return;
        if (podcastLibrary != null) podcastLibrary.refreshArtwork();
        if (archiveLibrary != null) archiveLibrary.refreshArtwork();
        if (podcastEpisodeList != null) podcastEpisodeList.refreshArtwork();
        if (queueList != null) queueList.refreshArtwork();
        if (downloadsPanel != null) downloadsPanel.refreshArtwork();
        if (favoritesPanel != null) favoritesPanel.refreshArtwork();
        if (historyPanel != null) historyPanel.refreshArtwork();
        if (newEpisodesPanel != null) newEpisodesPanel.refreshArtwork();
        if (inProgressPanel != null) inProgressPanel.refreshArtwork();
        if (subscriptionController != null) renderSubscriptionArtwork(subscriptionController.getState().getPreview());
        if(catalogBrowserController!=null&&findViewById(R.id.podcast_header_art).isShown())
            for(var podcast:catalogBrowserController.getState().getSubscriptions())
                if(podcast.getSubscriptionId()==catalogBrowserController.getState().getSelectedSubscriptionId()){
                    UiWidgets.setArtwork(findViewById(R.id.podcast_header_art),detailArtworkLoader.getOrRequest(
                            podcast.getArtworkUrl(),this::notifyCatalogArtworkReady));break;
                }
        renderEpisodeArtwork(); renderMiniArtwork();
    }

    private boolean selectedCatalogArchive() {
        if (catalogBrowserController == null) return false;
        CatalogBrowserController.State state = catalogBrowserController.getState();
        for (CatalogSubscriptionSummary subscription : state.getSubscriptions()) {
            if (subscription.getSubscriptionId() == state.getSelectedSubscriptionId()) {
                return !subscription.isSubscribed();
            }
        }
        return false;
    }

    private void renderCatalogEpisodes(List<CatalogEpisodeSummary> episodes) {
        if (renderedCatalogEpisodes.equals(episodes)) {podcastEpisodeList.refreshPlayback();return;}
        podcastEpisodeList.clear();
        int count = Math.min(episodes.size(), SecurityLimits.CATALOG_BROWSE_VISIBLE_EPISODE_LIMIT);
        for (int index = 0; index < count; index++) {
            CatalogEpisodeSummary episode = episodes.get(index);
            String caption = episodeMetadata.catalog(episode);
            podcastEpisodeList.add(new EpisodeListView.Row(episode.getMediaId(), episodeDisplayTitle(episode),
                    caption, episode.getPodcast(), episode.isDownloaded(), episode.isPlayed(),
                    episode.getDurationMillis(), episode.getPlaybackPositionMillis(), -1), false, false);
        }
        podcastEpisodeList.refreshPlayback(); renderedCatalogEpisodes = new ArrayList<>(episodes);
    }

    private int findQueuePosition(String mediaId) {
        if (episodeQueueController == null) {
            return -1;
        }
        for (EpisodeQueueEntry entry : episodeQueueController.getState().getEntries()) {
            if (entry.getMediaId().equals(mediaId)) {
                return entry.getPosition();
            }
        }
        return -1;
    }

    private String episodeDisplayTitle(CatalogEpisodeSummary episode) {
        return episode.getTitle().isEmpty()
                ? getString(R.string.catalog_episode_untitled)
                : episode.getTitle();
    }

    private void onAudioSettingsChanged() {
        if (playbackPrivacySettings.isOfflineModeEnabled()) {
            subscriptionController.cancel();
            manualRefreshAllController.cancel();
        }
        if(manualDownloads!=null)manualDownloads.refresh();
        renderCatalogState(catalogBrowserController.getState());
        refreshEpisodePlaybackButton();
    }

    private void downloadEpisode(String mediaId,String title) {
        if(manualDownloads==null||workState().hasDownloadCompetingWork(false))return;
        enqueueManualDownloads(List.of(mediaId),title);
    }

    private void enqueueManualDownloads(List<String> ids,String title) {
        if(manualDownloads==null)return;
        manualDownloads.enqueue(ids,result->{
            if(isDestroyed())return;
            int error=switch(result.error()){
                case OFFLINE->R.string.episode_download_error_offline;case FULL->R.string.episode_download_queue_full;
                case UNAVAILABLE->R.string.episode_download_error_missing;case STORAGE->R.string.episode_download_error_storage;
                case SCHEDULER->R.string.ui_download_start_failed;case NONE->R.string.episode_download_error_complete;
            };
            String text=result.accepted()>0?(result.accepted()<result.total()
                    ?getResources().getQuantityString(R.plurals.ui_downloads_queued_partial,result.total(),result.accepted(),result.total())
                    :title!=null&&ids.size()==1?getString(R.string.ui_download_queued_title,displayTitle(title))
                    :getResources().getQuantityString(R.plurals.ui_batch_downloads,result.accepted(),result.accepted())):getString(error);
            Toast.makeText(this,text,Toast.LENGTH_SHORT).show();
        });
    }

    private void onManualDownloadsChanged(){
        if(isDestroyed()||manualDownloads==null)return;
        var next=manualDownloads.snapshot();
        boolean finished=manualDownloadEntries.stream().anyMatch(old->next.entries().stream().noneMatch(entry->entry.request().id()==old.request().id()));
        manualDownloadEntries=next.entries();manualDownloadPanel.render(next);
        refreshRowPlayback();
        refreshEpisodePlaybackButton();
        if(settingsPanel!=null)settingsPanel.renderStorage();
        if(finished){reloadCatalogAfterLocalAction();refreshLibraryLists();refreshSelectedEpisode();
            if(settingsPanel!=null)settingsPanel.refreshStorage();}
    }

    private void deleteEpisodeDownload(String mediaId) {
        if (episodeDownloadController == null
                || isAnyFeedRefreshBusy()
                || isSubscriptionWorkflowBusy()
                || isDownloadStorageBusy()
                || isEpisodeQueueBusy()
                || isEpisodeActionBusy()
                || isChapterLoadBusy()
                || isCatalogBusy()) {
            return;
        }
        episodeDownloadController.delete(mediaId);
    }

    private void loadEpisodeDetails(String mediaId) {
        navigationShell.openEpisode(mediaId);
    }

    private void changeEpisodePlayedState() {
        EpisodeDetails details = currentEpisodeDetails();
        if (details == null || hasEpisodeActionCompetingWork()) {
            return;
        }
        episodeActionController.setPlayed(details.getMediaId(), !details.isPlayed());
    }

    private void changeEpisodeFavoriteState() {
        EpisodeDetails details = currentEpisodeDetails();
        if (details == null || hasEpisodeActionCompetingWork()) {
            return;
        }
        episodeActionController.setFavorite(details.getMediaId(), !details.isFavorite());
    }

    private boolean episodeArchived(EpisodeDetails episode) {
        return episode.getPodcast() == null ? selectedCatalogArchive()
                : !episode.getPodcast().subscribed();
    }

    private EpisodeDetails currentEpisodeDetails() {
        if (episodeActionController == null
                || episodeActionController.getState().isBusy()) {
            return null;
        }
        return displayedEpisodeDetails();
    }

    private EpisodeDetails displayedEpisodeDetails() {
        if (episodeActionController == null) return null;
        EpisodeDetails details = episodeActionController.getState().getDetails();
        if (details != null && navigationShell.current().destination() == Destination.EPISODE
                && !details.getMediaId().equals(navigationShell.current().episodeId())) return null;
        return details;
    }

    private void closeEpisodeDetails() {
        navigationShell.back();
    }

    private void resetEpisodeDetails() {
        chapterLoadController.reset();
        episodeActionController.reset();
    }

    private void openEpisodePage() {
        EpisodeDetails details = currentEpisodeDetails();
        if (details == null
                || details.getWebsiteUrl() == null
                || hasEpisodeActionCompetingWork()) {
            return;
        }
        try {
            CanonicalHttpsUrl validated = CanonicalHttpsUrl.parse(
                    details.getWebsiteUrl().toString());
            startActivity(new Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(validated.toString())));
        } catch (NetworkPolicyException | RuntimeException exception) {
            Toast.makeText(
                    this,
                    R.string.episode_details_page_error,
                    Toast.LENGTH_LONG).show();
        }
    }

    private void loadEpisodeChapters() {
        if (!allowNetworkAction()) return;
        EpisodeDetails details = currentEpisodeDetails();
        if (details == null || episodeArchived(details) || !details.isChapterReferenceAvailable()
                || chapterLoadController == null || isEpisodeActionBusy() || hasEpisodeActionCompetingWork()) {
            return;
        }
        chapterLoadController.load(details.getMediaId());
    }

    private boolean isCurrentChapter(String mediaId, PodcastChapter chapter) {
        EpisodeDetails details = currentEpisodeDetails();
        if (details == null || !details.getMediaId().equals(mediaId)
                || chapterLoadController == null || isEpisodeActionBusy() || hasEpisodeActionCompetingWork()) {
            return false;
        }
        return chapterPanel != null && chapterPanel.contains(mediaId, chapter);
    }

    private void seekChapter(String mediaId, PodcastChapter chapter) {
        if (isCurrentChapter(mediaId, chapter)) {
            playbackPanel.seekChapterCurrent(currentEpisodeDetails(), chapter.getStartTimeMillis());
        }
    }

    private void playChapter(String mediaId, PodcastChapter chapter) {
        if (!isCurrentChapter(mediaId, chapter) || mediaId.equals(playbackPanel.currentMediaId())) return;
        playbackPanel.playChapter(currentEpisodeDetails(), chapter.getStartTimeMillis());
    }

    private void openChapterPage(String mediaId, PodcastChapter chapter) {
        if (!isCurrentChapter(mediaId, chapter) || chapter.getWebsiteUrl() == null) { return; }
        try {
            CanonicalHttpsUrl validated = CanonicalHttpsUrl.parse(chapter.getWebsiteUrl().toString());
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(validated.toString())));
        } catch (NetworkPolicyException | RuntimeException exception) {
            Toast.makeText(this, R.string.episode_details_page_error, Toast.LENGTH_LONG).show();
        }
    }

    private void onChapterLoadStateChanged(ChapterLoadController.State state) {
        renderedCatalogEpisodes = Collections.emptyList();
        renderEpisodeActionState(episodeActionController.getState());
        renderCatalogState(catalogBrowserController.getState());
        renderEpisodeQueueState(episodeQueueController.getState());
    }

    private void renderChapterLoadState() {
        if (chapterPanel == null || chapterLoadController == null || loadEpisodeChaptersButton == null) return;
        EpisodeDetails details = displayedEpisodeDetails();
        ChapterLoadController.State state = chapterLoadController.getState();
        boolean available = details != null && details.isChapterReferenceAvailable() && !episodeArchived(details);
        boolean enabled = !isEpisodeActionBusy() && !hasEpisodeActionCompetingWork();
        loadEpisodeChaptersButton.setVisibility(available ? View.VISIBLE : View.GONE);
        episodeChapterPrivacyNotice.setVisibility(available ? View.VISIBLE : View.GONE);
        loadEpisodeChaptersButton.setEnabled(available && enabled);
        episodeChapterProgress.setVisibility(available && state.isBusy() ? View.VISIBLE : View.GONE);
        boolean matching = details != null && details.getMediaId().equals(state.getMediaId());
        episodeChapterStatus.setVisibility(available && matching && state.getPhase() != ChapterLoadController.Phase.IDLE
                ? View.VISIBLE : View.GONE);
        PodcastChapterDocument document = matching && state.getPhase() == ChapterLoadController.Phase.READY
                ? state.getDocument() : null;
        switch (state.getPhase()) {
            case IDLE -> episodeChapterStatus.setText(null);
            case LOADING -> episodeChapterStatus.setText(R.string.chapter_status_loading);
            case ERROR -> episodeChapterStatus.setText(state.getFailure() == ChapterLoadController.Failure.NO_REFERENCE
                    ? R.string.chapter_error_missing
                    : state.getFailure() == ChapterLoadController.Failure.BACKGROUND_WORK_UNAVAILABLE
                    ? R.string.chapter_error_background : R.string.chapter_error_load);
            case READY -> episodeChapterStatus.setVisibility(View.GONE);
            default -> throw new AssertionError("Unknown chapter load phase.");
        }
        chapterPanel.bind(details, document, enabled, available && enabled, matching && state.isBusy());
        playbackPanel.refreshChapterPosition();
    }

    private void onEpisodeActionStateChanged(EpisodeActionController.State state) {
        renderedCatalogEpisodes = Collections.emptyList();
        renderEpisodeActionState(state);
        renderCatalogState(catalogBrowserController.getState());
        if (state.getPhase() == EpisodeActionController.Phase.DETAILS_READY
                && navigationShell.current().destination() == Destination.EPISODE
                && state.getDetails() != null
                && state.getDetails().getMediaId().equals(navigationShell.current().episodeId())) {
            episodeDetailsPanel.requestFocus();
        }
        if (state.getPhase() == EpisodeActionController.Phase.SUCCEEDED) {
            if (state.getOperation() == EpisodeActionController.Operation.MARK_PLAYED
                    || state.getOperation() == EpisodeActionController.Operation.MARK_UNPLAYED) {
                Toast.makeText(this, episodeActionStatus.getText(), Toast.LENGTH_SHORT).show();
            }
            reloadCatalogAfterLocalAction(); refreshLibraryLists();
        } else if (state.getPhase() == EpisodeActionController.Phase.ERROR && state.getDetails() != null) {
            Toast.makeText(this, episodeActionStatus.getText(), Toast.LENGTH_LONG).show();
        }
        loadPendingEpisode();
    }

    private void renderEpisodeActionState(EpisodeActionController.State state) {
        EpisodeDetails details = state.getDetails();
        if (details != null && navigationShell.current().destination() == Destination.EPISODE
                && !details.getMediaId().equals(navigationShell.current().episodeId())) details = null;
        playbackPanel.bindEpisode(details);
        episodeDetailsPanel.setVisibility(details == null ? View.GONE : View.VISIBLE);
        if (details != null) {
            String displayTitle = details.getTitle().isEmpty()
                    ? getString(R.string.catalog_episode_untitled)
                    : details.getTitle();
            episodeDetailsTitle.setText(displayTitle);
            episodeDetailsAuthor.setText(details.getPodcast() == null
                    ? valueOrNotSupplied(details.getAuthor()) : details.getPodcast().title());
            episodeDetailsMetadata.setText(buildExpandedEpisodeMetadata(details));
            episodeDetailsNotes.setText(details.getNotes().isEmpty()
                    ? getString(R.string.episode_details_notes_empty)
                    : details.getNotes());
            episodePlaybackButton.setContentDescription(getString(
                    R.string.episode_playback_description,
                    displayTitle));
            episodePlayedButton.setText(details.isPlayed()
                    ? R.string.episode_mark_unplayed_action
                    : R.string.episode_mark_played_action);
            episodePlayedButton.setContentDescription(getString(
                    details.isPlayed()
                            ? R.string.episode_mark_unplayed_description
                            : R.string.episode_mark_played_description,
                    displayTitle));
            UiWidgets.toggleIcon(episodeFavoriteButton, details.isFavorite() ? R.drawable.ic_heart_filled : R.drawable.ic_heart,
                    getString(details.isFavorite() ? R.string.episode_unfavorite_action : R.string.episode_favorite_action), details.isFavorite());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) episodeFavoriteButton.setStateDescription(
                    getString(details.isFavorite() ? R.string.ui_favorited : R.string.ui_not_favorited));
            episodeFavoriteButton.setContentDescription(getString(
                    details.isFavorite()
                            ? R.string.episode_unfavorite_description
                            : R.string.episode_favorite_description,
                    displayTitle));
            openEpisodePageButton.setContentDescription(getString(
                    R.string.episode_details_page_description,
                    displayTitle));
            openEpisodePageButton.setVisibility(
                    details.getWebsiteUrl() == null ? View.GONE : View.VISIBLE);
        }
        boolean enabled = details != null
                && !state.isBusy()
                && !hasEpisodeActionCompetingWork();
        episodePlaybackButton.setEnabled(enabled && playbackPanel.isConnected()
                && (!episodeArchived(details) || details.isDownloaded()));
        episodePlayedButton.setEnabled(enabled);
        episodeFavoriteButton.setEnabled(enabled);
        openEpisodePageButton.setEnabled(enabled && details.getWebsiteUrl() != null);
        closeEpisodeDetailsButton.setEnabled(details != null && !state.isBusy() && !isChapterLoadBusy());
        Button queueHeader = findViewById(R.id.episode_queue_header);
        boolean queued = details != null && findQueuePosition(details.getMediaId()) >= 0;
        queueHeader.setEnabled(details != null && episodeQueueController != null
                && episodeQueueController.getState().getPhase() == EpisodeQueueController.Phase.READY && !hasQueueCompetingWork());
        UiWidgets.toggleIcon(queueHeader, R.drawable.ic_queue, getString(queued ? R.string.episode_queue_remove_action
                : R.string.episode_queue_append_action), queued);
        renderEpisodeArtwork(); refreshEpisodePlaybackButton();
        renderChapterLoadState();

        switch (state.getPhase()) {
            case IDLE:
                episodeActionStatus.setText(R.string.episode_details_status_idle);
                break;
            case LOADING_DETAILS:
                episodeActionStatus.setText(R.string.episode_details_status_loading);
                break;
            case DETAILS_READY:
                episodeActionStatus.setText(R.string.episode_details_status_ready);
                break;
            case UPDATING:
                episodeActionStatus.setText(R.string.episode_details_status_updating);
                break;
            case SUCCEEDED:
                showEpisodeActionSuccess(state);
                break;
            case ERROR:
                showEpisodeActionFailure(state.getFailure());
                break;
            default:
                throw new AssertionError("Unknown episode action phase.");
        }
        UiWidgets.episodeFeedback(episodeActionProgress, episodeActionStatus, details != null,
                state.isBusy(), state.getPhase() == EpisodeActionController.Phase.ERROR);
    }

    private String buildExpandedEpisodeMetadata(EpisodeDetails episode) {
        String duration = episode.getDurationMillis() < 0L ? getString(R.string.catalog_episode_duration_unknown)
                : formatDuration(episode.getDurationMillis());
        return duration + " · " + getString(episode.isDownloaded() ? R.string.catalog_episode_downloaded
                : R.string.catalog_episode_not_downloaded);
    }

    private void showEpisodeActionSuccess(EpisodeActionController.State state) {
        if (state.getMutation() == EpisodeStateMutation.UNCHANGED) {
            episodeActionStatus.setText(R.string.episode_details_status_unchanged);
            return;
        }
        switch (state.getOperation()) {
            case MARK_PLAYED:
                episodeActionStatus.setText(R.string.episode_marked_played_status);
                break;
            case MARK_UNPLAYED:
                episodeActionStatus.setText(R.string.episode_marked_unplayed_status);
                break;
            case FAVORITE:
                episodeActionStatus.setText(R.string.episode_favorited_status);
                break;
            case UNFAVORITE:
                episodeActionStatus.setText(R.string.episode_unfavorited_status);
                break;
            case NONE:
            case LOAD_DETAILS:
            default:
                throw new AssertionError("Unknown episode action operation.");
        }
    }

    private void showEpisodeActionFailure(EpisodeActionController.Failure failure) {
        switch (failure) {
            case INVALID_MEDIA_ID:
                episodeActionStatus.setText(R.string.episode_details_error_selection);
                break;
            case EPISODE_NOT_FOUND:
                episodeActionStatus.setText(R.string.episode_details_error_missing);
                break;
            case STORAGE_FAILED:
                episodeActionStatus.setText(R.string.episode_details_error_storage);
                break;
            case BACKGROUND_WORK_UNAVAILABLE:
                episodeActionStatus.setText(R.string.episode_details_error_background);
                break;
            case NONE:
            default:
                episodeActionStatus.setText(R.string.episode_details_error_unknown);
                break;
        }
    }

    private void onEpisodeDownloadStateChanged(EpisodeDownloadController.State state) {
        renderedCatalogEpisodes = Collections.emptyList();
        renderEpisodeDownloadState();
        if (state.getPhase() == EpisodeDownloadController.Phase.ERROR) {
            Toast.makeText(this, episodeDownloadStatus.getText(), Toast.LENGTH_SHORT).show();
        }
        renderCatalogState(catalogBrowserController.getState());
        if (settingsPanel != null) {
            if (!state.isBusy()) settingsPanel.refreshStorage();
            settingsPanel.renderStorage();
        }
        if (state.getPhase() == EpisodeDownloadController.Phase.SUCCEEDED) {
            Toast.makeText(this, episodeDownloadStatus.getText(), Toast.LENGTH_SHORT).show();
            reloadCatalogAfterLocalAction(); refreshLibraryLists(); refreshSelectedEpisode();
        }
    }

    private void renderEpisodeDownloadState() {
        if (episodeDownloadController == null) {
            episodeDownloadProgress.setVisibility(View.GONE);
            episodeDownloadStatus.setText(R.string.episode_download_error_storage);
            return;
        }
        EpisodeDownloadController.State state = episodeDownloadController.getState();
        episodeDownloadProgress.setVisibility(state.isBusy() ? View.VISIBLE : View.GONE);
        episodeDownloadStatus.setVisibility(state.isBusy()
                || state.getPhase() == EpisodeDownloadController.Phase.ERROR ? View.VISIBLE : View.GONE);
        switch(state.getPhase()){
            case IDLE->episodeDownloadStatus.setText(null);
            case DELETING->episodeDownloadStatus.setText(R.string.episode_download_status_deleting);
            case SUCCEEDED->episodeDownloadStatus.setText(R.string.episode_delete_status_complete);
            case ERROR->showEpisodeDownloadFailure(state.getFailure());
        }
    }

    private void showEpisodeDownloadFailure(EpisodeDownloadController.Failure failure){
        episodeDownloadStatus.setText(switch(failure){
            case INVALID_SELECTION->R.string.episode_download_error_selection;
            case EPISODE_NOT_FOUND->R.string.episode_download_error_missing;
            case ALREADY_ACTIVE->R.string.episode_download_error_active;
            case PLAYBACK_IN_USE->R.string.episode_download_error_playback_in_use;
            case STORAGE_FAILED->R.string.episode_download_error_storage;
            case BACKGROUND_WORK_UNAVAILABLE->R.string.episode_download_error_background;
            case NONE->R.string.episode_download_error_unknown;
        });
    }

    private void reloadCatalogAfterLocalAction() {
        CatalogBrowserController.State catalogState = catalogBrowserController.getState();
        pendingCatalogReloadSubscriptionId = catalogState.getSelectedSubscriptionId();
        catalogBrowserController.loadSubscriptions();
    }

    private void continuePendingCatalogReload(CatalogBrowserController.State state) {
        if (pendingCatalogReloadSubscriptionId < 1L) {
            return;
        }
        if (state.getPhase() == CatalogBrowserController.Phase.EMPTY
                || state.getPhase() == CatalogBrowserController.Phase.ERROR) {
            pendingCatalogReloadSubscriptionId = -1L;
            return;
        }
        if (state.getPhase() != CatalogBrowserController.Phase.SUBSCRIPTIONS_READY) {
            return;
        }
        long subscriptionId = pendingCatalogReloadSubscriptionId;
        pendingCatalogReloadSubscriptionId = -1L;
        catalogBrowserController.selectSubscription(subscriptionId);
    }

    private String formatDuration(long durationMillis) {
        return episodeMetadata.duration(durationMillis);
    }

    private void showCatalogStatus(CatalogBrowserController.State state) {
        switch (state.getPhase()) {
            case IDLE:
                catalogStatus.setText(R.string.catalog_status_idle);
                break;
            case LOADING_SUBSCRIPTIONS:
                catalogStatus.setText(R.string.catalog_status_loading_subscriptions);
                break;
            case EMPTY:
                catalogStatus.setText(R.string.catalog_status_empty);
                break;
            case SUBSCRIPTIONS_READY:
                catalogStatus.setText(R.string.catalog_status_choose_subscription);
                break;
            case LOADING_EPISODES:
                catalogStatus.setText(R.string.catalog_status_loading_episodes);
                break;
            case LOADING_MORE:
                catalogStatus.setText(R.string.catalog_status_loading_more);
                break;
            case EPISODES_READY:
                showReadyCatalogStatus(state);
                break;
            case ERROR:
                showCatalogFailure(state.getFailure());
                break;
            default:
                throw new AssertionError("Unknown catalog browser phase.");
        }
    }

    private void showReadyCatalogStatus(CatalogBrowserController.State state) {
        int episodeCount = state.getEpisodes().size();
        if (episodeCount == 0) {
            catalogStatus.setText(catalogBrowserController.getQuery()
                    .equals(CatalogBrowseQuery.DEFAULT)
                    ? R.string.catalog_status_no_episodes
                    : R.string.catalog_status_no_matches);
        } else if (state.hasMore()
                && episodeCount >= SecurityLimits.CATALOG_BROWSE_VISIBLE_EPISODE_LIMIT) {
            catalogStatus.setText(getString(
                    R.string.catalog_status_visible_limit,
                    SecurityLimits.CATALOG_BROWSE_VISIBLE_EPISODE_LIMIT));
        } else {
            catalogStatus.setText(getResources().getQuantityString(
                    R.plurals.catalog_status_episode_count,
                    episodeCount,
                    episodeCount));
        }
    }

    private void showCatalogFailure(CatalogBrowserController.Failure failure) {
        switch (failure) {
            case LOAD_FAILED:
                catalogStatus.setText(R.string.catalog_error_load);
                break;
            case INVALID_SELECTION:
                catalogStatus.setText(R.string.catalog_error_selection);
                break;
            case BACKGROUND_WORK_UNAVAILABLE:
                catalogStatus.setText(R.string.catalog_error_background);
                break;
            case NONE:
            default:
                catalogStatus.setText(R.string.catalog_error_unknown);
                break;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void startSubscriptionPreview() {
        if (!allowNetworkAction()) return;
        if (isAnyFeedRefreshBusy() || isAnyMediaWorkBusy()) {
            return;
        }
        try {
            String input = subscriptionUrlInput.getText().toString().trim();
            var owner = CanonicalHttpsUrl.parse(input);
            subscriptionController.startPreview(input, subscriptionLoginEditor.read(owner));
        } catch (io.github.jnesew.plainpoddroid.core.network.NetworkPolicyException e) {
            subscriptionController.startPreview(subscriptionUrlInput.getText().toString());
        } catch (IllegalArgumentException e) { showSubscriptionStatus(R.string.login_invalid); }

    }

    private void invalidateSubscriptionPreview() {
        if (subscriptionController == null) return;
        var phase = subscriptionController.getState().getPhase();
        if (phase == SubscriptionUiController.Phase.READY_TO_CONFIRM || phase == SubscriptionUiController.Phase.PREVIEWING)
            subscriptionController.cancel();
    }

    private boolean allowNetworkAction() {
        if (!playbackPrivacySettings.isOfflineModeEnabled()) return true;
        Toast.makeText(this, R.string.offline_network_unavailable, Toast.LENGTH_SHORT).show();
        return false;
    }

    private void applySharedText(Intent intent) {
        if (intent == null
                || !Intent.ACTION_SEND.equals(intent.getAction())
                || intent.getType() == null
                || !"text/plain".equalsIgnoreCase(intent.getType())) {
            return;
        }
        final String sharedText;
        try {
            sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
        } catch (RuntimeException exception) {
            showSubscriptionStatus(R.string.subscription_shared_text_invalid);
            return;
        }
        if (sharedText == null || sharedText.trim().isEmpty()) {
            showSubscriptionStatus(R.string.subscription_shared_text_invalid);
            return;
        }
        if (sharedText.length() > SecurityLimits.MAX_URL_CHARS) {
            showSubscriptionStatus(R.string.subscription_shared_text_too_long);
            return;
        }
        navigationShell.addPodcast();
        String normalized = sharedText.trim();
        subscriptionUrlInput.setText(normalized);
        subscriptionUrlInput.setSelection(normalized.length());
        showSubscriptionStatus(R.string.subscription_shared_text_ready);
    }

    private void renderSubscriptionState(SubscriptionUiController.State state) {
        SubscriptionUiController.Phase phase = state.getPhase();
        boolean busy = phase == SubscriptionUiController.Phase.PREVIEWING
                || phase == SubscriptionUiController.Phase.CONFIRMING;
        subscriptionUrlInput.setEnabled(
                !busy && !isAnyFeedRefreshBusy() && !isAnyMediaWorkBusy());
        previewFeedButton.setEnabled(
                !busy && !isAnyFeedRefreshBusy() && !isAnyMediaWorkBusy());
        subscriptionLoginEditor.setBusy(!subscriptionUrlInput.isEnabled());
        subscriptionProgress.setVisibility(busy ? View.VISIBLE : View.GONE);
        cancelPreviewButton.setVisibility(
                phase == SubscriptionUiController.Phase.PREVIEWING
                        || phase == SubscriptionUiController.Phase.READY_TO_CONFIRM
                        || phase == SubscriptionUiController.Phase.ERROR
                        ? View.VISIBLE
                        : View.GONE);
        cancelPreviewButton.setEnabled(phase != SubscriptionUiController.Phase.CONFIRMING);

        SubscriptionPreview preview = state.getPreview();
        subscriptionPreview.setVisibility(preview == null ? View.GONE : View.VISIBLE);
        confirmSubscriptionButton.setVisibility(preview != null &&
                (phase == SubscriptionUiController.Phase.READY_TO_CONFIRM || phase == SubscriptionUiController.Phase.CONFIRMING)
                ? View.VISIBLE : View.GONE);
        confirmSubscriptionButton.setEnabled(
                phase == SubscriptionUiController.Phase.READY_TO_CONFIRM
                        && !isAnyFeedRefreshBusy()
                        && !isAnyMediaWorkBusy());
        if (preview != null) {
            renderSubscriptionPreview(preview);
        } else {
            renderSubscriptionArtwork(null);
        }

        switch (phase) {
            case IDLE:
                subscriptionStatus.setVisibility(View.GONE);
                break;
            case PREVIEWING:
                subscriptionStatus.setVisibility(View.GONE);
                subscriptionProgress.setContentDescription(getString(R.string.subscription_status_previewing));
                break;
            case READY_TO_CONFIRM:
                subscriptionStatus.setVisibility(View.GONE);
                break;
            case CONFIRMING:
                showSubscriptionStatus(R.string.subscription_status_confirming);
                break;
            case SUBSCRIBED:
                subscriptionLoginEditor.clear();
                subscriptionUrlInput.setText("");
                subscriptionStatus.setVisibility(View.GONE);
                Toast.makeText(this,R.string.subscription_status_subscribed,Toast.LENGTH_SHORT).show();
                if (catalogBrowserController != null) {
                    catalogBrowserController.loadSubscriptions();
                }
                break;
            case ERROR:
                showSubscriptionFailure(state.getFailure());
                break;
            default:
                throw new AssertionError("Unknown subscription UI phase.");
        }
        if (catalogBrowserController != null) {
            renderCatalogState(catalogBrowserController.getState());
        }
    }

    private void updateSubscriptionInputEnabled() {
        if (subscriptionController == null || subscriptionUrlInput == null) {
            return;
        }
        SubscriptionUiController.Phase phase = subscriptionController.getState().getPhase();
        boolean workflowBusy = phase == SubscriptionUiController.Phase.PREVIEWING
                || phase == SubscriptionUiController.Phase.CONFIRMING;
        boolean competingWork = isAnyFeedRefreshBusy() || isAnyMediaWorkBusy();
        subscriptionUrlInput.setEnabled(!workflowBusy && !competingWork);
        previewFeedButton.setEnabled(!workflowBusy && !competingWork);
        if (confirmSubscriptionButton.getVisibility() == View.VISIBLE) {
            confirmSubscriptionButton.setEnabled(
                    phase == SubscriptionUiController.Phase.READY_TO_CONFIRM
                            && !competingWork);
        }
    }

    private void renderSubscriptionPreview(SubscriptionPreview preview) {
        renderSubscriptionArtwork(preview);
        previewTitle.setText(valueOrNotSupplied(preview.getTitle()));
        previewAuthor.setText(preview.getAuthor());
        previewAuthor.setVisibility(preview.getAuthor().isEmpty()?View.GONE:View.VISIBLE);
        previewAppleSource.setVisibility(preview.isFromAppleLink() ? View.VISIBLE : View.GONE);
        if (preview.isFromAppleLink()) previewAppleSource.setText(preview.isFromAppleEpisodeLink()
                ? R.string.subscription_preview_apple_episode : R.string.subscription_preview_apple_show);
        previewOriginalOrigin.setText(getString(
                R.string.subscription_preview_original_format,
                preview.getOriginalOriginForDisplay()));
        previewFinalOrigin.setText(getString(
                R.string.subscription_preview_final_format,
                preview.getFinalOriginForDisplay()));
        List<String> redirects = preview.getRedirectOriginsForDisplay();
        previewRedirects.setVisibility(redirects.size() > 1 ? View.VISIBLE : View.GONE);
        if (redirects.size() > 1) {
            previewRedirects.setText(getString(
                    R.string.subscription_preview_redirects_format,
                    TextUtils.join(" → ", redirects)));
        }
        List<String> counts=new ArrayList<>();
        counts.add(getResources().getQuantityString(R.plurals.ui_preview_episodes,
                preview.getAcceptedEpisodeCount(),preview.getAcceptedEpisodeCount()));
        if(preview.getRejectedEpisodeCount()>0)counts.add(getResources().getQuantityString(
                R.plurals.ui_preview_unavailable,preview.getRejectedEpisodeCount(),preview.getRejectedEpisodeCount()));
        if(preview.getDuplicateEpisodeCount()>0)counts.add(getResources().getQuantityString(
                R.plurals.ui_import_duplicates,preview.getDuplicateEpisodeCount(),preview.getDuplicateEpisodeCount()));
        previewCounts.setText(String.join(" · ",counts));
    }

    private void renderSubscriptionArtwork(SubscriptionPreview preview) {
        if (previewArtwork == null || !subscriptionPreview.isShown()) return;
        // Completion callbacks reread the current preview, never a stale captured one.
        Bitmap image = preview == null || detailArtworkLoader == null ? null
                : detailArtworkLoader.getOrRequest(
                        preview.getArtworkUrl(), this::notifyCatalogArtworkReady);
        UiWidgets.setArtwork(previewArtwork,image);
        int visibility=image==null?View.GONE:View.VISIBLE;
        if(previewArtwork.getVisibility()!=visibility)previewArtwork.setVisibility(visibility);
    }

    private String valueOrNotSupplied(String value) {
        return value.isEmpty() ? getString(R.string.subscription_not_supplied) : value;
    }

    private void showSubscriptionFailure(SubscriptionUiController.Failure failure) {
        switch (failure) {
            case INPUT_REQUIRED:
                showSubscriptionStatus(R.string.subscription_error_required);
                break;
            case INPUT_TOO_LONG:
                showSubscriptionStatus(R.string.subscription_error_too_long);
                break;
            case INVALID_URL:
                showSubscriptionStatus(R.string.subscription_error_invalid_url);
                break;
            case LOGIN_REQUIRED:
                showSubscriptionStatus(R.string.login_needed); break;
            case ACCESS_DENIED:
                showSubscriptionStatus(R.string.login_denied); break;
            case FETCH_FAILED:
                showSubscriptionStatus(R.string.subscription_error_fetch);
                break;
            case INVALID_FEED:
                showSubscriptionStatus(R.string.subscription_error_feed);
                break;
            case FEED_LIMIT:
                showSubscriptionStatus(R.string.subscription_error_limit); break;
            case APPLE_LOOKUP_FAILED:
                showSubscriptionStatus(R.string.subscription_error_apple_lookup); break;
            case APPLE_NO_PUBLIC_FEED:
                showSubscriptionStatus(R.string.subscription_error_apple_no_feed); break;
            case APPLE_LOGIN_UNSUPPORTED:
                showSubscriptionStatus(R.string.subscription_error_apple_login); break;
            case TOO_MANY_PENDING_PREVIEWS:
                showSubscriptionStatus(R.string.subscription_error_pending);
                break;
            case CONFIRMATION_EXPIRED:
                showSubscriptionStatus(R.string.subscription_error_expired);
                break;
            case SAVE_FAILED:
                showSubscriptionStatus(R.string.subscription_error_save);
                break;
            case BACKGROUND_WORK_UNAVAILABLE:
                showSubscriptionStatus(R.string.subscription_error_background);
                break;
            case NONE:
            default:
                showSubscriptionStatus(R.string.subscription_error_unknown);
                break;
        }
    }

    private void showSubscriptionStatus(int stringResource) {
        subscriptionStatus.setText(stringResource);
        subscriptionStatus.setVisibility(View.VISIBLE);
    }

    private EpisodeListView.Actions rowActions() {
        return new EpisodeListView.Actions() {
            public void open(String id) { loadEpisodeDetails(id); }
            public void play(String id) { playEpisodeRow(id); }
            public void menu(EpisodeListView.Row row, View anchor) { showEpisodeRowMenu(row, anchor); }
            public EpisodeListView.DownloadState downloadState(EpisodeListView.Row row) {
                var request=manualDownloads==null?null:manualDownloads.find(row.id());
                if(request!=null&&request.state()!=io.github.jnesew.plainpoddroid.core.download.ManualDownloadQueue.State.FAILED)
                    return request.state()==io.github.jnesew.plainpoddroid.core.download.ManualDownloadQueue.State.ACTIVE
                            ?EpisodeListView.DownloadState.ACTIVE:EpisodeListView.DownloadState.WAITING;
                if(row.downloaded())return EpisodeListView.DownloadState.COMPLETE;
                if(!canRequestDownload(row.id(),false)||playbackPrivacySettings.isOfflineModeEnabled()
                        ||(row.podcast()!=null&&!row.podcast().subscribed()))return EpisodeListView.DownloadState.UNAVAILABLE;
                return request==null?EpisodeListView.DownloadState.AVAILABLE:EpisodeListView.DownloadState.FAILED;
            }
            public void download(EpisodeListView.Row row,View anchor){
                var request=manualDownloads==null?null:manualDownloads.find(row.id());
                if(request!=null&&request.state()!=io.github.jnesew.plainpoddroid.core.download.ManualDownloadQueue.State.FAILED){manualDownloads.cancel(row.id());return;}
                if(row.downloaded()){
                    PopupMenu popup=new PopupMenu(MainActivity.this,anchor);
                    addDownloadMenu(popup,row.id(),row.title(),true,row.podcast()==null||row.podcast().subscribed());popup.show();
                }else downloadEpisode(row.id(),row.title());
            }
            public void move(String id, int position) { moveQueuedEpisode(id, position); }
            public void bulk(List<EpisodeListView.Row> rows,io.github.jnesew.plainpoddroid.core.episode.EpisodeBatchRequest.Action action){
                if(batchController!=null)batchController.perform(rows,action);
            }
            public boolean canMove() {
                return episodeQueueController != null && episodeQueueController.getState().getPhase()
                        == EpisodeQueueController.Phase.READY && !hasQueueCompetingWork();
            }
        };
    }

    private EpisodeListView newEpisodeList(int rowsId) {
        return new EpisodeListView(this, findViewById(rowsId), artworkThumbnailLoader,
                this::notifyCatalogArtworkReady, rowActions(), playbackPanel::isPlaying);
    }

    private LibraryListPanel newLibraryPanel(Destination destination) {
        return new LibraryListPanel(this, destination, screenWorker, mainHandler,
                artworkThumbnailLoader, this::notifyCatalogArtworkReady, rowActions(),
                playbackPanel::isPlaying, initialSavedState);
    }

    private void configureDestinationActions() {
        io.github.jnesew.plainpoddroid.ui.PullRefreshScrollView podcasts = findViewById(R.id.screen_podcasts);
        podcasts.setRefreshAction(refreshAllSubscriptionsButton::isEnabled, () -> refreshAllSubscriptionsButton.performClick());
        UiWidgets.icon(findViewById(R.id.search_action), R.drawable.ic_search, getString(R.string.ui_search), false);
        UiWidgets.icon(findViewById(R.id.destination_overflow), R.drawable.ic_more, getString(R.string.ui_more), false);
        findViewById(R.id.podcast_search_controls).setVisibility(appliedPodcastSearch.isEmpty()?View.GONE:View.VISIBLE);
        updatePodcastSearchIcon();
        findViewById(R.id.search_action).setOnClickListener(v -> {
            View search = findViewById(R.id.podcast_search_controls);
            boolean open=search.getVisibility()!=View.VISIBLE;
            search.setVisibility(open?View.VISIBLE:View.GONE);
            var input=(android.view.inputmethod.InputMethodManager)getSystemService(INPUT_METHOD_SERVICE);
            if(open) { catalogPodcastSearch.requestFocus();input.showSoftInput(catalogPodcastSearch,android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT); }
            else { catalogPodcastSearch.setText("");catalogPodcastSearch.clearFocus();input.hideSoftInputFromWindow(catalogPodcastSearch.getWindowToken(),0); }
            updatePodcastSearchIcon();
        });
        findViewById(R.id.destination_overflow).setOnClickListener(this::showDestinationMenu);
        findViewById(R.id.add_podcast_action).setOnClickListener(v -> navigationShell.addPodcast());
        findViewById(R.id.mini_player_body).setOnClickListener(v -> {
            String id = playbackPanel.currentMediaId();
            if (!id.isEmpty()) loadEpisodeDetails(id);
        });
        int[] chipIds = {R.id.filter_all, R.id.filter_unplayed, R.id.filter_downloaded};
        for (int index = 0; index < chipIds.length; index++) {
            int filter = index;
            findViewById(chipIds[index]).setOnClickListener(v -> {
                episodeFilter=CatalogBrowseQuery.Filter.values()[filter]; applyCatalogQuery();
            });
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerModernBack();
    }

    private void updatePodcastSearchIcon() {
        boolean open=findViewById(R.id.podcast_search_controls).getVisibility()==View.VISIBLE;
        UiWidgets.icon(findViewById(R.id.search_action),open?R.drawable.ic_close:R.drawable.ic_search,
                getString(open?R.string.ui_close_search:R.string.ui_search),false);
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private void registerModernBack() {
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                () -> { if (!navigationShell.back()) finish(); });
    }

    @Override
    @android.annotation.SuppressLint("GestureBackNavigation") // API 29–32 fallback; API 33+ registers OnBackInvokedCallback above.
    @SuppressWarnings("deprecation") // Android 10–12 fallback; API 33+ uses the dispatcher.
    public void onBackPressed() {
        if (!navigationShell.back()) super.onBackPressed();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        navigationShell.save(out);
        if (downloadsPanel != null) downloadsPanel.save(out);
        if (favoritesPanel != null) favoritesPanel.save(out);
        if (historyPanel != null) historyPanel.save(out);
        if (newEpisodesPanel != null) newEpisodesPanel.save(out);
        if (inProgressPanel != null) inProgressPanel.save(out);
        out.putInt("catalog.filter", episodeFilter.ordinal());
        out.putString("catalog.search", catalogEpisodeSearch.getText().toString());
        out.putString("catalog.podcastSearch", catalogPodcastSearch.getText().toString());
        out.putInt("queue.offset", queuePageOffset);
        super.onSaveInstanceState(out);
    }

    private void restoreCatalogQuery(Bundle saved) {
        if (saved == null) return;
        try {
            int filter = saved.getInt("catalog.filter", 0);
            if (filter < 0 || filter >= CatalogBrowseQuery.Filter.values().length) return;
            CatalogBrowseQuery.Filter selected = CatalogBrowseQuery.Filter.values()[filter];
            if (selected == CatalogBrowseQuery.Filter.QUEUED || selected == CatalogBrowseQuery.Filter.COMPLETED)
                selected = CatalogBrowseQuery.Filter.ALL;
            restoredQuery = new CatalogBrowseQuery(selected, saved.getString("catalog.search", ""));
            appliedPodcastSearch = CatalogSubscriptionSearch.validate(saved.getString("catalog.podcastSearch", ""));
            queuePageOffset = Math.max(0, Math.min(SecurityLimits.MAX_QUEUE_ENTRIES - 1, saved.getInt("queue.offset", 0)));
        } catch (IllegalArgumentException | NullPointerException ignored) { restoredQuery = null; appliedPodcastSearch = ""; }
    }

    private void onDestinationChanged() {
        cancelEpisodeSelection();
        if(batchController!=null)batchController.navigationChanged();
        if(podcastSettings!=null)podcastSettings.navigationChanged();
        Destination destination = navigationShell.current().destination();
        if (chapterPanel != null) chapterPanel.dismiss();
        if (destination != Destination.QUEUE && queueList != null) queueList.cancelDrag();
        if (playbackPanel != null) playbackPanel.setEpisodeVisible(destination == Destination.EPISODE);
        if (downloadsPanel != null) downloadsPanel.setVisible(destination == Destination.DOWNLOADS);
        if (favoritesPanel != null) favoritesPanel.setVisible(destination == Destination.FAVORITES);
        if (historyPanel != null) historyPanel.setVisible(destination == Destination.HISTORY);
        if (newEpisodesPanel != null) newEpisodesPanel.setVisible(destination == Destination.NEW_EPISODES);
        if (inProgressPanel != null) inProgressPanel.setVisible(destination == Destination.IN_PROGRESS);
        if (destination == Destination.EPISODE) {
            pendingEpisodeId = navigationShell.current().episodeId(); loadPendingEpisode();
        } else pendingEpisodeId = "";
        if (destination == Destination.PODCAST_EPISODES) ensurePodcastSelection();
        if (destination == Destination.PODCAST_EPISODES && catalogBrowserController != null)
            catalogBrowserController.acknowledgeViewed();
        if (destination == Destination.PODCASTS && catalogBrowserController != null)
            catalogBrowserController.loadSubscriptions();
        if (destination == Destination.QUEUE && episodeQueueController != null && !episodeQueueController.getState().isBusy()) {
            episodeQueueController.refresh();
        }
        refreshRowPlayback();
        notifyCatalogArtworkReady();
    }

    private void openPodcast(CatalogSubscriptionSummary podcast) {
        navigationShell.openPodcast(podcast.getSubscriptionId());
    }

    private void ensurePodcastSelection() {
        if (catalogBrowserController == null || catalogBrowserController.getState().getPhase()
                == CatalogBrowserController.Phase.LOADING_SUBSCRIPTIONS) return;
        long id = navigationShell.current().podcastId();
        if (catalogBrowserController.getState().getSelectedSubscriptionId() == id) return;
        for (CatalogSubscriptionSummary podcast : catalogBrowserController.getState().getSubscriptions()) {
            if (podcast.getSubscriptionId() == id) {
                subscriptionRefreshController.reset();
                catalogBrowserController.selectSubscription(id); return;
            }
        }
        CatalogBrowserController.Phase phase = catalogBrowserController.getState().getPhase();
        if (phase != CatalogBrowserController.Phase.IDLE && phase != CatalogBrowserController.Phase.ERROR) navigationShell.select(Destination.PODCASTS);
    }

    private void loadPendingEpisode() {
        if (pendingEpisodeId.isEmpty() || episodeActionController == null || episodeActionController.getState().isBusy()) return;
        String id = pendingEpisodeId; pendingEpisodeId = "";
        chapterLoadController.reset(); episodeActionController.loadDetails(id);
    }

    private void refreshSelectedEpisode() {
        if (navigationShell.current().destination() != Destination.EPISODE || episodeActionController.getState().isBusy()) return;
        episodeActionController.loadDetails(navigationShell.current().episodeId());
    }

    private void refreshLibraryLists() {
        if (downloadsPanel != null) downloadsPanel.refresh();
        if (favoritesPanel != null) favoritesPanel.refresh();
        if (historyPanel != null) historyPanel.refresh();
        if (newEpisodesPanel != null) newEpisodesPanel.refresh();
        if (inProgressPanel != null) inProgressPanel.refresh();
    }

    private void refreshRowPlayback() {
        if (podcastEpisodeList != null && navigationShell.current().destination()==Destination.PODCAST_EPISODES) podcastEpisodeList.refreshPlayback();
        if (queueList != null && navigationShell.current().destination()==Destination.QUEUE) queueList.refreshPlayback();
        if (downloadsPanel != null) downloadsPanel.refreshPlayback();
        if (favoritesPanel != null) favoritesPanel.refreshPlayback();
        if (historyPanel != null) historyPanel.refreshPlayback();
        if (newEpisodesPanel != null) newEpisodesPanel.refreshPlayback();
        if (inProgressPanel != null) inProgressPanel.refreshPlayback();
    }

    private String displayTitle(String title) { return title.isEmpty() ? getString(R.string.catalog_episode_untitled) : title; }

    private void playEpisodeRow(String id) {
        if (id.equals(playbackPanel.currentMediaId())) {
            if(!playbackPanel.isPlaying(id)&&workState().hasPlaybackCompetingWork())
                Toast.makeText(this,R.string.ui_work_busy,Toast.LENGTH_SHORT).show();
            else playbackPanel.toggleCurrent();
            return;
        }
        if (workState().hasPlaybackCompetingWork()) {
            Toast.makeText(this, R.string.episode_download_error_active, Toast.LENGTH_SHORT).show(); return;
        }
        int ticket = ++rowPlayGeneration;
        try { screenWorker.execute(() -> {
            EpisodeDetails details = null;
            try { details = ((PlainPodDroidApplication) getApplication()).getEpisodeActionStore().findDetails(id); }
            catch (IOException | RuntimeException ignored) { }
            EpisodeDetails result = details;
            mainHandler.post(() -> {
                if (isDestroyed() || ticket != rowPlayGeneration) return;
                if (result == null || (episodeArchived(result) && !result.isDownloaded()) || !playbackPanel.isConnected()) {
                    Toast.makeText(this, R.string.ui_play_failed, Toast.LENGTH_LONG).show(); return;
                }
                if(removalBusy&&result.getPodcast()!=null&&result.getPodcast().subscriptionId()==removingPodcastId)return;
                playbackPanel.playEpisode(result, workState().hasPlaybackCompetingWork());
            });
        }); } catch (RejectedExecutionException ignored) { Toast.makeText(this, R.string.ui_play_failed, Toast.LENGTH_LONG).show(); }
    }

    private void refreshMiniDetails() {
        if (playbackPanel == null) return;
        String id = playbackPanel.currentMediaId();
        if (id.equals(miniDetailsId)) return;
        miniDetailsId = id; miniDetails = null; renderMiniArtwork();
        int ticket = ++miniDetailsGeneration;
        if (id.isEmpty()) return;
        if (episodeQueueController != null && !episodeQueueController.getState().isBusy()) episodeQueueController.refresh();
        if (historyPanel != null) historyPanel.refresh();
        if (newEpisodesPanel != null) newEpisodesPanel.refresh();
        if (inProgressPanel != null) inProgressPanel.refresh();
        try { screenWorker.execute(() -> {
            EpisodeDetails details = null;
            try { details = ((PlainPodDroidApplication) getApplication()).getEpisodeActionStore().findDetails(id); }
            catch (IOException | RuntimeException ignored) { }
            EpisodeDetails result = details;
            mainHandler.post(() -> {
                if (isDestroyed() || ticket != miniDetailsGeneration || !id.equals(playbackPanel.currentMediaId())) return;
                miniDetails = result; renderMiniArtwork();
            });
        }); } catch (RejectedExecutionException ignored) { }
    }

    private void renderMiniArtwork() {
        TextView label=findViewById(R.id.mini_podcast);
        String title=miniDetails==null||miniDetails.getPodcast()==null?"":miniDetails.getPodcast().title();
        if(!TextUtils.equals(label.getText(),title))label.setText(title);
        if(findViewById(R.id.mini_art).isShown())UiWidgets.setArtwork(findViewById(R.id.mini_art), artworkThumbnailLoader == null ? null : artworkThumbnailLoader.getOrRequest(
                miniDetails == null || miniDetails.getPodcast() == null ? null : miniDetails.getPodcast().artworkUrl(), this::notifyCatalogArtworkReady));
    }

    private void renderEpisodeArtwork() {
        if(!findViewById(R.id.episode_cover).isShown())return;
        EpisodeDetails details = displayedEpisodeDetails();
        UiWidgets.setArtwork(findViewById(R.id.episode_cover), detailArtworkLoader.getOrRequest(
                details == null || details.getPodcast() == null ? null : details.getPodcast().artworkUrl(), this::notifyCatalogArtworkReady));
    }

    private void menuAction(PopupMenu popup, int label, boolean enabled, Runnable action) {
        popup.getMenu().add(label).setEnabled(enabled).setOnMenuItemClickListener(item -> { action.run(); return true; });
    }

    private void cancelEpisodeSelection(){
        if(podcastEpisodeList!=null)podcastEpisodeList.cancelSelection();
        if(queueList!=null)queueList.cancelSelection();
        for(LibraryListPanel panel:new LibraryListPanel[]{downloadsPanel,favoritesPanel,historyPanel,newEpisodesPanel,inProgressPanel})
            if(panel!=null)panel.cancelSelection();
    }
    private void selectEpisodeRow(String id){
        switch(navigationShell.current().destination()){
            case PODCAST_EPISODES->podcastEpisodeList.select(id);case QUEUE->queueList.select(id);
            case DOWNLOADS->downloadsPanel.select(id);case FAVORITES->favoritesPanel.select(id);case HISTORY->historyPanel.select(id);
            case NEW_EPISODES->newEpisodesPanel.select(id);case IN_PROGRESS->inProgressPanel.select(id);
            default->{ }
        }
    }
    private boolean queueSelectedDownloads(List<EpisodeListView.Row> rows){
        if(playbackPrivacySettings.isNeverStreamAudioEnabled()){
            Toast.makeText(this,R.string.episode_download_error_offline,Toast.LENGTH_SHORT).show();return false;}
        if(manualDownloads==null||workState().hasDownloadCompetingWork(false))return false;
        var ids=new ArrayList<String>();for(var row:rows)ids.add(row.id());
        enqueueManualDownloads(ids,null);return true;
    }

    private void showPodcastMenu(CatalogSubscriptionSummary podcast, View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        addPodcastActions(popup,podcast);
        popup.show();
    }

    private void addPodcastActions(PopupMenu popup,CatalogSubscriptionSummary podcast){
        var menu=new io.github.jnesew.plainpoddroid.ui.PodcastActionsMenu(popup.getMenu(),podcast,
                canChangePodcast(),canChangePodcast()&&!playbackPrivacySettings.isOfflineModeEnabled(),
                new io.github.jnesew.plainpoddroid.ui.PodcastActionsMenu.Actions(){
                    public void information(){showPodcastInformation(podcast);}
                    public void settings(){showPodcastSettings(podcast);}
                    public void refresh(){refreshPodcast(podcast);}
                    public void archive(){confirmPodcastRemoval(podcast,true);}
                    public void delete(){confirmPodcastRemoval(podcast,false);}
                    public void subscribeAgain(){MainActivity.this.subscribeAgain(podcast);}
                    public void login(){showPodcastLogin(podcast);}
                });
        podcastSettings.bindLoginAction(menu,podcast);
    }

    private void refreshPodcast(CatalogSubscriptionSummary podcast){
        if(!allowNetworkAction())return;
        if(!canChangePodcast()){Toast.makeText(this,R.string.ui_work_busy,Toast.LENGTH_SHORT).show();return;}
        manualRefreshAllController.reset();subscriptionRefreshController.refresh(podcast.getSubscriptionId());
    }

    private void subscribeAgain(CatalogSubscriptionSummary podcast){
        if(!allowNetworkAction())return;
        if(!canChangePodcast()){Toast.makeText(this,R.string.ui_work_busy,Toast.LENGTH_SHORT).show();return;}
        navigationShell.addPodcast();subscriptionLoginEditor.clear();
        subscriptionUrlInput.setText(podcast.getSourceUrl().toString());
        startSubscriptionPreview(); // Explicit action; existing preview and Subscribe confirmation remain authoritative.
    }

    private boolean canEditFeedLogin(){return !isAnyFeedRefreshBusy()&&!isSubscriptionWorkflowBusy()&&!isAnyMediaWorkBusy()&&!removalBusy&&!isOpmlBusy();}

    private void showPodcastInformation(CatalogSubscriptionSummary podcast) {
        String updated = podcast.getUpdatedAtMillis() == 0L ? getString(R.string.catalog_update_unknown)
                : getString(R.string.catalog_updated_at_format, DateUtils.formatDateTime(this,
                        podcast.getUpdatedAtMillis(), DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME));
        if (podcast.getRefreshFailureCount() > 0) updated += getString(R.string.catalog_refresh_failed_at_format,
                DateUtils.formatDateTime(this, podcast.getLastRefreshAttemptAtMillis(), DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME));
        String counts = getResources().getQuantityString(R.plurals.ui_podcast_counts, podcast.getEpisodeCount(), podcast.getEpisodeCount(), podcast.getUnplayedCount())
                + "\n" + getResources().getQuantityString(R.plurals.catalog_subscription_download_count,
                        podcast.getDownloadedCount(), podcast.getDownloadedCount());
        String source = podcast.getSourceUrl() == null ? "" : "\n\n" + getString(R.string.ui_podcast_source, podcast.getSourceUrl().toString());
        new AlertDialog.Builder(this).setTitle(displayTitle(podcast.getTitle()))
                .setMessage((podcast.getAuthor().isEmpty() ? "" : podcast.getAuthor() + "\n\n") + counts + "\n\n" + updated + source)
                .setPositiveButton(android.R.string.ok, null).show();
    }

    private void showPodcastSpeed(long id){podcastSettings.showPodcastSpeed(id);}
    private void showPodcastLogin(CatalogSubscriptionSummary podcast){podcastSettings.showPodcastLogin(podcast);}
    private void showPodcastSettings(CatalogSubscriptionSummary podcast){podcastSettings.showPodcastSettings(podcast);}
    private boolean isSavingPodcastSettings(){return podcastSettings!=null&&podcastSettings.isSavingAutomaticDownload();}

    private boolean canRequestDownload(String id,boolean downloaded){
        if(downloaded)return episodeDownloadController!=null&&!workState().hasDownloadCompetingWork(false);
        return manualDownloads!=null&&!manualDownloads.isPending(id)&&!workState().hasDownloadCompetingWork(false);
    }

    private void addDownloadMenu(PopupMenu popup,String id,String title,boolean downloaded,boolean subscribed){
        var request=manualDownloads==null?null:manualDownloads.find(id);
        if(request!=null&&request.state()!=io.github.jnesew.plainpoddroid.core.download.ManualDownloadQueue.State.FAILED){
            menuAction(popup,R.string.episode_cancel_download_action,true,()->manualDownloads.cancel(id));return;
        }
        int label=downloaded?R.string.episode_delete_download_action:request!=null?R.string.ui_retry_download:R.string.episode_download_action;
        menuAction(popup,label,canRequestDownload(id,downloaded)&&(downloaded||subscribed),
                ()->{if(downloaded)deleteEpisodeDownload(id);else downloadEpisode(id,title);});
    }

    private void showEpisodeRowMenu(EpisodeListView.Row row, View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        menuAction(popup, R.string.episode_details_open_action, true, () -> loadEpisodeDetails(row.id()));
        menuAction(popup,R.string.ui_select_episodes,true,()->selectEpisodeRow(row.id()));
        if(row.podcast()!=null)menuAction(popup,R.string.ui_mark_older_played,batchController!=null&&!isEpisodeActionBusy(),
                ()->batchController.previewPodcast(row.podcast().subscriptionId(),row.id()));
        boolean current = row.id().equals(playbackPanel.currentMediaId());
        menuAction(popup, playbackPanel.isPlaying(row.id()) ? R.string.ui_pause : R.string.ui_play,
                playbackPanel.isConnected() && (current || (!workState().hasPlaybackCompetingWork()
                        && (row.downloaded() || row.podcast() == null || row.podcast().subscribed()))),
                () -> playEpisodeRow(row.id()));
        addDownloadMenu(popup,row.id(),row.title(),row.downloaded(),row.podcast()==null||row.podcast().subscribed());
        boolean queueEnabled = episodeQueueController != null && episodeQueueController.getState().getPhase() == EpisodeQueueController.Phase.READY && !hasQueueCompetingWork();
        int position = findQueuePosition(row.id());
        menuAction(popup, position >= 0 ? R.string.episode_queue_remove_action : R.string.episode_queue_append_action,
                queueEnabled, () -> { if (position >= 0) removeQueuedEpisode(row.id()); else appendQueuedEpisode(row.id()); });
        menuAction(popup, R.string.episode_queue_play_next_action, queueEnabled && position != 0, () -> playQueuedEpisodeNext(row.id()));
        if (row.queuePosition() >= 0) {
            menuAction(popup, R.string.episode_queue_move_up_action, queueEnabled && position > 0, () -> moveQueuedEpisode(row.id(), position - 1));
            menuAction(popup, R.string.episode_queue_move_down_action, queueEnabled && position >= 0
                    && position + 1 < episodeQueueController.getState().getEntries().size(), () -> moveQueuedEpisode(row.id(), position + 1));
        }
        popup.show();
    }

    private boolean canSelectEpisodes(){
        return switch(navigationShell.current().destination()){
            case PODCAST_EPISODES->podcastEpisodeList.hasRows();case QUEUE->queueList.hasRows();
            case DOWNLOADS->downloadsPanel.hasRows();case FAVORITES->favoritesPanel.hasRows();case HISTORY->historyPanel.hasRows();
            case NEW_EPISODES->newEpisodesPanel.hasRows();case IN_PROGRESS->inProgressPanel.hasRows();default->false;
        };
    }

    private void beginEpisodeSelection(){
        switch(navigationShell.current().destination()){
            case PODCAST_EPISODES->podcastEpisodeList.beginSelection();case QUEUE->queueList.beginSelection();
            case DOWNLOADS->downloadsPanel.beginSelection();case FAVORITES->favoritesPanel.beginSelection();case HISTORY->historyPanel.beginSelection();
            case NEW_EPISODES->newEpisodesPanel.beginSelection();case IN_PROGRESS->inProgressPanel.beginSelection();default->{ }
        }
    }

    private void showDestinationMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        switch(navigationShell.current().destination()){
            case PODCAST_EPISODES,QUEUE,DOWNLOADS,FAVORITES,HISTORY,NEW_EPISODES,IN_PROGRESS ->
                menuAction(popup,R.string.ui_select_episodes,canSelectEpisodes(),this::beginEpisodeSelection);
            default->{ }
        }
        switch (navigationShell.current().destination()) {
            case EPISODE -> {
                menuAction(popup,R.string.ui_stop_playback,playbackPanel.isConnected()&&!playbackPanel.currentMediaId().isEmpty(),playbackPanel::stopPlayback);
                menuAction(popup, R.string.ui_playback_options, true, playbackPanel::showOptions);
                EpisodeDetails details = currentEpisodeDetails();
                if(details!=null&&details.getPodcast()!=null)menuAction(popup,R.string.ui_podcast_speed,true,
                        ()->showPodcastSpeed(details.getPodcast().subscriptionId()));
                menuAction(popup, R.string.ui_episode_information, details != null, () -> {
                    if (details == null) return;
                    String info = (details.getAuthor().isEmpty() ? "" : details.getAuthor() + "\n\n")
                            + episodeMetadata.details(details);
                    new AlertDialog.Builder(this).setTitle(R.string.ui_episode_information)
                            .setMessage(info).setPositiveButton(android.R.string.ok, null).show();
                });
                menuAction(popup, details != null && details.isPlayed() ? R.string.episode_mark_unplayed_action : R.string.episode_mark_played_action,
                        episodePlayedButton.isEnabled(), () -> episodePlayedButton.performClick());
                menuAction(popup, R.string.episode_details_page_action, openEpisodePageButton.isEnabled(), () -> openEpisodePageButton.performClick());
                if (details != null) {
                    addDownloadMenu(popup,details.getMediaId(),details.getTitle(),details.isDownloaded(),!episodeArchived(details));
                }
            }
            case PODCAST_EPISODES -> {
                menuAction(popup,R.string.ui_mark_all_played,batchController!=null&&!isEpisodeActionBusy(),
                        ()->batchController.previewPodcast(navigationShell.current().podcastId(),null));
                menuAction(popup,R.string.ui_sort_episodes,true,this::showEpisodeOrder);
                for(var podcast:catalogBrowserController.getState().getSubscriptions())
                    if(podcast.getSubscriptionId()==navigationShell.current().podcastId()){addPodcastActions(popup,podcast);break;}
            }
            case PODCASTS, ARCHIVES -> {
                menuAction(popup, R.string.ui_sort_podcasts, true, this::showPodcastOrder);
                menuAction(popup, R.string.refresh_all_action, refreshAllSubscriptionsButton.isEnabled(), () -> refreshAllSubscriptionsButton.performClick());
                menuAction(popup, R.string.refresh_all_cancel_action, cancelRefreshAllButton.isEnabled(), () -> cancelRefreshAllButton.performClick());
                menuAction(popup, R.string.ui_add_podcast, true, navigationShell::addPodcast);
            }
            case QUEUE -> menuAction(popup, R.string.episode_queue_refresh_action, refreshEpisodeQueueButton.isEnabled(), () -> refreshEpisodeQueueButton.performClick());
            case DOWNLOADS, FAVORITES, HISTORY, NEW_EPISODES, IN_PROGRESS -> menuAction(popup, R.string.catalog_refresh_action, true, this::refreshLibraryLists);
            case SETTINGS, ADD_PODCAST -> menuAction(popup, R.string.ui_about, true, () -> startActivity(new Intent(this, AboutActivity.class)));
        }
        popup.show();
    }

    private void showPodcastOrder() {
        var settings = new LibraryBrowseSettings(this);
        new AlertDialog.Builder(this).setTitle(R.string.ui_sort_podcasts)
                .setSingleChoiceItems(new String[] {getString(R.string.ui_latest_episode), getString(R.string.ui_name)},
                        settings.getOrder().ordinal(), (dialog, which) -> {
                            if (settings.setOrder(io.github.jnesew.plainpoddroid.core.catalog.CatalogSubscriptionOrder.values()[which])) {
                                libraryRendered = false; renderCatalogSubscriptions(catalogBrowserController.getState());
                            } else Toast.makeText(this, R.string.ui_preference_save_failed, Toast.LENGTH_SHORT).show();
                            dialog.dismiss();
                        }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void showEpisodeOrder() {
        long id = navigationShell.current().podcastId();
        for (var podcast : catalogBrowserController.getState().getSubscriptions()) {
            if (podcast.getSubscriptionId() != id) continue;
            new AlertDialog.Builder(this).setTitle(R.string.ui_sort_episodes)
                    .setSingleChoiceItems(new String[] {getString(R.string.ui_newest_first), getString(R.string.ui_oldest_first)},
                            podcast.getEpisodeOrder().ordinal(), (dialog, which) -> {
                                dialog.dismiss();
                                try { screenWorker.execute(() -> {
                                    boolean saved;
                                    try { ((PlainPodDroidApplication) getApplication()).getLibraryCatalog()
                                            .setEpisodeOrder(id, CatalogBrowseQuery.Order.values()[which]); saved = true; }
                                    catch (IOException | RuntimeException e) { saved = false; }
                                    boolean committed = saved;
                                    mainHandler.post(() -> { if (isDestroyed()) return;
                                        if (committed) reloadCatalogAfterLocalAction();
                                        else Toast.makeText(this, R.string.ui_preference_save_failed, Toast.LENGTH_SHORT).show(); });
                                }); } catch (RejectedExecutionException e) { Toast.makeText(this, R.string.ui_preference_save_failed, Toast.LENGTH_SHORT).show(); }
                            }).setNegativeButton(android.R.string.cancel, null).show();
            return;
        }
    }

    private void configureEdgeToEdge() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            // Edge-to-edge is enforced for this target SDK on Android 15 and newer.
            return;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            configureApi30To34EdgeToEdge(window);
        } else {
            configureLegacyEdgeToEdge(window);
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    @SuppressWarnings("deprecation") // Deprecated in API 35, but required on API 30–34.
    private static void configureApi30To34EdgeToEdge(Window window) {
        window.setDecorFitsSystemWindows(false);
    }

    @SuppressWarnings("deprecation") // Required framework fallback for the API 29 minimum.
    private static void configureLegacyEdgeToEdge(Window window) {
        window.getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    private static void applySystemBarInsets(View root) {
        int initialLeft = root.getPaddingLeft();
        int initialTop = root.getPaddingTop();
        int initialRight = root.getPaddingRight();
        int initialBottom = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(
                        initialLeft + bars.left,
                        initialTop + bars.top,
                        initialRight + bars.right,
                        initialBottom + bars.bottom);
            } else {
                applyLegacySystemBarInsets(
                        view,
                        insets,
                        initialLeft,
                        initialTop,
                        initialRight,
                        initialBottom);
            }
            return insets;
        });
        root.requestApplyInsets();
    }

    @SuppressWarnings("deprecation") // Required framework fallback for the API 29 minimum.
    private static void applyLegacySystemBarInsets(
            View view,
            WindowInsets insets,
            int initialLeft,
            int initialTop,
            int initialRight,
            int initialBottom) {
        view.setPadding(
                initialLeft + insets.getSystemWindowInsetLeft(),
                initialTop + insets.getSystemWindowInsetTop(),
                initialRight + insets.getSystemWindowInsetRight(),
                initialBottom + insets.getSystemWindowInsetBottom());
    }
}
