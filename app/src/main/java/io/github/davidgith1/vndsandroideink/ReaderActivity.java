package io.github.davidgith1.vndsandroideink;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.StyleSpan;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import io.github.davidgith1.vndsandroideink.engine.VnEngine;
import io.github.davidgith1.vndsandroideink.nscripter.NsResolution;
import io.github.davidgith1.vndsandroideink.nscripter.NsSaveManager;
import io.github.davidgith1.vndsandroideink.nscripter.NsScriptEngine;
import io.github.davidgith1.vndsandroideink.nscripter.NsScriptSource;
import io.github.davidgith1.vndsandroideink.vnds.ScriptEngine;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class ReaderActivity extends AppCompatActivity implements VnEngine.Listener {

    public static final String EXTRA_VN_DIR = "vn_dir";
    public static final String EXTRA_VN_TITLE = "vn_title";
    /** Optional int extra: a slot to load at startup instead of starting main.scr from the top --
     * {@link SaveManager#SLOT_RESUME} or a manual 1..{@link SaveManager#SLOT_COUNT} slot. Omitted
     * (or absent) means start fresh. */
    public static final String EXTRA_LOAD_SLOT = "load_slot";
    /** Optional string extra: {@link VnEntry.EngineType#name()}, set by {@code MainActivity} from
     * the pack's own persisted detection result (see {@code VnImporter}'s ".engine" marker).
     * Omitted only by direct-launch instrumented tests that bypass MainActivity/VnImporter
     * entirely -- see {@link #decideEngineType()}'s fallback for those. */
    public static final String EXTRA_VN_ENGINE = "vn_engine";

    private ImageView backgroundImage;
    private FrameLayout spriteLayer;
    private View sceneContainer;
    /** The whole activity content area, used as the target for the Onyx full-screen refresh
     * (open/resume, and the manual "Refresh screen" menu item). */
    private View rootContentView;
    private View tapCatcher;
    private LinearLayout choicesPanel;
    private LinearLayout textPanel;
    private TextView speakerName;
    private TextView bodyText;
    private TextView advanceButton;
    private TextView autoButton;
    private TextView textLogButton;
    private TextView advanceToChoiceButton;
    private MenuPanel menu;

    private VnEngine engine;
    private File vnDir;
    /** Whether {@link #engine} is an {@link NsScriptEngine} rather than the VNDS {@link
     * ScriptEngine} -- decided once in {@code onCreate()} by {@link #decideEngineType()}. Gates
     * the sprite bookkeeping split ({@link #sprites} vs {@link #nsSprites}) and every save/load
     * path. */
    private boolean nsEngineActive = false;

    private boolean einkMode;
    private boolean muteAudio;
    /** Whether the Onyx EPD SDK (see {@link EinkRefreshManager}) is usable on this device --
     * computed once, since it can't change during a run. */
    private boolean einkRefreshSupported;

    /** Foreground sprite layers currently on screen, in draw order. setimg always appends a new
     * layer (never replaces by position -- multiple layers legitimately share the same x,y, e.g.
     * a body+clothes+face+expression portrait stack); only bgload ever clears them. */
    private final List<SpriteInstance> sprites = new ArrayList<>();
    /** NScripter's numbered-layer sprites: unlike VNDS's append-only {@link #sprites}, a layer
     * persists across background changes and is replaced/cleared in place by its own number --
     * see {@link VnEngine.Listener#onSprite} and {@link #onSpriteCleared}. */
    private final Map<Integer, SpriteInstance> nsSprites = new java.util.HashMap<>();
    private String currentBgPath = null;
    /** See {@link VnEngine.SpriteTransparency} -- tracked alongside {@link #currentBgPath} so a
     * save/load round-trip doesn't revert an alpha-mask-tagged background (e.g. a real ONScripter
     * title screen's message-box/title-text art loaded via "bg") back to opaque. Always OPAQUE for
     * VNDS, which never tags backgrounds. */
    private VnEngine.SpriteTransparency currentBgTransparency = VnEngine.SpriteTransparency.OPAQUE;
    /** See {@link VnEngine.Listener#onSprite}'s doc on alphaMaskCells; only meaningful when {@link
     * #currentBgTransparency} is {@code ALPHA_MASK}. */
    private int currentBgAlphaCells = 1;
    /** The currently-intended music track's path (or null for none/stopped), tracked independently
     * of whether it's actually audible right now (muted, or paused for an overlay) -- so a save
     * captures what SHOULD be playing and loading it back resumes that track, same as background. */
    private String currentMusicPath = null;
    private boolean finished = false;

    /** The options of the choice menu currently on screen, or null when none is -- kept so a save
     * taken mid-choice (engine state WAITING_CHOICE) can persist what to redisplay on load; see
     * {@link #showChoices} (where it's set) and {@link #saveToSlot}/{@link #loadFromSlot}. Only
     * consulted for the VNDS engine -- NScripter's own save format tracks this internally (see
     * {@code NsScriptEngine.Snapshot}'s {@code lastChoice*} fields). */
    private List<String> currentChoiceOptions = null;

    /** Set for the duration of {@link #advanceToNextChoice}: suppresses sound effects and
     * intermediate music cues, and forces images/text to apply instantly (no fade/typewriter),
     * regardless of e-ink mode -- see the listener methods below and {@link #showImage}/
     * {@link #updateBodyText}. */
    private boolean turboSkipping = false;
    /** Whether a "music" cue fired at all during the current turbo-skip run, and the file it
     * named (possibly null for "music ~", i.e. stop) -- only this last one actually plays, once
     * the skip lands on a choice or the story ends; see {@link #advanceToNextChoice}. */
    private boolean turboMusicChanged = false;
    private File turboMusicFile = null;

    /** Scale from the VN's declared img.ini resolution to actual on-screen pixels. */
    private float sceneScale = 1f;
    /** The VN's declared virtual canvas size (img.ini / NScripter's ";mode", see
     * {@link #applySceneAspect}), used to resolve an {@code AUTO_POSITION_*} sentinel (see
     * {@link #resolveAutoPositionX}) against -- defaults match {@link #applySceneAspect}'s own
     * 640x480 fallback. */
    private int sceneVirtualWidth = 640;
    private int sceneVirtualHeight = 480;

    /** Pixel budget available to bodyText; -1 until measured after the first layout pass. */
    private int bodyBudgetPx = -1;

    private final List<BodyLine> bodyLines = new ArrayList<>();
    /** The most recent "@Name" speaker seen, used only for the save-slot preview text; reset on
     * every page turn so a stale name can't attach itself to unrelated later text. */
    private String lastSpeaker = "";
    /** Every line read so far this session (independent of the on-screen page/overflow logic), for Text log. */
    private final List<SaveManager.SavedLine> textLog = new ArrayList<>();
    private final Handler typeHandler = new Handler(Looper.getMainLooper());
    private Runnable typewriterRunnable;
    /** True while {@link #typewriterRunnable} is still revealing text a character at a time --
     * lets {@link #advance()} make a tap during that reveal complete the line instantly instead
     * of also advancing past it. */
    private boolean typingInProgress = false;
    /** The full text the current typewriter reveal (or instant set) is building toward, so a tap
     * mid-reveal can jump straight to it. */
    private CharSequence typewriterFullText = "";

    private boolean autoAdvance = false;
    /** The most recently shown blocking dialogue line, used to pace Auto-advance. */
    private String lastAutoAdvanceText = "";
    private final Runnable autoAdvanceRunnable = this::autoAdvanceTick;

    private MediaPlayer musicPlayer;
    private MediaPlayer sfxPlayer;
    private int sfxRepeatsRemaining;
    /** Tracks which players an overlay (text log, menu, settings, save/load) paused, so resuming
     * doesn't restart something that was already stopped/changed while the overlay was open. */
    private boolean musicPausedForOverlay = false;
    private boolean sfxPausedForOverlay = false;

    /** A pending timed engine-resume, if any -- covers both a scripted "delay" ({@link #onDelay})
     * and holding a "text ~" clear until a voice-synced sound effect finishes ({@link #onTextClear}).
     * Cancelled and re-armed with its remaining time across an overlay open/close so nothing it
     * unblocks (bgload/setimg/etc., or the text clear itself) can happen while an overlay is on screen. */
    private Runnable pendingDelayRunnable;
    private Runnable pendingDelayAction; // what to run when pendingDelayRunnable fires; survives overlay pause/resume
    private long delayDeadlineElapsed = -1; // SystemClock.elapsedRealtime() target; -1 = none pending
    private long delayRemainingMsAtPause = -1; // >=0 while an overlay is holding a timed resume in reserve
    /** Total-time-read tracking: sums gaps between consecutive "the story actually progressed"
     * events (a tap, an auto-advance tick, a choice, or a delay/sfx-wait naturally elapsing) --
     * see {@link #recordActivity}. Any single gap of {@link #IDLE_THRESHOLD_MS} or more (sitting
     * on a line with nothing happening) is excluded entirely rather than capped, so time the app
     * just sat open unattended doesn't count as reading time. */
    private static final long IDLE_THRESHOLD_MS = 60_000;
    private long lastActivityElapsedMs = -1; // -1 = no activity recorded yet this foreground stretch
    private long activeMsThisSession = 0;

    private LruCache<String, Bitmap> bitmapCache;
    /** The VN's own bundled "default.ttf", if it ships one; null if it doesn't. Loaded once since
     * it can't change mid-story -- only whether it's actually *used* (see {@link #applyPrefs}) can. */
    private Typeface novelFont;
    /** Whether a completion guide has been imported for this VN (from the library) -- checked
     * once since nothing during this session can change it, and shared by {@link #initMenu} and
     * {@link #initTapAndAutoControls} instead of each re-checking the filesystem. */
    private boolean hasGuide;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        NoAnimTransition.applyOpenOverride(this);
        setContentView(R.layout.activity_reader);
        EdgeToEdge.applyInsets(findViewById(R.id.readerRoot));

        String dirPath = getIntent().getStringExtra(EXTRA_VN_DIR);
        vnDir = new File(dirPath);
        nsEngineActive = decideEngineType();
        novelFont = loadNovelFont();
        hasGuide = GuideManager.hasGuide(this, vnKey());

        initViews();
        initMenu();
        initTapAndAutoControls();

        applyPrefs();
        recomputeBodyBudget();
        initEngine();

        menu.wireBackPress(this, this::closeOverlay, this::confirmThenLeaveViaBack);
    }

    /** The hardware/gesture back button's own version of the menu's Library row (see {@link
     * #initMenu}'s "menuLibrary" click listener) -- same "confirm before leaving if not resumable"
     * gate, instead of the platform's default back behavior finishing the activity unconditionally
     * with no warning. Mirrors that row's logic exactly, including not calling {@link
     * #openOverlay()} first (a pre-existing quirk of that row, left as-is here rather than fixed as
     * a drive-by change in this unrelated fix). */
    private void confirmThenLeaveViaBack() {
        Runnable goToLibrary = () -> NoAnimTransition.finish(this);
        if (canResumeNow() || finished) {
            goToLibrary.run();
        } else {
            ConfirmDialog.show(this, getString(R.string.confirm_library_title),
                    getString(R.string.warn_not_resumable_message),
                    getString(R.string.return_action), goToLibrary::run, this::closeOverlay);
        }
    }

    /** Binds every view, sizes the scene area to the VN's declared resolution, and sets up the
     * bitmap cache -- everything that has to happen before any script line can be drawn. */
    private void initViews() {
        TextView titleView = findViewById(R.id.titleView);
        titleView.setText(getIntent().getStringExtra(EXTRA_VN_TITLE));

        rootContentView = findViewById(android.R.id.content);
        sceneContainer = findViewById(R.id.sceneContainer);
        backgroundImage = findViewById(R.id.backgroundImage);
        spriteLayer = findViewById(R.id.spriteLayer);
        tapCatcher = findViewById(R.id.tapCatcher);
        choicesPanel = findViewById(R.id.choicesPanel);
        textPanel = findViewById(R.id.textPanel);
        speakerName = findViewById(R.id.speakerName);
        bodyText = findViewById(R.id.bodyText);
        advanceButton = findViewById(R.id.advanceButton);
        autoButton = findViewById(R.id.btnAuto);
        textLogButton = findViewById(R.id.btnTextLog);
        advanceToChoiceButton = findViewById(R.id.btnAdvanceToChoice);

        applySceneAspect();

        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 6);
        bitmapCache = new LruCache<String, Bitmap>(maxKb) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
    }

    /** Wires the hamburger menu panel and every row inside it (Refresh, Save, Load, Settings,
     * Library, Quit). */
    private void initMenu() {
        View menuPanelView = findViewById(R.id.menuPanel);
        View menuScrimView = findViewById(R.id.menuScrim);
        menu = new MenuPanel(menuPanelView, menuScrimView);

        einkRefreshSupported = EinkRefreshManager.isSupported();
        View menuRefreshRow = findViewById(R.id.menuRefresh);
        View menuRefreshDivider = findViewById(R.id.menuRefreshDivider);
        if (einkRefreshSupported) {
            menuRefreshRow.setOnClickListener(v -> {
                menu.close();
                closeOverlay();
                // Deferred with post so the flash happens after the menu's own GONE visibility
                // change is actually drawn, instead of flashing a frame that still shows it.
                rootContentView.post(EinkRefreshManager::fullRefresh);
            });
        } else {
            // Onyx-only hardware feature: hide the row entirely rather than show a menu item
            // that does nothing on every other device.
            menuRefreshRow.setVisibility(View.GONE);
            menuRefreshDivider.setVisibility(View.GONE);
        }

        findViewById(R.id.menuButton).setOnClickListener(v -> {
            if (menu.isOpen()) {
                menu.close();
                closeOverlay();
            } else {
                menu.open();
                openOverlay();
            }
        });
        menuScrimView.setOnClickListener(v -> {
            menu.close();
            closeOverlay();
        });
        findViewById(R.id.menuSave).setOnClickListener(v -> {
            menu.close();
            openSaveDialog();
        });
        findViewById(R.id.menuLoad).setOnClickListener(v -> {
            menu.close();
            openLoadDialog();
        });
        findViewById(R.id.menuAdvanceToChoice).setOnClickListener(v -> {
            menu.close();
            advanceToNextChoice();
        });
        findViewById(R.id.menuSettings).setOnClickListener(v -> {
            menu.close();
            openOverlay();
            SettingsDialog.show(this, this::applyPrefs, this::closeOverlay);
        });
        findViewById(R.id.menuVariables).setOnClickListener(v -> {
            menu.close();
            openOverlay();
            VariablesDialog.show(this, engine.getVariablesSnapshot(), engine.getGlobalsSnapshot(),
                    this::onVarChanged, this::closeOverlay);
        });
        View menuGuideRow = findViewById(R.id.menuGuide);
        View menuGuideDivider = findViewById(R.id.menuGuideDivider);
        if (hasGuide) {
            menuGuideRow.setOnClickListener(v -> {
                menu.close();
                openOverlay();
                GuideDialog.show(this, vnKey(), getIntent().getStringExtra(EXTRA_VN_TITLE), this::closeOverlay);
            });
        } else {
            // No guide imported for this VN (from the library) -- hide the row entirely rather
            // than show a menu item that does nothing.
            menuGuideRow.setVisibility(View.GONE);
            menuGuideDivider.setVisibility(View.GONE);
        }
        findViewById(R.id.menuLibrary).setOnClickListener(v -> {
            menu.close();
            Runnable goToLibrary = () -> NoAnimTransition.finish(this);
            if (canResumeNow() || finished) {
                goToLibrary.run();
            } else {
                ConfirmDialog.show(this, getString(R.string.confirm_library_title),
                        getString(R.string.warn_not_resumable_message),
                        getString(R.string.return_action), goToLibrary::run, this::closeOverlay);
            }
        });
        findViewById(R.id.menuQuit).setOnClickListener(v -> {
            menu.close();
            String message = (canResumeNow() || finished)
                    ? getString(R.string.confirm_quit_message)
                    : getString(R.string.warn_not_resumable_message);
            ConfirmDialog.show(this, getString(R.string.confirm_quit_title), message,
                    getString(R.string.quit), this::finishAffinity, this::closeOverlay);
        });
    }

    /** Wires tap-to-advance (tap-catcher, Next button), Auto-advance toggle, and the Text log
     * button. While a choice is on screen, the bottom bar stays visible (rather than hiding, as it
     * used to) but only Text log and Guide stay usable -- see {@link #choicesShowing()}. */
    private void initTapAndAutoControls() {
        tapCatcher.setOnClickListener(v -> advance());
        advanceButton.setOnClickListener(v -> {
            if (choicesShowing()) {
                return;
            }
            advance();
        });
        autoButton.setOnClickListener(v -> {
            if (choicesShowing()) {
                return;
            }
            toggleAutoAdvance();
        });
        // Deliberately not guarded by choicesShowing(): reviewing the backlog while deciding a
        // choice is as useful as checking the guide is.
        textLogButton.setOnClickListener(v -> {
            openOverlay();
            TextLogDialog.show(this, textLog, activeFont(), this::closeOverlay);
        });
        advanceToChoiceButton.setOnClickListener(v -> {
            if (choicesShowing()) {
                return;
            }
            advanceToNextChoice();
        });

        View guideButton = findViewById(R.id.btnGuide);
        View guideButtonDivider = findViewById(R.id.btnGuideDivider);
        if (hasGuide) {
            guideButton.setVisibility(View.VISIBLE);
            guideButtonDivider.setVisibility(View.VISIBLE);
            // Deliberately not guarded by choicesShowing(): checking the guide while deciding a
            // choice (e.g. which option leads to which route) is exactly when it's most useful.
            guideButton.setOnClickListener(v -> {
                openOverlay();
                GuideDialog.show(this, vnKey(), getIntent().getStringExtra(EXTRA_VN_TITLE), this::closeOverlay);
            });
        }
    }

    private boolean choicesShowing() {
        return choicesPanel.getVisibility() == View.VISIBLE;
    }

    /** Decides which engine this VN needs, read from {@link #EXTRA_VN_ENGINE} -- the value
     * {@code MainActivity} passes through from {@code VnEntry.engineType} (itself persisted at
     * import time by {@code VnImporter}). Falls back to auto-detecting a plain-text/obfuscated
     * NScripter script directly in {@code vnDir} only when the extra is absent entirely, which
     * only happens when something launches {@code ReaderActivity} without going through {@code
     * MainActivity}/{@code VnImporter} at all (namely, this project's own instrumented tests that
     * exercise the reader directly against a hand-built scratch folder). Called before {@link
     * #initViews()} so {@link #applySceneAspect()} can already see the decision. */
    private boolean decideEngineType() {
        String extra = getIntent().getStringExtra(EXTRA_VN_ENGINE);
        if (extra != null) {
            return VnEntry.EngineType.NSCRIPTER.name().equals(extra);
        }
        return NsScriptSource.hasAnyScript(vnDir);
    }

    /** Constructs the script engine and either starts main.scr fresh or repositions it at a
     * requested save slot (see {@link #EXTRA_LOAD_SLOT}); which engine is {@link #nsEngineActive},
     * already decided by {@link #decideEngineType()} back in {@code onCreate()}. */
    private void initEngine() {
        if (nsEngineActive) {
            engine = new NsScriptEngine(vnDir, this, new java.util.HashMap<>());
            engine.setDelaysEnabled(computeDelaysEnabled());
            try {
                int nsLoadSlot = getIntent().getIntExtra(EXTRA_LOAD_SLOT, -1);
                if (nsLoadSlot >= 0 && NsSaveManager.load(this, vnKey(), nsLoadSlot) != null) {
                    loadFromSlot(nsLoadSlot);
                } else {
                    engine.start();
                }
            } catch (RuntimeException e) {
                // hasAnyScript() recognizes obfuscated formats load() can't actually decode yet
                // (nscr_sec.dat -- see NsObfuscation); fail back to the library rather than crash.
                Toast.makeText(this, R.string.load_failed, Toast.LENGTH_LONG).show();
                NoAnimTransition.finish(this);
            }
            return;
        }
        engine = new ScriptEngine(vnDir, this, SaveManager.loadGlobals(this, vnKey()));
        engine.setDelaysEnabled(computeDelaysEnabled());
        int loadSlot = getIntent().getIntExtra(EXTRA_LOAD_SLOT, -1);
        if (loadSlot >= 0 && SaveManager.load(this, vnKey(), loadSlot) != null) {
            loadFromSlot(loadSlot);
        } else {
            engine.start();
        }
    }

    /**
     * Sizes the scene area to exactly match the VN's declared resolution -- VNDS's img.ini for
     * that engine, or NScripter's leading ";mode&lt;width&gt;" script header for the other (see
     * {@link NsScriptSource#peekResolution}) -- falling back to the classic 640x480 if missing
     * either way, so there's no letterboxing and the text panel below always gets the rest of the
     * screen, consistently, regardless of which background image happens to be showing.
     */
    private void applySceneAspect() {
        int w, h;
        if (nsEngineActive) {
            NsResolution res = NsScriptSource.peekResolution(vnDir);
            w = res.width;
            h = res.height;
        } else {
            Map<String, String> ini = VnImporter.parseKeyValueFile(new File(vnDir, "img.ini"));
            w = parseIntOr(ini.get("width"), 640);
            h = parseIntOr(ini.get("height"), 480);
        }
        if (w <= 0 || h <= 0) {
            w = 640;
            h = 480;
        }
        sceneVirtualWidth = w;
        sceneVirtualHeight = h;

        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int sceneHeight = Math.round(screenWidth * (h / (float) w));

        sceneScale = screenWidth / (float) w;

        ViewGroup.LayoutParams lp = sceneContainer.getLayoutParams();
        lp.height = sceneHeight;
        if (lp instanceof LinearLayout.LayoutParams) {
            ((LinearLayout.LayoutParams) lp).weight = 0;
        }
        sceneContainer.setLayoutParams(lp);
    }

    /** Whether "delay" should actually pause: always in non-eink mode, optionally in eink mode. */
    private boolean computeDelaysEnabled() {
        return !einkMode || !Prefs.isInstantDelaysInEink(this);
    }

    // ---- Auto-advance ----------------------------------------------------------------------

    private void toggleAutoAdvance() {
        autoAdvance = !autoAdvance;
        updateAutoButtonStyle();
        if (autoAdvance) {
            scheduleAutoAdvance();
        } else {
            pauseAutoAdvance();
        }
    }

    private void updateAutoButtonStyle() {
        if (autoAdvance) {
            autoButton.setBackgroundColor(ContextCompat.getColor(this, R.color.eink_text));
            autoButton.setTextColor(ContextCompat.getColor(this, R.color.eink_background));
        } else {
            autoButton.setBackgroundResource(R.drawable.bg_library_item);
            autoButton.setTextColor(ContextCompat.getColor(this, R.color.eink_text));
        }
    }

    private void autoAdvanceTick() {
        if (!autoAdvance) {
            return;
        }
        advance();
    }

    /** Cancels any pending auto-advance tick without touching the autoAdvance toggle itself. */
    private void pauseAutoAdvance() {
        typeHandler.removeCallbacks(autoAdvanceRunnable);
    }

    /** Tracks whether a dialog/menu overlay is currently up, so {@link #onResume} knows not to
     * un-freeze things behind it if the app is merely returning from the background (e.g. Home)
     * while a dialog it never dismissed is still showing. */
    private boolean overlayShowing = false;

    /** Call when any full-screen/dialog overlay (text log, menu, settings, save/load) opens. */
    private void openOverlay() {
        overlayShowing = true;
        pauseForOverlay();
    }

    /** Call when that overlay closes (its dismiss/close callback). */
    private void closeOverlay() {
        overlayShowing = false;
        resumeForOverlay();
    }

    /** Freezes Auto-advance and any playing music/sfx so they don't keep running underneath an
     * overlay, or in the background after Home/task-switch (see {@link #onPause}). */
    private void pauseForOverlay() {
        pauseAutoAdvance();
        if (musicPlayer != null && musicPlayer.isPlaying()) {
            musicPlayer.pause();
            musicPausedForOverlay = true;
        }
        if (sfxPlayer != null && sfxPlayer.isPlaying()) {
            sfxPlayer.pause();
            sfxPausedForOverlay = true;
        }
        if (pendingDelayRunnable != null) {
            typeHandler.removeCallbacks(pendingDelayRunnable);
            pendingDelayRunnable = null;
            delayRemainingMsAtPause = Math.max(0, delayDeadlineElapsed - android.os.SystemClock.elapsedRealtime());
        }
    }

    /** Reverses {@link #pauseForOverlay()} when the overlay closes. */
    private void resumeForOverlay() {
        if (musicPausedForOverlay && musicPlayer != null && !musicPlayer.isPlaying()) {
            musicPlayer.start();
        }
        musicPausedForOverlay = false;
        if (sfxPausedForOverlay && sfxPlayer != null && !sfxPlayer.isPlaying()) {
            sfxPlayer.start();
        }
        sfxPausedForOverlay = false;
        if (delayRemainingMsAtPause >= 0) {
            scheduleTimedResume(delayRemainingMsAtPause, pendingDelayAction);
            delayRemainingMsAtPause = -1;
        }
        scheduleAutoAdvance();
    }

    /** Arms a pending engine resume for {@code ms} from now, running {@code action} once it
     * elapses (see {@link #onDelay} and {@link #onTextClear}). */
    private void scheduleTimedResume(long ms, Runnable action) {
        pendingDelayAction = action;
        delayDeadlineElapsed = android.os.SystemClock.elapsedRealtime() + ms;
        pendingDelayRunnable = () -> {
            pendingDelayRunnable = null;
            delayDeadlineElapsed = -1;
            recordActivity(); // a delay/sfx-wait elapsing on its own is the story progressing, not idling
            Runnable a = pendingDelayAction;
            pendingDelayAction = null;
            if (a != null) {
                a.run();
            }
            scheduleAutoAdvance();
        };
        typeHandler.postDelayed(pendingDelayRunnable, Math.max(0, ms));
    }

    /** Re-arms the next auto-advance tick if Auto is on and there's a line waiting for a tap. */
    private void scheduleAutoAdvance() {
        typeHandler.removeCallbacks(autoAdvanceRunnable);
        if (!autoAdvance || finished || engine == null || engine.getState() != VnEngine.State.WAITING_TAP) {
            return;
        }
        long delay = computeAutoAdvanceDelayMs(lastAutoAdvanceText);
        if (Prefs.isAutoWaitForSfx(this)) {
            delay = Math.max(delay, sfxRemainingMs());
        }
        if (engine.isPageEndPending()) {
            // This is the last line of the current page (a "text ~" clear comes next): give the
            // player extra time to finish reading before it's wiped for the next page.
            delay += Prefs.getAutoPagePauseSeconds(this) * 1000L;
        }
        typeHandler.postDelayed(autoAdvanceRunnable, delay);
    }

    private long computeAutoAdvanceDelayMs(String text) {
        String trimmed = text.trim();
        int words = trimmed.isEmpty() ? 1 : trimmed.split("\\s+").length;
        long ms = Math.round(words / (double) Prefs.getAutoAdvanceWpm(this) * 60000.0);
        return Math.max(900, ms);
    }

    /** How much longer the currently-playing sound effect (if any) has left, so Auto-advance
     * doesn't cut it short. Never waits on an infinite loop. */
    private long sfxRemainingMs() {
        if (sfxPlayer == null) {
            return 0;
        }
        try {
            if (sfxPlayer.isLooping() || !sfxPlayer.isPlaying()) {
                return 0;
            }
            long remainingThisPlay = Math.max(0, sfxPlayer.getDuration() - sfxPlayer.getCurrentPosition());
            return remainingThisPlay + (long) sfxRepeatsRemaining * Math.max(0, sfxPlayer.getDuration());
        } catch (IllegalStateException e) {
            return 0;
        }
    }

    private static int parseIntOr(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Shared by tap-anywhere, the bottom "Next" button, and the volume keys. */
    private void advance() {
        if (finished) {
            NoAnimTransition.finish(this);
            return;
        }
        recordActivity(); // a tap, or an auto-advance tick (autoAdvanceTick() calls this too)
        if (engine.getState() == VnEngine.State.WAITING_TAP) {
            if (!completeTypewriterIfActive()) {
                engine.resumeFromTap();
            }
        } else if (engine.getState() == VnEngine.State.WAITING_DELAY) {
            skipPendingDelay();
        }
        scheduleAutoAdvance();
    }

    /** If the current line is still being typewriter-animated, a tap should just reveal the rest
     * of it instantly rather than also advancing past it -- matches how most VN readers treat a
     * tap during text reveal, and stops fast readers from losing lines they tapped through before
     * they'd fully appeared. Returns true if it consumed the tap this way. */
    private boolean completeTypewriterIfActive() {
        if (!typingInProgress) {
            return false;
        }
        typeHandler.removeCallbacks(typewriterRunnable);
        typingInProgress = false;
        bodyText.setText(typewriterFullText);
        return true;
    }

    /** Lets a tap skip straight through a scripted "delay" (or a sound-effect wait held via
     * {@link #onTextClear}) instead of forcing the player to sit through it -- e.g. Planetarian's
     * intro is almost entirely delay/sfx pacing with few WAITING_TAP lines, so without this a tap
     * would do nothing for most of it. */
    private void skipPendingDelay() {
        if (pendingDelayRunnable == null) {
            return; // nothing currently counting down (e.g. an overlay has it in reserve)
        }
        typeHandler.removeCallbacks(pendingDelayRunnable);
        Runnable action = pendingDelayAction;
        pendingDelayRunnable = null;
        pendingDelayAction = null;
        delayDeadlineElapsed = -1;
        if (action != null) {
            action.run();
        }
    }

    /** "Advance to next choice": runs the engine forward without pausing for real taps or
     * scripted delays, and without playing sound effects or intermediate music cues, until it
     * reaches the next {@code choice} (or the story ends) -- only the last "music" cue seen
     * during the run, if any, actually plays once it stops (see {@link #onSound}/{@link #onMusic}
     * and {@link #showImage}/{@link #updateBodyText}, which also force instant rendering for the
     * duration regardless of e-ink mode). */
    private void advanceToNextChoice() {
        if (engine == null || engine.getState() != VnEngine.State.WAITING_TAP) {
            Toast.makeText(this, R.string.advance_to_choice_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        if (sfxPlayer != null) {
            sfxPlayer.setOnCompletionListener(null);
            sfxPlayer.release();
            sfxPlayer = null;
        }
        boolean previousDelaysEnabled = computeDelaysEnabled();
        turboSkipping = true;
        turboMusicChanged = false;
        turboMusicFile = null;
        engine.setDelaysEnabled(false);
        try {
            // Each resumeFromTap() advances only to the next blocking point; looping here is what
            // pushes past every WAITING_TAP line without a real tap. Bounded by the script itself:
            // this can only end at WAITING_CHOICE or FINISHED, since delays are forced off above
            // and sound (the only other source of a host-driven wait, via onTextClear) is silenced.
            while (engine.getState() == VnEngine.State.WAITING_TAP) {
                engine.resumeFromTap();
            }
        } finally {
            turboSkipping = false;
            engine.setDelaysEnabled(previousDelaysEnabled);
        }
        if (turboMusicChanged) {
            playMusic(turboMusicFile);
        }
        scheduleAutoAdvance();
    }

    /** Volume keys (unless the user has freed them up for normal system-volume behavior via
     * Settings' "Volume buttons turn pages" toggle), plus a paired gamepad's shoulder/select
     * buttons (e.g. BTN_TL2, BTN_SELECT on an 8BitDo controller) wired to "next" for one-handed
     * page turning. */
    private boolean isAdvanceKey(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            return Prefs.isVolumeButtonsPageTurn(this);
        }
        return keyCode == KeyEvent.KEYCODE_BUTTON_L2 || keyCode == KeyEvent.KEYCODE_BUTTON_SELECT;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (isAdvanceKey(keyCode)) {
            advance();
            return true; // consume: don't also change system volume
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (isAdvanceKey(keyCode)) {
            return true; // consume here too, so it doesn't fall through to some other default action
        }
        return super.onKeyUp(keyCode, event);
    }

    private void applyPrefs() {
        einkMode = Prefs.isEinkMode(this);
        muteAudio = Prefs.isMuteAudio(this);
        if (Prefs.isKeepScreenOn(this)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        float sizeSp = Prefs.getTextSizeSp(this);
        bodyText.setTextSize(sizeSp);
        speakerName.setTextSize(sizeSp * 0.9f);
        Typeface font = activeFont();
        bodyText.setTypeface(font);
        speakerName.setTypeface(font, Typeface.BOLD); // speakerName is always bold, unlike bodyText
        applyEinkFilter(backgroundImage);
        for (SpriteInstance s : sprites) {
            applyEinkFilter(s.view);
        }
        recomputeBodyBudget(); // speakerName's height changes with text size
        if (engine != null) {
            engine.setDelaysEnabled(computeDelaysEnabled());
        }
        // Live-apply volume to whatever's already playing, instead of only affecting the next
        // track/cue -- same "takes effect on the very next Settings change" intent as text size/font.
        if (musicPlayer != null) {
            float musicVol = Prefs.getMusicVolumePercent(this) / 100f;
            musicPlayer.setVolume(musicVol, musicVol);
        }
        if (sfxPlayer != null) {
            float sfxVol = Prefs.getSfxVolumePercent(this) / 100f;
            sfxPlayer.setVolume(sfxVol, sfxVol);
        }
    }

    /** The typeface actually in effect right now: the VN's own font if it has one and the
     * setting is on, null (system default) otherwise. */
    private Typeface activeFont() {
        return (novelFont != null && Prefs.isUseNovelFont(this)) ? novelFont : null;
    }

    /** Looks for the VN's own "default.ttf" at its root (a real, common VNDS convention -- real
     * sample packs commonly ship one); null if it doesn't have one or fails to load. */
    private Typeface loadNovelFont() {
        File fontFile = new File(vnDir, "default.ttf");
        if (!fontFile.exists()) {
            return null;
        }
        try {
            return Typeface.createFromFile(fontFile);
        } catch (RuntimeException e) {
            return null; // malformed/corrupt font file: fall back to the system font
        }
    }

    /** Forces a full-screen (GC16) flash, clearing any ghosting left by whatever was on screen
     * before -- used for opening the reader and returning to it from multitasking (see
     * {@link #onResume}). Deferred with {@code post} so it fires after the pending layout/draw
     * pass, not on a stale frame. */
    private void maybeFullEinkRefresh() {
        if (einkMode && einkRefreshSupported) {
            sceneContainer.post(EinkRefreshManager::fullRefresh);
        }
    }

    private void applyEinkFilter(ImageView view) {
        if (einkMode) {
            ColorMatrix cm = new ColorMatrix();
            cm.setSaturation(0f); // e-ink screens render grayscale anyway; flatten color noise
            view.setColorFilter(new ColorMatrixColorFilter(cm));
        } else {
            view.clearColorFilter();
        }
    }

    // ---- VnEngine.Listener ----------------------------------------------------------

    @Override
    public void onSpeaker(String name) {
        runOnUiThread(() -> {
            lastSpeaker = name;
            addBodyLine(name, true);
        });
    }

    @Override
    public void onTextLine(String line) {
        runOnUiThread(() -> {
            lastAutoAdvanceText = line;
            addBodyLine(line, false);
        });
    }

    @Override
    public void onTextAppend(String moreText) {
        runOnUiThread(() -> {
            lastAutoAdvanceText = moreText;
            appendToLastBodyLine(moreText);
        });
    }

    @Override
    public void onTextClear() {
        runOnUiThread(() -> {
            // A caption paired with a voice line ("text @line" / "sound x.aac 1" / "text ~") is
            // meant to stay up for as long as that line is playing -- otherwise the clear (which
            // runs synchronously right after the sound is fired) would wipe it before anyone can
            // read or hear it. Hold the clear -- and the engine -- until playback finishes.
            long sfxRemaining = sfxRemainingMs();
            if (sfxRemaining > 0) {
                engine.pauseForHostTiming();
                scheduleTimedResume(sfxRemaining, () -> {
                    performTextClear();
                    engine.resumeFromDelay();
                });
                return;
            }
            performTextClear();
        });
    }

    private void performTextClear() {
        bodyLines.clear();
        lastSpeaker = ""; // a page turn ends this speaker's "turn"; don't let it leak into later text
        typeHandler.removeCallbacksAndMessages(null);
        typingInProgress = false;
        bodyText.setText("");
        speakerName.setText("");
    }

    /** Appends one line (dialogue, or a bold "@Name" speaker line) to the current page. */
    private void addBodyLine(String text, boolean bold) {
        textLog.add(new SaveManager.SavedLine(text, bold));

        List<BodyLine> candidate = new ArrayList<>(bodyLines);
        candidate.add(new BodyLine(text, bold));
        CharSequence candidateText = buildBodySpannable(candidate);
        int prefixLength = bodyLines.isEmpty() ? 0 : buildBodySpannable(bodyLines).length();
        if (!bodyLines.isEmpty() && wouldOverflow(candidateText)) {
            // The box is full: start a fresh page instead of scrolling.
            candidate = new ArrayList<>();
            candidate.add(new BodyLine(text, bold));
            candidateText = buildBodySpannable(candidate);
            prefixLength = 0;
        }
        bodyLines.clear();
        bodyLines.addAll(candidate);
        updateBodyText(candidateText, prefixLength);
    }

    /** Extends the most recently shown line with {@code moreText} instead of starting a new one --
     * NScripter's mid-line "@" pause (see {@code NsDialogue}); the resumed text is the rest of the
     * very same original line, so it must read as one continuous line, not a line break. */
    private void appendToLastBodyLine(String moreText) {
        if (bodyLines.isEmpty()) {
            // No line open yet (e.g. right after a page clear) -- nothing to extend.
            addBodyLine(moreText, false);
            return;
        }
        int lastIndex = bodyLines.size() - 1;
        BodyLine merged = new BodyLine(bodyLines.get(lastIndex).text + moreText, bodyLines.get(lastIndex).bold);

        List<BodyLine> candidate = new ArrayList<>(bodyLines.subList(0, lastIndex));
        candidate.add(merged);
        CharSequence candidateText = buildBodySpannable(candidate);
        int prefixLength = buildBodySpannable(bodyLines).length(); // already shown, before this append
        if (wouldOverflow(candidateText)) {
            // No room left on this page for the continuation: start a fresh page instead, same
            // fallback addBodyLine already uses.
            candidate = new ArrayList<>();
            candidate.add(merged);
            candidateText = buildBodySpannable(candidate);
            prefixLength = 0;
        }
        bodyLines.clear();
        bodyLines.addAll(candidate);

        if (!textLog.isEmpty()) {
            int lastLogIndex = textLog.size() - 1;
            SaveManager.SavedLine lastLog = textLog.get(lastLogIndex);
            textLog.set(lastLogIndex, new SaveManager.SavedLine(lastLog.text + moreText, lastLog.bold));
        }
        updateBodyText(candidateText, prefixLength);
    }

    private static CharSequence buildBodySpannable(List<BodyLine> lines) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            BodyLine line = lines.get(i);
            int start = sb.length();
            sb.append(line.text);
            if (line.bold) {
                sb.setSpan(new StyleSpan(Typeface.BOLD), start, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            if (i < lines.size() - 1) {
                sb.append("\n");
            }
        }
        return sb;
    }

    private static final class BodyLine {
        final String text;
        final boolean bold;

        BodyLine(String text, boolean bold) {
            this.text = text;
            this.bold = bold;
        }
    }

    private static final class SpriteInstance {
        final int x;
        final int y;
        final String path;
        final ImageView view;
        final VnEngine.SpriteTransparency transparency;
        /** See {@link VnEngine.Listener#onSprite}'s doc; only meaningful when {@code transparency}
         * is {@code ALPHA_MASK}. */
        final int alphaMaskCells;

        SpriteInstance(int x, int y, String path, ImageView view, VnEngine.SpriteTransparency transparency,
                       int alphaMaskCells) {
            this.x = x;
            this.y = y;
            this.path = path;
            this.view = view;
            this.transparency = transparency;
            this.alphaMaskCells = alphaMaskCells;
        }
    }

    @Override
    public void onBackground(File imageFile, int fadeFrames, VnEngine.SpriteTransparency transparency, int alphaMaskCells) {
        runOnUiThread(() -> {
            // bgload is the only thing that clears foreground sprites in VNDS (even when reloading
            // the same background path, which some real scripts use purely to trigger this reset --
            // e.g. reloading the same portrait background before every portrait change). NScripter's
            // "bg" clears only its left/center/right character-portrait layers, and does so
            // explicitly via onSpriteCleared -- see
            // NsCommandDispatcher's "bg" handler -- rather than this callback clearing anything itself.
            if (!nsEngineActive) {
                clearSprites();
            }
            String path = imageFile != null ? imageFile.getAbsolutePath() : null;
            if (Objects.equals(path, currentBgPath) && transparency == currentBgTransparency
                    && alphaMaskCells == currentBgAlphaCells) {
                return; // already showing this image: skip only the redundant bitmap redraw
            }
            currentBgPath = path;
            currentBgTransparency = transparency;
            currentBgAlphaCells = alphaMaskCells;
            long fadeMs = Math.round(fadeFrames * 1000.0 / 60.0);
            showImage(backgroundImage, path != null ? loadBitmap(path, transparency, alphaMaskCells) : null, fadeMs);
        });
    }

    private void clearSprites() {
        spriteLayer.removeAllViews();
        sprites.clear();
    }

    @Override
    public void onSprite(int layer, int x, int y, File imageFile, VnEngine.SpriteTransparency transparency, int alphaMaskCells) {
        runOnUiThread(() -> {
            String path = imageFile.getAbsolutePath();
            Bitmap bmp = loadBitmap(path, transparency, alphaMaskCells);
            ImageView iv = newSpriteView();
            positionSprite(iv, bmp, x, y);
            showImage(iv, bmp);
            if (layer < 0) {
                // VNDS's setimg always adds a new layer -- never replaces by position -- so that
                // e.g. a body+clothes+face+expression portrait stack (all at the same x,y) all
                // stay visible.
                spriteLayer.addView(iv);
                sprites.add(new SpriteInstance(x, y, path, iv, transparency, alphaMaskCells));
            } else {
                // NScripter's numbered layer: replaces whatever was already showing at this layer.
                SpriteInstance existing = nsSprites.remove(layer);
                if (existing != null) {
                    spriteLayer.removeView(existing.view);
                }
                spriteLayer.addView(iv);
                nsSprites.put(layer, new SpriteInstance(x, y, path, iv, transparency, alphaMaskCells));
            }
        });
    }

    @Override
    public void onSpriteCleared(int layer) {
        // VNDS never calls this -- its sprites are only ever cleared in bulk, via onBackground.
        runOnUiThread(() -> {
            if (layer < 0) {
                for (SpriteInstance s : nsSprites.values()) {
                    spriteLayer.removeView(s.view);
                }
                nsSprites.clear();
            } else {
                SpriteInstance existing = nsSprites.remove(layer);
                if (existing != null) {
                    spriteLayer.removeView(existing.view);
                }
            }
        });
    }

    private ImageView newSpriteView() {
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.FIT_XY);
        applyEinkFilter(iv);
        return iv;
    }

    /** Places a sprite at its script-declared pixel position, scaled to the actual screen size --
     * or, for an {@code AUTO_POSITION_*} sentinel (NScripter's "ld" left/center/right stand
     * positions; see {@code VnEngine.Listener}'s doc), resolves it against the decoded image's own
     * size and the scene's virtual canvas size first. */
    private void positionSprite(ImageView iv, Bitmap bmp, int x, int y) {
        int bmpWidth = bmp != null ? bmp.getWidth() : 0;
        int bmpHeight = bmp != null ? bmp.getHeight() : 0;
        int resolvedX = resolveAutoPositionX(x, bmpWidth);
        int resolvedY = resolveAutoPositionY(y, bmpHeight);
        int w = bmp != null ? Math.round(bmpWidth * sceneScale) : ViewGroup.LayoutParams.WRAP_CONTENT;
        int h = bmp != null ? Math.round(bmpHeight * sceneScale) : ViewGroup.LayoutParams.WRAP_CONTENT;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.leftMargin = Math.round(resolvedX * sceneScale);
        lp.topMargin = Math.round(resolvedY * sceneScale);
        iv.setLayoutParams(lp);
    }

    private int resolveAutoPositionX(int x, int bmpWidth) {
        if (x == VnEngine.Listener.AUTO_POSITION_LEFT) {
            return 0;
        }
        if (x == VnEngine.Listener.AUTO_POSITION_CENTER) {
            return (sceneVirtualWidth - bmpWidth) / 2;
        }
        if (x == VnEngine.Listener.AUTO_POSITION_RIGHT) {
            return sceneVirtualWidth - bmpWidth;
        }
        return x;
    }

    private int resolveAutoPositionY(int y, int bmpHeight) {
        if (y == VnEngine.Listener.AUTO_POSITION_BOTTOM) {
            return sceneVirtualHeight - bmpHeight;
        }
        return y;
    }

    @Override
    public void onSound(File soundFile, int times) {
        runOnUiThread(() -> {
            // Sound effects are never carried through a turbo-skip, not even the very last one
            // right before the choice -- unlike music, there's no "final" one worth playing.
            if (turboSkipping) {
                return;
            }
            playOneShot(soundFile, times);
        });
    }

    @Override
    public void onMusic(File musicFileOrNull) {
        runOnUiThread(() -> {
            if (turboSkipping) {
                // Deferred: only the last music cue seen during the whole skip actually plays,
                // once advanceToNextChoice() lands on a choice (or the story ends).
                turboMusicChanged = true;
                turboMusicFile = musicFileOrNull;
                return;
            }
            playMusic(musicFileOrNull);
        });
    }

    @Override
    public void onChoices(List<String> options) {
        runOnUiThread(() -> showChoices(options, null));
    }

    @Override
    public void onChoices(List<String> options, List<File> images) {
        runOnUiThread(() -> showChoices(options, images));
    }

    @Override
    public void onDelay(int frames) {
        long ms = Math.round(frames * 1000.0 / 60.0);
        // A scripted delay is often paired with a "sound" line right before it, timed for the
        // effect to finish -- but the frame count and the actual audio file's length don't always
        // agree, so never resume sooner than a currently-playing one-shot sound effect would end.
        ms = Math.max(ms, sfxRemainingMs());
        scheduleTimedResume(ms, engine::resumeFromDelay);
    }

    @Override
    public void onGlobalsChanged(Map<String, String> globals) {
        SaveManager.saveGlobals(this, vnKey(), globals);
    }

    @Override
    public void onFinished() {
        runOnUiThread(() -> {
            // Cancel first: a tick already posted before the script ended must not fire once
            // finished is true (advance() would just re-finish() the activity out from under "The End").
            pauseAutoAdvance();
            finished = true;
            SaveManager.clearResume(this, vnKey()); // resuming a finished story doesn't mean anything
            autoButton.setVisibility(View.GONE);
            textLogButton.setVisibility(View.GONE);
            advanceToChoiceButton.setVisibility(View.GONE); // nothing left to advance to
            speakerName.setText("");
            bodyLines.clear();
            bodyLines.add(new BodyLine(getString(R.string.the_end), false));
            bodyLines.add(new BodyLine(getString(R.string.tap_to_return), false));
            updateBodyText(buildBodySpannable(bodyLines), 0);
            advanceButton.setText(R.string.return_button);
        });
    }

    @Override
    public void onExitToLibrary() {
        runOnUiThread(() -> {
            pauseAutoAdvance();
            SaveManager.clearResume(this, vnKey()); // an explicit script-driven exit, not resumable
            NoAnimTransition.finish(this);
        });
    }

    @Override
    public void onLoadMenuRequested() {
        // Whenever the Load dialog this opens ends without a slot being picked (no save data at
        // all, or the player cancels one that did open), fall back to redisplaying whichever choice
        // menu (e.g. a title screen's New Game/Load/Quit) led into "systemcall load", instead of
        // leaving the engine paused with nothing on screen -- see VnEngine.reshowLastChoiceMenu's
        // doc. If there's no such menu to restore either, this is a no-op, same as before this
        // fallback existed.
        runOnUiThread(() -> openLoadDialog(engine::reshowLastChoiceMenu));
    }

    // ---- UI helpers -----------------------------------------------------------------------

    /** Fade duration for anything without its own scripted length (sprites -- setimg has no
     * fadetime argument in the VNDS format -- and visual state restored from a save/load, which
     * has no fadetime to restore either). bgload's own fadetime, when present, overrides this;
     * see the {@link #showImage(ImageView, Bitmap, long)} overload. */
    private static final long DEFAULT_FADE_MS = 120;

    private void showImage(ImageView view, Bitmap bitmap) {
        showImage(view, bitmap, DEFAULT_FADE_MS);
    }

    private void showImage(ImageView view, Bitmap bitmap, long fadeMs) {
        // Also instant (no fade) mid turbo-skip regardless of e-ink mode -- a cascade of fade
        // animations across dozens of skipped backgrounds/sprites would be chaotic, not instant.
        if (einkMode || turboSkipping) {
            view.animate().cancel();
            view.setAlpha(1f);
            view.setImageBitmap(bitmap);
            return;
        }
        view.animate().cancel();
        view.animate().alpha(0f).setDuration(fadeMs).withEndAction(() -> {
            view.setImageBitmap(bitmap);
            view.animate().alpha(1f).setDuration(fadeMs).start();
        }).start();
    }

    /**
     * Shows the full accumulated body text. In non-e-ink mode, only the newly appended suffix
     * (from prefixLength onward) is typewriter-animated -- everything before that was already
     * fully shown on a previous call and re-animating it too on every tap would replay the whole
     * page instead of just the new line.
     */
    private void updateBodyText(CharSequence full, int prefixLength) {
        typeHandler.removeCallbacksAndMessages(null);
        typewriterFullText = full;
        if (einkMode || turboSkipping || Prefs.getTextSpeedCps(this) == Prefs.TEXT_SPEED_INSTANT) {
            typingInProgress = false;
            bodyText.setText(full); // instantly show text, no typewriter reveal
            return;
        }
        int start = Math.max(0, Math.min(prefixLength, full.length()));
        bodyText.setText(full.subSequence(0, start));
        typingInProgress = start < full.length();
        // One character per tick, at a per-tick delay derived from the user's characters-per-
        // second setting -- read fresh on every call (rather than cached) so a mid-story Settings
        // change takes effect on the very next line instead of needing a restart.
        long tickDelayMs = Math.max(1, Math.round(1000.0 / Prefs.getTextSpeedCps(this)));
        final int[] pos = {start};
        typewriterRunnable = new Runnable() {
            @Override
            public void run() {
                if (pos[0] <= full.length()) {
                    bodyText.setText(full.subSequence(0, pos[0]));
                    if (pos[0] == full.length()) {
                        typingInProgress = false;
                    }
                    pos[0] += 1;
                    typeHandler.postDelayed(this, tickDelayMs);
                }
            }
        };
        typeHandler.post(typewriterRunnable);
    }

    /** Captures how many pixels bodyText has to work with before it would spill past textPanel. */
    private void recomputeBodyBudget() {
        textPanel.post(() -> {
            int budget = textPanel.getHeight() - bodyText.getTop() - textPanel.getPaddingBottom();
            if (budget > 0) {
                bodyBudgetPx = budget;
            }
        });
    }

    /** Measures (without touching the TextView) whether the given full text would overflow. */
    private boolean wouldOverflow(CharSequence text) {
        int width = bodyText.getWidth();
        if (bodyBudgetPx <= 0 || width <= 0 || text.length() == 0) {
            return false;
        }
        TextPaint paint = bodyText.getPaint();
        StaticLayout layout = StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                .setLineSpacing(bodyText.getLineSpacingExtra(), bodyText.getLineSpacingMultiplier())
                .build();
        return layout.getHeight() > bodyBudgetPx;
    }

    /** @param images parallel to {@code options}, an NScripter "spbtn"/"exbtn" button-sprite's own
     *                image file per option (see {@link VnEngine.Listener#onChoices(List, List)}),
     *                {@code null} (the whole list, or an individual entry) when there's no image to
     *                show -- always the case for a VNDS choice. An option with an image gets a
     *                split layout (image on the left half, text on the right half) instead of the
     *                plain text button every other option still gets. */
    private void showChoices(List<String> options, List<File> images) {
        currentChoiceOptions = new ArrayList<>(options);
        choicesPanel.removeAllViews();
        for (int i = 0; i < options.size(); i++) {
            final int index = i;
            File imageFile = images != null && index < images.size() ? images.get(index) : null;
            View button = imageFile != null && imageFile.isFile()
                    ? buildImageChoiceButton(options.get(i), imageFile)
                    : buildTextChoiceButton(options.get(i));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(8);
            button.setLayoutParams(lp);
            button.setOnClickListener(v -> {
                recordActivity();
                choicesPanel.setVisibility(View.GONE);
                currentChoiceOptions = null;
                engine.choose(index);
                scheduleAutoAdvance();
            });
            choicesPanel.addView(button);
        }
        // advanceBar itself stays visible (not hidden like it used to be) -- only its buttons are
        // gated off, via choicesShowing(), so Guide can still be used while deciding.
        choicesPanel.setVisibility(View.VISIBLE);
    }

    private Button buildTextChoiceButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(ContextCompat.getColorStateList(this, R.color.choice_button_text));
        button.setBackground(ContextCompat.getDrawable(this, R.drawable.bg_choice_button));
        button.setStateListAnimator(null); // no press elevation animation
        button.setElevation(0f);
        return button;
    }

    /** A choice button for an NScripter image-sprite button, split evenly down the middle: the
     * button's own image on the left half, its text label on the right half -- rather than the
     * plain centered text {@link #buildTextChoiceButton} renders for every other choice. */
    private View buildImageChoiceButton(String text, File imageFile) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(ContextCompat.getDrawable(this, R.drawable.bg_choice_button));
        row.setStateListAnimator(null); // no press elevation animation
        row.setElevation(0f);
        row.setClickable(true);
        row.setFocusable(true);

        ImageView imageView = new ImageView(this);
        imageView.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        imageView.setAdjustViewBounds(true);
        imageView.setMaxHeight(dp(72));
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setImageBitmap(loadBitmap(imageFile.getAbsolutePath()));

        TextView textView = new TextView(this);
        LinearLayout.LayoutParams textLp =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textLp.leftMargin = dp(12);
        textView.setLayoutParams(textLp);
        textView.setText(text);
        textView.setTextColor(ContextCompat.getColorStateList(this, R.color.choice_button_text));
        textView.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);

        row.addView(imageView);
        row.addView(textView);
        return row;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private Bitmap loadBitmap(String path) {
        return loadBitmap(path, VnEngine.SpriteTransparency.OPAQUE, 1);
    }

    /** @param transparency see {@link VnEngine.SpriteTransparency}. */
    private Bitmap loadBitmap(String path, VnEngine.SpriteTransparency transparency) {
        return loadBitmap(path, transparency, 1);
    }

    /** @param transparency see {@link VnEngine.SpriteTransparency}.
     * @param alphaMaskCells see {@link VnEngine.Listener#onSprite}'s doc; only meaningful when
     *                       {@code transparency} is {@code ALPHA_MASK}. */
    private Bitmap loadBitmap(String path, VnEngine.SpriteTransparency transparency, int alphaMaskCells) {
        String cacheKey = transparency == VnEngine.SpriteTransparency.OPAQUE ? path
                : path + "#" + transparency + "#" + alphaMaskCells;
        Bitmap cached = bitmapCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        Bitmap bmp = BitmapFactory.decodeFile(path);
        if (bmp != null) {
            switch (transparency) {
                case ALPHA_MASK:
                    bmp = compositeSideBySideAlphaMask(bmp, alphaMaskCells);
                    break;
                case TOPLEFT_KEY:
                    bmp = compositeTopLeftColorKey(bmp);
                    break;
                case OPAQUE:
                default:
                    break;
            }
        }
        if (bmp != null) {
            bitmapCache.put(cacheKey, bmp);
        }
        return bmp;
    }

    /** NScripter's ":a;" file-load tag (see {@code NsCommandDispatcher}) doubles the image's
     * width: the left half of each "cell" is the real art, the right half a grayscale alpha mask
     * (white = transparent, black = opaque) -- matches real ONScripter behavior, which XORs the
     * mask byte with 0xff to get alpha (equivalent
     * to 255 - value for any 8-bit channel). Composites art+mask into one real ARGB bitmap.
     *
     * <p>{@code cellCount} (from an extended ":a/N,x,y;" tag, see {@code
     * NsCommandDispatcher#alphaMaskCellsFor}) splits the image into that many equal-width
     * left-to-right "cells" FIRST, each cell independently an [art|mask] pair -- matching
     * real ONScripter's own cell-splitting math, confirmed against a real game's
     * title-screen text ("lsp 0,\":a/2,0,3;May/System/Title_Text.jpg\",0,0",
     * decoded 1280 wide: genuinely 2 side-by-side 320+320 [art|mask] cells, byte-identical to each
     * other, NOT one plain 640+640 pair spanning the whole image -- the latter interpretation
     * corrupts the alpha across roughly half the image, since the "mask" it'd read is actually the
     * SECOND cell's own art). {@code cellCount == 1} (a plain ":a;" tag, no slash) degenerates to
     * the previous simple whole-image split. This host has no sprite-sheet animation of its own, so
     * only cell 0 (the first) is ever composited/shown -- real ONScripter would pick a
     * script/interaction-driven {@code current_cell} instead. */
    private Bitmap compositeSideBySideAlphaMask(Bitmap doubleWide, int cellCount) {
        if (doubleWide.hasAlpha()) {
            return doubleWide;
        }
        if (cellCount < 1) {
            cellCount = 1;
        }
        int cellWidth = doubleWide.getWidth() / cellCount; // one cell = its own [art|mask] pair
        int halfWidth = cellWidth / 2; // cell 0's art width == its mask width
        int height = doubleWide.getHeight();
        if (halfWidth <= 0 || height <= 0) {
            return doubleWide;
        }
        Bitmap result = Bitmap.createBitmap(halfWidth, height, Bitmap.Config.ARGB_8888);
        int[] colorRow = new int[halfWidth];
        int[] maskRow = new int[halfWidth];
        for (int y = 0; y < height; y++) {
            doubleWide.getPixels(colorRow, 0, halfWidth, 0, y, halfWidth, 1);
            doubleWide.getPixels(maskRow, 0, halfWidth, halfWidth, y, halfWidth, 1);
            for (int x = 0; x < halfWidth; x++) {
                int alpha = 255 - (maskRow[x] & 0xff); // mask is grayscale: any channel works
                colorRow[x] = (colorRow[x] & 0x00FFFFFF) | (alpha << 24);
            }
            result.setPixels(colorRow, 0, halfWidth, 0, y, halfWidth, 1);
        }
        return result;
    }

    /** NScripter's default (untagged) sprite transparency, and its explicit ":l;" tag -- matches
     * real ONScripter's top-left color-key transparency behavior: the
     * image's own top-left corner pixel's color is the "transparent" color-key, applied everywhere
     * that exact color appears (a hard cutout, no partial/antialiased edges). */
    private Bitmap compositeTopLeftColorKey(Bitmap opaque) {
        int width = opaque.getWidth();
        int height = opaque.getHeight();
        if (width <= 0 || height <= 0) {
            return opaque;
        }
        int keyColor = opaque.getPixel(0, 0) & 0x00FFFFFF;
        Bitmap result = opaque.copy(Bitmap.Config.ARGB_8888, true);
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            result.getPixels(row, 0, width, 0, y, width, 1);
            for (int x = 0; x < width; x++) {
                if ((row[x] & 0x00FFFFFF) == keyColor) {
                    row[x] = 0; // fully transparent
                }
            }
            result.setPixels(row, 0, width, 0, y, width, 1);
        }
        return result;
    }

    /** "sound file times": times &lt; 0 loops forever, otherwise plays that many times then stops. */
    private void playOneShot(File file, int times) {
        if (sfxPlayer != null) {
            sfxPlayer.setOnCompletionListener(null);
            sfxPlayer.release();
            sfxPlayer = null;
        }
        if (file == null) {
            return; // "~": stop whatever was playing, don't start anything new
        }
        if (muteAudio || !file.exists()) {
            return;
        }
        try {
            sfxPlayer = new MediaPlayer();
            sfxPlayer.setDataSource(file.getAbsolutePath());
            if (times < 0) {
                sfxPlayer.setLooping(true);
            } else {
                sfxRepeatsRemaining = Math.max(0, times - 1);
                sfxPlayer.setOnCompletionListener(mp -> {
                    if (sfxRepeatsRemaining > 0) {
                        sfxRepeatsRemaining--;
                        mp.seekTo(0);
                        mp.start();
                    } else {
                        mp.release();
                        if (sfxPlayer == mp) {
                            sfxPlayer = null;
                        }
                    }
                });
            }
            sfxPlayer.prepare();
            float sfxVol = Prefs.getSfxVolumePercent(this) / 100f;
            sfxPlayer.setVolume(sfxVol, sfxVol);
            sfxPlayer.start();
        } catch (IOException | RuntimeException e) {
            sfxPlayer = null;
            Toast.makeText(this, getString(R.string.sound_play_failed, file.getName()), Toast.LENGTH_SHORT).show();
        }
    }

    private void playMusic(File file) {
        if (musicPlayer != null) {
            musicPlayer.release();
            musicPlayer = null;
        }
        // Tracked regardless of mute/existence below, so a save always captures what SHOULD be
        // playing (e.g. muted mid-track) -- loading it back later re-evaluates mute/existence
        // against whatever the state is at that point, not what it was at save time.
        currentMusicPath = file != null ? file.getAbsolutePath() : null;
        if (muteAudio || file == null || !file.exists()) {
            return;
        }
        try {
            musicPlayer = new MediaPlayer();
            musicPlayer.setDataSource(file.getAbsolutePath());
            musicPlayer.setLooping(true);
            musicPlayer.prepare();
            float musicVol = Prefs.getMusicVolumePercent(this) / 100f;
            musicPlayer.setVolume(musicVol, musicVol);
            musicPlayer.start();
        } catch (IOException | RuntimeException e) {
            musicPlayer = null;
            Toast.makeText(this, getString(R.string.sound_play_failed, file.getName()), Toast.LENGTH_SHORT).show();
        }
    }

    // ---- Save / load ----------------------------------------------------------------------

    private String vnKey() {
        return vnDir.getName();
    }

    /** Applies an edit made in the Variables screen straight to the live engine state -- a global
     * (gsetvar) edit is persisted immediately via {@link #onGlobalsChanged} (same as a script's own
     * gsetvar would trigger); a local (setvar) edit just updates the in-memory value, picked up by
     * whatever save/resume snapshot happens next, same as any other setvar. */
    private void onVarChanged(boolean global, String name, String value) {
        if (global) {
            engine.setGlobal(name, value);
        } else {
            engine.setVariable(name, value);
        }
    }

    private List<SaveManager.SlotInfo> listSlots() {
        return nsEngineActive ? NsSaveManager.listSlots(this, vnKey()) : SaveManager.listSlots(this, vnKey());
    }

    private void openSaveDialog() {
        if (!canResumeNow()) {
            Toast.makeText(this, R.string.save_unavailable, Toast.LENGTH_SHORT).show();
            // The menu that led here already froze Auto-advance/media/pending delays via
            // openOverlay(); since no dialog is opening after all, nothing else will ever
            // reverse that -- without this the story would just stay paused forever.
            closeOverlay();
            return;
        }
        openOverlay();
        SaveSlotDialog.show(this, true, listSlots(), this::saveToSlot, this::closeOverlay);
    }

    private void openLoadDialog() {
        // Same reasoning as openSaveDialog(): the menu already froze things via openOverlay(),
        // and no dialog is opening after all to eventually undo that.
        openLoadDialog(this::closeOverlay);
    }

    /** @param onCancelOrNothingToLoad run when there's no save data at all instead of opening the
     *                                 dialog, AND (in addition to this method's own closeOverlay())
     *                                 when the player cancels back out of a dialog that did open --
     *                                 the hamburger menu's own Load row (see {@link
     *                                 #openLoadDialog()}) just needs to undo the freeze opening the
     *                                 menu caused, so its own closeOverlay() already covers both
     *                                 cases; but "systemcall load" fired from inside a choice menu's
     *                                 target (see {@link #onLoadMenuRequested}) never froze anything
     *                                 of its own and needs that choice menu restored instead, in
     *                                 either case -- not just the no-save-data one. */
    private void openLoadDialog(Runnable onCancelOrNothingToLoad) {
        List<SaveManager.SlotInfo> slots = listSlots();
        boolean anySaved = false;
        for (SaveManager.SlotInfo slot : slots) {
            if (slot.occupied) {
                anySaved = true;
                break;
            }
        }
        if (!anySaved) {
            Toast.makeText(this, R.string.load_missing, Toast.LENGTH_SHORT).show();
            onCancelOrNothingToLoad.run(); // openOverlay() never ran below: nothing to unwind
            return;
        }
        openOverlay();
        SaveSlotDialog.show(this, false, slots, this::loadFromSlot, () -> {
            closeOverlay(); // always reverse this method's own openOverlay() above
            onCancelOrNothingToLoad.run(); // then whatever extra the caller needs on cancel
        });
    }

    /** Whether the engine's current state is consistent enough to actually capture a save/resume
     * snapshot right now -- same requirement {@link #openSaveDialog} already enforces for a manual
     * save. Used both to gate the auto-resume snapshot in {@link #onPause} and to warn before
     * leaving the reader (Library/Quit) at a moment that wouldn't be resumable. WAITING_CHOICE
     * counts as resumable too, same as WAITING_TAP: {@link #saveToSlot}/{@link #loadFromSlot}
     * persist and redisplay the choice menu itself, not just the position right before it. */
    private boolean canResumeNow() {
        if (finished || engine == null) {
            return false;
        }
        VnEngine.State state = engine.getState();
        return state == VnEngine.State.WAITING_TAP || state == VnEngine.State.WAITING_CHOICE;
    }

    private void saveToSlot(int slot) {
        List<SaveManager.SavedLine> saved = new ArrayList<>();
        for (BodyLine line : bodyLines) {
            saved.add(new SaveManager.SavedLine(line.text, line.bold));
        }
        boolean atChoice = engine.getState() == VnEngine.State.WAITING_CHOICE;
        if (nsEngineActive) {
            List<NsSaveManager.NsSpriteEntry> spriteEntries = new ArrayList<>();
            for (Map.Entry<Integer, SpriteInstance> e : nsSprites.entrySet()) {
                SpriteInstance s = e.getValue();
                spriteEntries.add(new NsSaveManager.NsSpriteEntry(e.getKey(), s.x, s.y, s.path, s.transparency,
                        s.alphaMaskCells));
            }
            NsSaveManager.save(this, vnKey(), slot, (NsScriptEngine) engine, currentBgPath, currentBgTransparency,
                    currentBgAlphaCells, currentMusicPath, spriteEntries, lastSpeaker, saved, atChoice);
            return;
        }
        List<SaveManager.SpriteEntry> spriteEntries = new ArrayList<>();
        for (SpriteInstance s : sprites) {
            spriteEntries.add(new SaveManager.SpriteEntry(s.x, s.y, s.path));
        }
        List<String> choiceOptions = atChoice && currentChoiceOptions != null
                ? currentChoiceOptions : new ArrayList<>();
        SaveManager.save(this, vnKey(), slot, (ScriptEngine) engine, currentBgPath, currentMusicPath, spriteEntries,
                lastSpeaker, saved, atChoice, choiceOptions);
    }

    private void loadFromSlot(int slot) {
        if (nsEngineActive) {
            loadNsSlot(slot);
            return;
        }
        SaveManager.SlotData data = SaveManager.load(this, vnKey(), slot);
        if (data == null) {
            Toast.makeText(this, R.string.load_failed, Toast.LENGTH_SHORT).show();
            return;
        }

        resetVisualStateForLoad();
        clearSprites();

        currentBgPath = data.backgroundPath.isEmpty() ? null : data.backgroundPath;
        currentBgTransparency = VnEngine.SpriteTransparency.OPAQUE; // VNDS never tags backgrounds
        currentBgAlphaCells = 1;
        showImage(backgroundImage, currentBgPath != null ? loadBitmap(currentBgPath) : null);
        playMusic(data.musicPath.isEmpty() ? null : new File(data.musicPath));

        for (SaveManager.SpriteEntry e : data.sprites) {
            Bitmap bmp = loadBitmap(e.path);
            ImageView iv = newSpriteView();
            positionSprite(iv, bmp, e.x, e.y);
            showImage(iv, bmp);
            spriteLayer.addView(iv);
            sprites.add(new SpriteInstance(e.x, e.y, e.path, iv, VnEngine.SpriteTransparency.OPAQUE, 1));
        }

        restoreBodyAndTextLog(data.lastSpeaker, data.bodyLines);
        if (data.atChoice) {
            ((ScriptEngine) engine).restoreStateAtChoice(data.file, data.pc, data.vars, data.choiceOptions);
        } else {
            engine.restoreState(data.file, data.pc, data.vars);
        }
        scheduleAutoAdvance();
    }

    private void loadNsSlot(int slot) {
        NsSaveManager.NsSlotData data = NsSaveManager.load(this, vnKey(), slot);
        if (data == null) {
            Toast.makeText(this, R.string.load_failed, Toast.LENGTH_SHORT).show();
            return;
        }

        resetVisualStateForLoad();
        for (SpriteInstance s : nsSprites.values()) {
            spriteLayer.removeView(s.view);
        }
        nsSprites.clear();

        currentBgPath = data.backgroundPath == null || data.backgroundPath.isEmpty() ? null : data.backgroundPath;
        currentBgTransparency = data.backgroundTransparency;
        currentBgAlphaCells = data.backgroundAlphaMaskCells;
        showImage(backgroundImage, currentBgPath != null
                ? loadBitmap(currentBgPath, currentBgTransparency, currentBgAlphaCells) : null);
        playMusic(data.musicPath == null || data.musicPath.isEmpty() ? null : new File(data.musicPath));

        for (NsSaveManager.NsSpriteEntry e : data.sprites) {
            Bitmap bmp = loadBitmap(e.path, e.transparency, e.alphaMaskCells);
            ImageView iv = newSpriteView();
            positionSprite(iv, bmp, e.x, e.y);
            showImage(iv, bmp);
            spriteLayer.addView(iv);
            nsSprites.put(e.layer, new SpriteInstance(e.x, e.y, e.path, iv, e.transparency, e.alphaMaskCells));
        }

        restoreBodyAndTextLog(data.lastSpeaker, data.bodyLines);
        NsScriptEngine nsEngine = (NsScriptEngine) engine;
        nsEngine.restoreFromSnapshot(data.engineState);
        if (data.atChoice) {
            nsEngine.reshowLastChoiceMenu();
        }
        scheduleAutoAdvance();
    }

    /** The engine-agnostic half of {@link #loadFromSlot}/{@link #loadNsSlot}: clears whatever
     * would otherwise conflict with the slot being restored, before either method repopulates it
     * from that slot's own format-specific data. */
    private void resetVisualStateForLoad() {
        typeHandler.removeCallbacksAndMessages(null);
        pendingDelayRunnable = null;
        delayDeadlineElapsed = -1;
        delayRemainingMsAtPause = -1;
        choicesPanel.setVisibility(View.GONE);
        currentChoiceOptions = null;
        autoButton.setVisibility(View.VISIBLE);
        textLogButton.setVisibility(View.VISIBLE);
        advanceToChoiceButton.setVisibility(View.VISIBLE);
        advanceButton.setText(R.string.advance_button);
        finished = false;
    }

    private void restoreBodyAndTextLog(String speaker, List<SaveManager.SavedLine> savedBodyLines) {
        lastSpeaker = speaker;
        bodyLines.clear();
        for (SaveManager.SavedLine line : savedBodyLines) {
            bodyLines.add(new BodyLine(line.text, line.bold));
        }
        speakerName.setText("");
        typeHandler.removeCallbacksAndMessages(null);
        typingInProgress = false;
        bodyText.setText(buildBodySpannable(bodyLines));

        // The full backlog isn't persisted, only the loaded page -- don't keep a pre-load
        // backlog around that may not correspond to this save at all.
        textLog.clear();
        textLog.addAll(savedBodyLines);
        lastAutoAdvanceText = savedBodyLines.isEmpty() ? "" : savedBodyLines.get(savedBodyLines.size() - 1).text;
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Leaving the foreground (Home, task switch, screen lock, ...) must freeze the story the
        // same way an overlay does -- otherwise Auto-advance keeps clicking through the script,
        // and music/sfx keep playing, entirely out of the user's sight.
        pauseForOverlay();
        // Keep the always-current "resume" snapshot up to date on every way of leaving the reader
        // (Home, task switch, back to library, Quit): onPause fires for all of them. Only when
        // the engine is actually WAITING_TAP is the visual/engine state consistent enough to save,
        // same requirement as a manual save -- otherwise this pause just leaves the previous
        // resume point in place rather than overwriting it with a mid-transition snapshot.
        if (canResumeNow()) {
            saveToSlot(SaveManager.SLOT_RESUME);
        }
        flushActiveTime();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Don't un-freeze behind a dialog/menu that's still showing -- its own close callback
        // (closeOverlay()) is what's responsible for that.
        if (!overlayShowing) {
            resumeForOverlay();
        }
        // Fresh baseline: resuming itself isn't "activity", it just anchors the first gap
        // measurement so time spent backgrounded is never counted.
        lastActivityElapsedMs = android.os.SystemClock.elapsedRealtime();
        // onResume covers both opening the reader (it always follows onCreate) and returning to
        // it from multitasking (Home, task switcher, screen lock) -- both are moments the actual
        // screen contents may not match what's freshly drawn underneath, so a full flash clears it.
        maybeFullEinkRefresh();
    }

    /** Marks "the story just progressed" -- called on a tap, an auto-advance tick, a choice, or a
     * delay/sfx-wait naturally elapsing. Credits the gap since the last such event toward this
     * session's active reading time, unless that gap was long enough to count as idle instead. */
    private void recordActivity() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (lastActivityElapsedMs >= 0) {
            long gap = now - lastActivityElapsedMs;
            if (gap < IDLE_THRESHOLD_MS) {
                activeMsThisSession += gap;
            }
        }
        lastActivityElapsedMs = now;
    }

    /** Credits one final trailing gap (still reading the last line right up until now counts),
     * then persists whatever accumulated this session -- called whenever the reader leaves the
     * foreground. */
    private void flushActiveTime() {
        if (lastActivityElapsedMs >= 0) {
            long gap = android.os.SystemClock.elapsedRealtime() - lastActivityElapsedMs;
            if (gap < IDLE_THRESHOLD_MS) {
                activeMsThisSession += gap;
            }
            lastActivityElapsedMs = -1;
        }
        if (activeMsThisSession > 0) {
            SaveManager.addPlayMillis(this, vnKey(), activeMsThisSession);
            activeMsThisSession = 0;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        typeHandler.removeCallbacksAndMessages(null);
        if (musicPlayer != null) {
            musicPlayer.release();
            musicPlayer = null;
        }
        if (sfxPlayer != null) {
            sfxPlayer.release();
            sfxPlayer = null;
        }
    }
}
