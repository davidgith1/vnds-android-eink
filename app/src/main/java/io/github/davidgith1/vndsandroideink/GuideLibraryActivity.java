package io.github.davidgith1.vndsandroideink;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * The "Guides" page: a second, separate library screen (switched to from the novel library's own
 * three-dot menu, see MainActivity's menuGuideLibrary) listing completion guides that aren't tied
 * to any imported VNDS story -- just a VNDB link plus an imported guide.json. Same overall shape
 * as MainActivity itself (RecyclerView + a bottom-right add button + a hamburger menu), since it
 * plays the same "library" role for these entries.
 */
public class GuideLibraryActivity extends AppCompatActivity implements StandaloneGuideAdapter.Listener {

    private RecyclerView recyclerView;
    private View emptyView;
    private MenuPanel menu;
    private StandaloneGuideAdapter adapter;
    /** Which entry a completion-guide file picker was just launched for, read back once the
     * picker returns a Uri (or null if cancelled). */
    private String pendingGuideKey;

    private final ActivityResultLauncher<String[]> openGuideImportFile =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null || pendingGuideKey == null) {
                    return;
                }
                readGuideImport(uri, pendingGuideKey);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        NoAnimTransition.applyOpenOverride(this);
        setContentView(R.layout.activity_guide_library);
        EdgeToEdge.applyInsets(findViewById(R.id.guideLibraryRoot));

        recyclerView = findViewById(R.id.recyclerView);
        emptyView = findViewById(R.id.emptyView);
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
        findViewById(R.id.menuBackToLibrary).setOnClickListener(v -> {
            menu.close();
            NoAnimTransition.finish(this);
        });
        findViewById(R.id.menuSettings).setOnClickListener(v -> {
            menu.close();
            SettingsDialog.show(this, null, null);
        });
        findViewById(R.id.menuQuit).setOnClickListener(v -> {
            menu.close();
            ConfirmDialog.show(this, getString(R.string.confirm_quit_title),
                    getString(R.string.confirm_quit_message), getString(R.string.quit), this::finishAffinity, null);
        });
        findViewById(R.id.addGuideButton).setOnClickListener(v -> showAddGuideChooser());

        adapter = new StandaloneGuideAdapter(this);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setItemAnimator(null); // no fade/move animations
        recyclerView.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
        recyclerView.setAdapter(adapter);

        menu.wireBackPress(this, null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshList();
    }

    private void refreshList() {
        List<StandaloneGuideManager.Entry> entries = StandaloneGuideManager.listEntries(this);
        adapter.setEntries(entries);
        emptyView.setVisibility(adapter.isEmpty() ? View.VISIBLE : View.GONE);
        if (Prefs.isEinkMode(this) && EinkRefreshManager.isSupported()) {
            recyclerView.post(EinkRefreshManager::fullRefresh);
        }
    }

    private void showAddGuideChooser() {
        ChooserDialog.show(this, R.string.add_guide_chooser_title,
                new ChooserDialog.Row(R.string.add_guide_by_vndb, () ->
                        VndbFetchDialog.show(this, rawId -> fetchNewEntry(rawId))),
                new ChooserDialog.Row(R.string.add_guide_by_name, () ->
                        AddGuideByNameDialog.show(this, title -> addManualEntry(title))));
    }

    /** The "+ Add guide" → "Just enter a name" flow: no VNDB lookup at all, just a plain title. */
    private void addManualEntry(String title) {
        StandaloneGuideManager.createManualEntry(this, title);
        refreshList();
        Toast.makeText(this, R.string.add_guide_no_guide_yet, Toast.LENGTH_LONG).show();
    }

    /** The "+ Add guide" → "Link to a VNDB entry" flow: a brand-new entry is only registered once
     * its VNDB fetch actually succeeds, so a cancelled/failed lookup never leaves an orphan entry
     * with no metadata. */
    private void fetchNewEntry(String rawId) {
        String key = StandaloneGuideManager.newKey();
        AlertDialog fetching = new AlertDialog.Builder(this)
                .setMessage(R.string.vndb_fetching)
                .setCancelable(false)
                .show();
        VndbManager.fetch(this, key, rawId, new VndbManager.Callback() {
            @Override
            public void onSuccess(VndbMeta meta) {
                fetching.dismiss();
                StandaloneGuideManager.register(GuideLibraryActivity.this, key);
                refreshList();
                Toast.makeText(GuideLibraryActivity.this, R.string.add_guide_no_guide_yet, Toast.LENGTH_LONG).show();
            }

            @Override
            public void onError(Exception e) {
                fetching.dismiss();
                Toast.makeText(GuideLibraryActivity.this, getString(R.string.vndb_fetch_failed, e.getMessage()), Toast.LENGTH_LONG).show();
            }
        });
    }

    @Override
    public void onEntryClick(StandaloneGuideManager.Entry entry) {
        if (entry.hasGuide) {
            GuideDialog.show(this, entry.key, entry.title, null);
        } else {
            Toast.makeText(this, R.string.add_guide_no_guide_yet, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onDeleteClick(StandaloneGuideManager.Entry entry) {
        confirmDeleteEntry(entry);
    }

    @Override
    public void onMenuClick(StandaloneGuideManager.Entry entry) {
        String title = entry.title;
        StandaloneGuideRowMenuDialog.show(this, title, entry.hasGuide, new StandaloneGuideRowMenuDialog.Listener() {
            @Override
            public void onGetInfoFromVndb() {
                VndbFetchDialog.show(GuideLibraryActivity.this, rawId -> relinkVndbInfo(entry.key, rawId));
            }

            @Override
            public void onVisitVndbPage() {
                VndbMeta meta = VndbManager.load(GuideLibraryActivity.this, entry.key);
                if (meta == null) {
                    Toast.makeText(GuideLibraryActivity.this, R.string.vndb_no_id_linked, Toast.LENGTH_SHORT).show();
                    return;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(meta.url())));
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(GuideLibraryActivity.this, R.string.vndb_no_browser, Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onImportGuide() {
                pendingGuideKey = entry.key;
                openGuideImportFile.launch(new String[]{"application/json", "*/*"});
            }

            @Override
            public void onViewGuide() {
                GuideDialog.show(GuideLibraryActivity.this, entry.key, title, null);
            }

            @Override
            public void onDeleteGuide() {
                ConfirmDialog.show(GuideLibraryActivity.this, getString(R.string.delete_guide_title),
                        getString(R.string.delete_guide_message, title), getString(R.string.delete), () -> {
                            GuideManager.deleteGuide(GuideLibraryActivity.this, entry.key);
                            Toast.makeText(GuideLibraryActivity.this, R.string.delete_guide_success, Toast.LENGTH_SHORT).show();
                            refreshList();
                        }, null);
            }

            @Override
            public void onDeleteEntry() {
                confirmDeleteEntry(entry);
            }
        });
    }

    private void confirmDeleteEntry(StandaloneGuideManager.Entry entry) {
        String title = entry.title;
        ConfirmDialog.show(this, getString(R.string.delete_entry_title),
                getString(R.string.delete_entry_message, title), getString(R.string.delete), () -> {
                    StandaloneGuideManager.deleteEntry(this, entry.key);
                    refreshList();
                }, null);
    }

    private void relinkVndbInfo(String key, String rawId) {
        AlertDialog fetching = new AlertDialog.Builder(this)
                .setMessage(R.string.vndb_fetching)
                .setCancelable(false)
                .show();
        VndbManager.fetch(this, key, rawId, new VndbManager.Callback() {
            @Override
            public void onSuccess(VndbMeta meta) {
                fetching.dismiss();
                refreshList();
            }

            @Override
            public void onError(Exception e) {
                fetching.dismiss();
                Toast.makeText(GuideLibraryActivity.this, getString(R.string.vndb_fetch_failed, e.getMessage()), Toast.LENGTH_LONG).show();
            }
        });
    }

    private void readGuideImport(Uri uri, String key) {
        try {
            GuideManager.importGuide(this, key, uri);
            Toast.makeText(this, R.string.import_guide_success, Toast.LENGTH_SHORT).show();
            refreshList();
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.import_guide_failed, e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }
}
