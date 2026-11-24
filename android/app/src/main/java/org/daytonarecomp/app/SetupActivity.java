package org.daytonarecomp.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Launcher. Starts the game when its data is installed; otherwise lets the
 * user pick a .zip of the extracted game data (the contents of extracted/,
 * default.xex included) with the system file picker and unpacks it into
 *   /sdcard/Android/data/org.daytonarecomp.app/files/assets/
 * No storage permission is needed: the picker grants access to that one file,
 * and the destination is the app's own folder. No PC required.
 */
public class SetupActivity extends Activity {
    private static final String TAG = "Daytona";
    private static final int PICK_ZIP = 1;
    private static final String MARKER = "default.xex";

    private TextView status;
    private ProgressBar progress;
    private Button pick;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (new File(assetsDir(), MARKER).isFile()) {
            launchGame();
            return;
        }
        buildUi();
    }

    private File assetsDir() {
        File files = getExternalFilesDir(null);
        if (files == null) {
            files = getFilesDir();
        }
        return new File(files, "assets");
    }

    private void launchGame() {
        startActivity(new Intent(this, DaytonaActivity.class));
        finish();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(64, 64, 64, 64);
        root.setBackgroundColor(Color.rgb(16, 24, 40));

        TextView title = new TextView(this);
        title.setText("Daytona USA");
        title.setTextSize(28);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        root.addView(title);

        status = new TextView(this);
        status.setText("Les données du jeu ne sont pas encore installées.\n"
                + "Choisis le fichier daytona-assets.zip (le contenu extrait de ton paquet XBLA).");
        status.setTextSize(16);
        status.setTextColor(Color.LTGRAY);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, 32, 0, 32);
        root.addView(status);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        pick = new Button(this);
        pick.setText("Choisir daytona-assets.zip");
        pick.setOnClickListener(v -> openPicker());
        root.addView(pick);

        setContentView(root);
        pick.requestFocus();  // reachable with the d-pad + A on a handheld
    }

    private void openPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[] {"application/zip", "application/x-zip-compressed", "application/octet-stream"});
        startActivityForResult(intent, PICK_ZIP);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_ZIP || resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        pick.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        status.setText("Installation des données…");
        new Thread(() -> install(uri), "assets-import").start();
    }

    private void install(Uri uri) {
        File dest = assetsDir();
        try {
            // Pass 1: find default.xex, so a zip holding a top-level folder
            // (extracted/default.xex, assets/default.xex, ...) works too, and
            // add up the sizes for the progress bar.
            String prefix = null;
            long total = 0;
            try (ZipInputStream zip = open(uri)) {
                ZipEntry e;
                while ((e = zip.getNextEntry()) != null) {
                    String name = e.getName().replace('\\', '/');
                    if (!e.isDirectory() && (name.equals(MARKER) || name.endsWith("/" + MARKER))) {
                        String p = name.substring(0, name.length() - MARKER.length());
                        if (prefix == null || p.length() < prefix.length()) {
                            prefix = p;
                        }
                    }
                    if (e.getSize() > 0) {
                        total += e.getSize();
                    }
                }
            }
            final boolean sized = total > 0;
            ui.post(() -> progress.setIndeterminate(!sized));
            if (prefix == null) {
                fail("Ce zip ne contient pas default.xex. Choisis daytona-assets.zip.");
                return;
            }

            deleteRecursive(dest);
            if (!dest.mkdirs() && !dest.isDirectory()) {
                fail("Impossible de créer " + dest);
                return;
            }
            String destRoot = dest.getCanonicalPath() + File.separator;

            // Pass 2: extract everything under that prefix.
            long done = 0;
            byte[] buf = new byte[1 << 16];
            try (ZipInputStream zip = open(uri)) {
                ZipEntry e;
                while ((e = zip.getNextEntry()) != null) {
                    String name = e.getName().replace('\\', '/');
                    if (!name.startsWith(prefix)) {
                        continue;
                    }
                    String rel = name.substring(prefix.length());
                    if (rel.isEmpty()) {
                        continue;
                    }
                    File out = new File(dest, rel);
                    // Refuse entries that would land outside the folder ("../").
                    if (!out.getCanonicalPath().startsWith(destRoot)) {
                        Log.w(TAG, "skipping unsafe zip entry " + name);
                        continue;
                    }
                    if (e.isDirectory()) {
                        out.mkdirs();
                        continue;
                    }
                    File parent = out.getParentFile();
                    if (parent != null) {
                        parent.mkdirs();
                    }
                    try (OutputStream os = new FileOutputStream(out)) {
                        int n;
                        while ((n = zip.read(buf)) > 0) {
                            os.write(buf, 0, n);
                            done += n;
                            if (total > 0) {
                                final int permille = (int) Math.min(1000, done * 1000 / total);
                                ui.post(() -> progress.setProgress(permille));
                            }
                        }
                    }
                }
            }
            if (!new File(dest, MARKER).isFile()) {
                fail("default.xex manque après l'installation.");
                return;
            }
            Log.i(TAG, "game data installed in " + dest);
            ui.post(this::launchGame);
        } catch (IOException | SecurityException ex) {
            Log.e(TAG, "import failed", ex);
            deleteRecursive(dest);
            fail("Échec de l'installation : " + ex.getMessage());
        }
    }

    private ZipInputStream open(Uri uri) throws IOException {
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new IOException("fichier illisible");
        }
        return new ZipInputStream(in);
    }

    private void fail(String message) {
        ui.post(() -> {
            status.setText(message);
            progress.setVisibility(View.GONE);
            pick.setEnabled(true);
        });
    }

    private static void deleteRecursive(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursive(c);
            }
        }
        f.delete();
    }
}
