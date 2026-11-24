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
                + "Choisis ton jeu : le paquet XBLA, un zip qui le contient,\n"
                + "ou daytona-assets.zip (les fichiers déjà extraits).");
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
        pick.setText("Choisir le fichier du jeu");
        pick.setOnClickListener(v -> openPicker());
        root.addView(pick);

        setContentView(root);
        pick.requestFocus();  // reachable with the d-pad + A on a handheld
    }

    private void openPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // Any type: the package has no extension, so no MIME filter fits it.
        intent.setType("*/*");
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
            // The original package itself (a LIVE/STFS file, no extension)?
            byte[] head = new byte[4];
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null || readFully(in, head) < 4) {
                    fail("Fichier illisible ou vide.");
                    return;
                }
            }
            if (StfsExtractor.isLivePackage(head)) {
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    installFromPackage(in, -1, dest);
                }
                return;
            }

            // Otherwise a zip. Pass 1: find default.xex, so a zip holding a top-level folder
            // (extracted/default.xex, assets/default.xex, ...) works too, and
            // add up the sizes for the progress bar.
            String prefix = null;
            String packageEntry = null;  // a LIVE package inside the zip
            long packageSize = -1;
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
                    if (!e.isDirectory() && packageEntry == null) {
                        byte[] magic = new byte[4];
                        if (readFully(zip, magic) == 4 && StfsExtractor.isLivePackage(magic)) {
                            packageEntry = name;
                            packageSize = e.getSize();
                        }
                    }
                }
            }
            if (prefix == null && packageEntry != null) {
                // e.g. the "(World) (XBLA)" zip: one LIVE package inside.
                try (ZipInputStream zip = open(uri)) {
                    ZipEntry e;
                    while ((e = zip.getNextEntry()) != null) {
                        if (e.getName().replace('\\', '/').equals(packageEntry)) {
                            installFromPackage(zip, packageSize, dest);
                            return;
                        }
                    }
                }
                fail("Paquet introuvable dans le zip.");
                return;
            }
            final boolean sized = total > 0;
            ui.post(() -> progress.setIndeterminate(!sized));
            if (prefix == null) {
                fail("Ni default.xex ni paquet XBLA dans ce zip.");
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

    /**
     * Copies the package to the app folder (STFS needs random access, which a
     * picker stream does not give), unpacks it into dest, deletes the copy.
     */
    private void installFromPackage(InputStream in, long size, File dest) throws IOException {
        File tmp = new File(dest.getParentFile(), "package.tmp");
        ui.post(() -> {
            status.setText("Copie du paquet… (1/2)");
            progress.setIndeterminate(size <= 0);
        });
        try {
            long copied = 0;
            byte[] buf = new byte[1 << 16];
            try (OutputStream os = new FileOutputStream(tmp)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                    copied += n;
                    if (size > 0) {
                        final int permille = (int) Math.min(1000, copied * 1000 / size);
                        ui.post(() -> progress.setProgress(permille));
                    }
                }
            }
            deleteRecursive(dest);
            dest.mkdirs();
            ui.post(() -> {
                status.setText("Extraction du jeu… (2/2)");
                progress.setIndeterminate(false);
                progress.setProgress(0);
            });
            int files = StfsExtractor.extract(tmp, dest, (done, total) -> {
                final int permille = total > 0 ? (int) Math.min(1000, done * 1000 / total) : 0;
                ui.post(() -> progress.setProgress(permille));
            });
            Log.i(TAG, "extracted " + files + " files from the package into " + dest);
        } catch (IOException ex) {
            deleteRecursive(dest);
            throw ex;
        } finally {
            tmp.delete();
        }
        ui.post(this::launchGame);
    }

    private static int readFully(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) {
                break;
            }
            off += n;
        }
        return off;
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
