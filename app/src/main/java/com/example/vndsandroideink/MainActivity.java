package com.example.vndsandroideink;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.vndsandroideink.nscripter.NsSaveManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends AppCompatActivity implements LibraryAdapter.Listener {

    private RecyclerView recyclerView;
    private View libraryScrollButtons;
    private MenuPanel menu;
    private LibraryAdapter adapter;
    private AlertDialog importDialog;
    /** Which of the two "pick a SAF tree" chooser options launched {@link #openTree}, since both
     * share one launcher/contract but need different importer entry points on return. */
    private boolean singleFolderImport = false;
    /** Set by the import dialog's Cancel button (archive imports only -- see {@link #openArchive});
     * polled by {@link VnImporter#importArchive} during its copy/extract phases. Re-created fresh
     * per import so a stale flag from a finished import can't affect the next one. */
    private AtomicBoolean importCancelRequested;
    /** vnKey/title of the novel a save export/import file picker was just launched for, read
     * back once the picker returns a Uri (or null if cancelled). */
    private String pendingSaveVnKey;
    private String pendingSaveTitle;
    /** vnKey a completion-guide file picker was just launched for, read back once the picker
     * returns a Uri (or null if cancelled). */
    private String pendingGuideVnKey;

    /** Refreshes the visible list from the current local library state with no extra behavior;
     * shared by every call site that doesn't need anything beyond that (delete, save import,
     * VNDB metadata fetch). onResume's own call additionally forces an e-ink refresh, so it keeps
     * its own callback instead of using this one. */
    private final VnImporter.Callback refreshCallback = new VnImporter.Callback() {
        @Override
        public void onComplete(List<VnEntry> entries) {
            adapter.setEntries(entries);
        }

        @Override
        public void onError(Exception e) {
        }
    };

    private final VnImporter.Callback importCallback = new VnImporter.Callback() {
        @Override
        public void onComplete(List<VnEntry> entries) {
            dismissImportDialog();
            adapter.setEntries(entries);
        }

        @Override
        public void onError(Exception e) {
            dismissImportDialog();
            Toast.makeText(MainActivity.this, getString(R.string.import_failed, e.getMessage()), Toast.LENGTH_LONG).show();
        }

        @Override
        public void onProgress(String message) {
            if (importDialog != null && importDialog.isShowing()) {
                importDialog.setMessage(message);
            }
        }

        @Override
        public void onCancelled() {
            dismissImportDialog();
            Toast.makeText(MainActivity.this, R.string.import_cancelled, Toast.LENGTH_SHORT).show();
        }
    };

    private final ActivityResultLauncher<Uri> openTree =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri == null) {
                    return;
                }
                showImportDialog(null);
                if (singleFolderImport) {
                    VnImporter.scanAndImportSingleFolder(this, uri, importCallback);
                } else {
                    VnImporter.scanAndImportTree(this, uri, importCallback);
                }
            });

    private final ActivityResultLauncher<String[]> openArchive =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) {
                    return;
                }
                importCancelRequested = new AtomicBoolean(false);
                showImportDialog(() -> importCancelRequested.set(true));
                VnImporter.importArchive(this, uri, importCancelRequested, importCallback);
            });

    private final ActivityResultLauncher<String> createSaveExportFile =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("application/json"), uri -> {
                if (uri == null || pendingSaveVnKey == null) {
                    return;
                }
                writeSaveExport(uri, pendingSaveVnKey, pendingSaveTitle);
            });

    private final ActivityResultLauncher<String[]> openSaveImportFile =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null || pendingSaveVnKey == null) {
                    return;
                }
                readSaveImport(uri, pendingSaveVnKey, pendingSaveTitle);
            });

    private final ActivityResultLauncher<String[]> openGuideImportFile =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null || pendingGuideVnKey == null) {
                    return;
                }
                readGuideImport(uri, pendingGuideVnKey);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        EdgeToEdge.applyInsets(findViewById(R.id.mainRoot));

        recyclerView = findViewById(R.id.recyclerView);
        View menuPanelView = findViewById(R.id.menuPanel);
        View menuScrimView = findViewById(R.id.menuScrim);
        menu = new MenuPanel(menuPanelView, menuScrimView);

        findViewById(R.id.menuButton).setOnClickListener(v -> {
            if (menu.isOpen()) {
                menu.close();
            } else {
                menu.open();
            }
        });
        menuScrimView.setOnClickListener(v -> menu.close());
        findViewById(R.id.menuSettings).setOnClickListener(v -> {
            menu.close();
            SettingsDialog.show(this, this::applyLibraryScrollPrefs, null);
        });
        findViewById(R.id.menuGuideLibrary).setOnClickListener(v -> {
            menu.close();
            NoAnimTransition.start(this, new Intent(this, GuideLibraryActivity.class));
        });
        findViewById(R.id.menuQuit).setOnClickListener(v -> {
            menu.close();
            ConfirmDialog.show(this, getString(R.string.confirm_quit_title),
                    getString(R.string.confirm_quit_message), getString(R.string.quit), this::finishAffinity, null);
        });
        findViewById(R.id.importButton).setOnClickListener(v -> showImportChooser());

        libraryScrollButtons = findViewById(R.id.libraryScrollButtons);
        // scrollBy (not smoothScrollBy) jumps a full page instantly, with no animated glide for
        // e-ink to have to redraw through -- same pattern SettingsDialog/GuideDialog use.
        findViewById(R.id.libraryScrollUp).setOnClickListener(v -> recyclerView.scrollBy(0, -recyclerView.getHeight()));
        findViewById(R.id.libraryScrollDown).setOnClickListener(v -> recyclerView.scrollBy(0, recyclerView.getHeight()));
        applyLibraryScrollPrefs();

        adapter = new LibraryAdapter(this);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setItemAnimator(null); // no fade/move animations
        recyclerView.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
        recyclerView.setAdapter(adapter);

        menu.wireBackPress(this, null);
    }

    /** Shows/hides the up/down paging buttons per {@link Prefs#isPagedLibraryScroll} -- called on
     * launch and again live whenever Settings changes the toggle, without needing to reopen the
     * library. */
    private void applyLibraryScrollPrefs() {
        libraryScrollButtons.setVisibility(Prefs.isPagedLibraryScroll(this) ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyLibraryScrollPrefs();
        // Refreshes on every return to the library too (not just first launch), since resume
        // status and total time read both change while a story was just being read.
        VnImporter.loadLocalLibrary(this, new VnImporter.Callback() {
            @Override
            public void onComplete(List<VnEntry> entries) {
                adapter.setEntries(entries);
                // onResume covers both opening the app (it always follows onCreate) and
                // returning to it from multitasking (Home, task switcher, screen lock) -- a full
                // flash clears any ghosting from whatever was drawn on screen before this.
                maybeFullEinkRefresh();
            }

            @Override
            public void onError(Exception e) {
                // loadLocalLibrary never actually throws (pure local file scan); nothing to show.
            }
        });
    }

    /** Forces a full-screen (GC16) flash on Onyx hardware, when eink mode is on; a no-op
     * everywhere else (see {@link EinkRefreshManager}). Deferred with {@code post} so it fires
     * after the pending layout/draw pass (the just-updated library list), not a stale frame. */
    private void maybeFullEinkRefresh() {
        if (Prefs.isEinkMode(this) && EinkRefreshManager.isSupported()) {
            recyclerView.post(EinkRefreshManager::fullRefresh);
        }
    }

    @Override
    public void onVnClick(VnEntry entry) {
        String vnKey = entry.localDir.getName();
        boolean isNs = entry.engineType == VnEntry.EngineType.NSCRIPTER;
        boolean hasResume = isNs ? NsSaveManager.hasResume(this, vnKey) : SaveManager.hasResume(this, vnKey);
        List<SaveManager.SlotInfo> slots = isNs ? NsSaveManager.listSlots(this, vnKey) : SaveManager.listSlots(this, vnKey);
        boolean hasSaves = false;
        for (SaveManager.SlotInfo slot : slots) {
            if (slot.occupied) {
                hasSaves = true;
                break;
            }
        }
        if (!hasResume && !hasSaves) {
            launchReader(entry, -1);
            return;
        }
        LaunchChooserDialog.show(this, entry.title, hasResume, hasSaves, new LaunchChooserDialog.Listener() {
            @Override
            public void onStartFromBeginning() {
                launchReader(entry, -1);
            }

            @Override
            public void onResume() {
                launchReader(entry, SaveManager.SLOT_RESUME);
            }

            @Override
            public void onLoadSave() {
                // Also list the resume slot here (the reader's own in-session "Load" menu
                // deliberately doesn't -- loading the current resume point mid-session isn't
                // useful the way it is from the library, where it's the alternative to "Resume").
                List<SaveManager.SlotInfo> withResume = new ArrayList<>();
                if (hasResume) {
                    withResume.add(isNs ? NsSaveManager.resumeSlotInfo(MainActivity.this, vnKey)
                            : SaveManager.resumeSlotInfo(MainActivity.this, vnKey));
                }
                withResume.addAll(slots);
                SaveSlotDialog.show(MainActivity.this, false, withResume,
                        slot -> onSlotPicked(entry, slot, hasResume), null);
            }
        });
    }

    private void onSlotPicked(VnEntry entry, int slot, boolean hadResume) {
        if (hadResume && slot != SaveManager.SLOT_RESUME) {
            // Loading a manual slot means the resume snapshot -- which still points at wherever
            // the player last left off -- will be overwritten by this slot's progress the next
            // time they leave the story again.
            ConfirmDialog.show(this, getString(R.string.confirm_overwrite_resume_title),
                    getString(R.string.confirm_overwrite_resume_message),
                    getString(R.string.load_progress), () -> launchReader(entry, slot), null);
        } else {
            launchReader(entry, slot);
        }
    }

    private void launchReader(VnEntry entry, int loadSlot) {
        Intent intent = new Intent(MainActivity.this, ReaderActivity.class);
        intent.putExtra(ReaderActivity.EXTRA_VN_DIR, entry.localDir.getAbsolutePath());
        intent.putExtra(ReaderActivity.EXTRA_VN_TITLE, entry.title);
        intent.putExtra(ReaderActivity.EXTRA_LOAD_SLOT, loadSlot);
        intent.putExtra(ReaderActivity.EXTRA_VN_ENGINE, entry.engineType.name());
        NoAnimTransition.start(this, intent);
    }

    private void showImportChooser() {
        ChooserDialog.show(this, R.string.import_novel_chooser_title,
                new ChooserDialog.Row(R.string.import_from_folder, () -> {
                    singleFolderImport = false;
                    openTree.launch(null);
                }),
                new ChooserDialog.Row(R.string.import_single_folder, () -> {
                    singleFolderImport = true;
                    openTree.launch(null);
                }),
                new ChooserDialog.Row(R.string.import_from_archive, () -> openArchive.launch(new String[]{"*/*"})));
    }

    @Override
    public void onVnMenuClick(VnEntry entry) {
        String vnKey = entry.localDir.getName();
        VnRowMenuDialog.show(this, entry.title, GuideManager.hasGuide(this, vnKey), new VnRowMenuDialog.Listener() {
            @Override
            public void onEditTitle() {
                EditTitleDialog.show(MainActivity.this, entry.title, newTitle -> {
                    TitleOverrideManager.set(MainActivity.this, vnKey, newTitle);
                    VnImporter.loadLocalLibrary(MainActivity.this, refreshCallback);
                });
            }

            @Override
            public void onGetInfoFromVndb() {
                VndbFetchDialog.show(MainActivity.this, rawId -> fetchVndbInfo(vnKey, rawId));
            }

            @Override
            public void onVisitVndbPage() {
                VndbMeta meta = VndbManager.load(MainActivity.this, vnKey);
                if (meta == null) {
                    Toast.makeText(MainActivity.this, R.string.vndb_no_id_linked, Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(meta.url())));
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(MainActivity.this, R.string.vndb_no_browser, Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onExportSaves() {
                try {
                    JSONObject root = SaveManager.exportData(MainActivity.this, vnKey, entry.title);
                    if (root.getJSONObject("entries").length() == 0) {
                        Toast.makeText(MainActivity.this, R.string.export_saves_none, Toast.LENGTH_SHORT).show();
                        return;
                    }
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, getString(R.string.export_saves_failed, e.getMessage()), Toast.LENGTH_LONG).show();
                    return;
                }
                pendingSaveVnKey = vnKey;
                pendingSaveTitle = entry.title;
                createSaveExportFile.launch(entry.title.replaceAll("[^a-zA-Z0-9 _-]", "") + " saves.json");
            }

            @Override
            public void onImportSaves() {
                pendingSaveVnKey = vnKey;
                pendingSaveTitle = entry.title;
                openSaveImportFile.launch(new String[]{"application/json", "*/*"});
            }

            @Override
            public void onImportGuide() {
                pendingGuideVnKey = vnKey;
                openGuideImportFile.launch(new String[]{"application/json", "*/*"});
            }

            @Override
            public void onViewGuide() {
                GuideDialog.show(MainActivity.this, vnKey, entry.title, null);
            }

            @Override
            public void onDeleteGuide() {
                ConfirmDialog.show(MainActivity.this, getString(R.string.delete_guide_title),
                        getString(R.string.delete_guide_message, entry.title), getString(R.string.delete), () -> {
                            GuideManager.deleteGuide(MainActivity.this, vnKey);
                            Toast.makeText(MainActivity.this, R.string.delete_guide_success, Toast.LENGTH_SHORT).show();
                        }, null);
            }
        });
    }

    private void readGuideImport(Uri uri, String vnKey) {
        try {
            GuideManager.importGuide(this, vnKey, uri);
            Toast.makeText(this, R.string.import_guide_success, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.import_guide_failed, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    /** @param vnKey the novel to write this file's saves into; must be already validated as the
     *               one being exported. */
    private void writeSaveExport(Uri uri, String vnKey, String title) {
        try {
            JSONObject root = SaveManager.exportData(this, vnKey, title);
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) {
                    throw new IOException("Couldn't open the picked file for writing");
                }
                out.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            Toast.makeText(this, R.string.export_saves_success, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.export_saves_failed, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private void readSaveImport(Uri uri, String vnKey, String currentTitle) {
        JSONObject root;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new IOException("Couldn't open the picked file for reading");
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            root = new JSONObject(buffer.toString(StandardCharsets.UTF_8.name()));
            if (!SaveManager.isSaveExportFile(root)) {
                throw new JSONException("Not a VNDS save export file");
            }
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.import_saves_failed, e.getMessage()), Toast.LENGTH_LONG).show();
            return;
        }
        String exportedTitle = SaveManager.exportedTitle(root);
        String message = getString(R.string.import_saves_confirm_message);
        if (!exportedTitle.isEmpty() && !exportedTitle.equals(currentTitle)) {
            message = getString(R.string.import_saves_wrong_novel, exportedTitle) + "\n\n" + message;
        }
        ConfirmDialog.show(this, getString(R.string.import_saves_confirm_title), message,
                getString(R.string.import_action), () -> {
                    try {
                        SaveManager.importData(this, vnKey, root);
                        Toast.makeText(this, R.string.import_saves_success, Toast.LENGTH_SHORT).show();
                        VnImporter.loadLocalLibrary(this, refreshCallback);
                    } catch (Exception e) {
                        Toast.makeText(this, getString(R.string.import_saves_failed, e.getMessage()), Toast.LENGTH_LONG).show();
                    }
                }, null);
    }

    private void fetchVndbInfo(String vnKey, String rawId) {
        AlertDialog fetching = new AlertDialog.Builder(this)
                .setMessage(R.string.vndb_fetching)
                .setCancelable(false)
                .show();
        VndbManager.fetch(this, vnKey, rawId, new VndbManager.Callback() {
            @Override
            public void onSuccess(VndbMeta meta) {
                fetching.dismiss();
                // Refresh the whole list so this entry picks up the newly-linked metadata.
                VnImporter.loadLocalLibrary(MainActivity.this, refreshCallback);
            }

            @Override
            public void onError(Exception e) {
                fetching.dismiss();
                Toast.makeText(MainActivity.this, getString(R.string.vndb_fetch_failed, e.getMessage()), Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    public void onDeleteClick(VnEntry entry) {
        ConfirmDialog.show(this, getString(R.string.delete_novel_title),
                getString(R.string.delete_novel_message, entry.title),
                getString(R.string.delete), () -> VnImporter.deleteLocal(this, entry, refreshCallback), null);
    }

    /** @param onCancel if non-null, shows a Cancel button wired to it (archive imports only --
     *                  the other import paths have no cancellation checkpoints to honor it). */
    private void showImportDialog(Runnable onCancel) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(R.string.importing_title)
                .setMessage(R.string.importing_message)
                .setCancelable(false);
        if (onCancel != null) {
            builder.setNegativeButton(R.string.cancel, (dialog, which) -> onCancel.run());
        }
        importDialog = builder.show();
        // Same flat 1px border as every other popup in the app, in place of the default Material
        // dialog card (rounded corners/elevation shadow, no border at all).
        importDialog.getWindow().setBackgroundDrawableResource(R.drawable.bg_menu_popup);
        // A multi-minute extraction with the screen timing out partway through just looks dead --
        // keep it awake for as long as the dialog's up, cleared again in dismissImportDialog().
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void dismissImportDialog() {
        if (importDialog != null && importDialog.isShowing()) {
            importDialog.dismiss();
        }
        importDialog = null;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
}
