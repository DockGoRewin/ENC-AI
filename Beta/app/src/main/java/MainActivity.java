package com.dockgo.encaibeta;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfRenderer;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.BulletSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.MetricAffectingSpan;
import android.text.style.QuoteSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.util.Base64;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
    private static final String PREFS = "enc_ai_settings";
    private static final String DEFAULT_URL = "http://127.0.0.1:20128";
    private static final String DEFAULT_MODEL = "gemini/gemini-3.5-flash";
    private static final String HERMES_MODEL = "hermes-agent";
    private static final String BACKEND_9ROUTER = "9router";
    private static final String BACKEND_HERMES = "hermes";

    // Folder penyimpanan file hasil Hermes (di filesDir, tetap ada setelah app ditutup)
    private static final String GENERATED_DIR = "generated";

    // Batas lampiran
    private static final int REQ_PICK_FILES = 1001;
    private static final int REQ_CAMERA = 1002;
    private static final long MAX_FILE_BYTES = 20L * 1024 * 1024; // 20 MB per file
    private static final int MAX_ATTACHMENTS = 6;
    private static final int MAX_IMAGE_DIM = 1280;                // sisi terpanjang gambar yang dikirim
    private static final int MAX_PDF_PAGES = 4;                   // halaman PDF yang dirender jadi gambar

    // Batas karakter file teks.
    private static final int INLINE_TEXT_MAX = 30000;
    private static final int MAX_TEXT_CHARS_9ROUTER = 100000;
    private static final int MAX_TEXT_CHARS_HERMES = 1000000;

    private int maxTextChars() {
        return isHermes() ? MAX_TEXT_CHARS_HERMES : MAX_TEXT_CHARS_9ROUTER;
    }

    private static final Set<String> TEXT_EXT = new HashSet<>(Arrays.asList(
            "txt", "md", "csv", "tsv", "json", "xml", "html", "htm", "css", "js", "ts", "java", "kt",
            "py", "c", "cpp", "h", "hpp", "cs", "sh", "yml", "yaml", "log", "sql", "ini", "toml",
            "gradle", "properties", "php", "rb", "go", "rs", "swift", "dart", "tex", "srt",
            "lua", "bat", "ps1", "cfg", "conf"));

    // Katalog bawaan: tampil di pemilih model walau /v1/models gagal dimuat.
    private static final String[] BUILTIN_MODELS = {
            // Google Gemini (API key)
            "gemini/gemini-4-argon", "gemini/gemini-3.8-flash", "gemini/gemini-3.7-flash", "gemini/gemini-3.6-flash",
            "gemini/gemini-3.5-flash", "gemini/gemini-3.5-flash-lite", "gemini/gemini-3.1-pro-preview",
            "gemini/gemini-3.1-flash-lite", "gemini/gemini-3-flash-preview",
            "gemini/gemini-2.5-flash", "gemini/gemini-2.5-pro", "gemini/gemini-2.5-flash-lite",
            // Google Gemma
            "gemini/gemma-4-31b-it", "gemini/gemma-4-26b-a4b-it",
            // OpenAI (API key)
            "openai/gpt-5", "openai/gpt-5-mini", "openai/gpt-5-nano", "openai/gpt-4.1",
            "openai/gpt-4.1-mini", "openai/gpt-4o", "openai/gpt-4o-mini", "openai/o3", "openai/o4-mini",
            // Anthropic (API key)
            "anthropic/claude-sonnet-4-5", "anthropic/claude-opus-4-1", "anthropic/claude-haiku-4-5",
            // Claude Code (langganan)
            "cc/claude-opus-4-7", "cc/claude-opus-4-6", "cc/claude-sonnet-4-5", "cc/claude-haiku-4-5",
            // Codex (langganan)
            "cx/gpt-5.3-codex", "cx/gpt-5-codex",
            // Kiro
            "kr/claude-sonnet-4.5", "kr/claude-haiku-4.5",
            // Lainnya
            "deepseek/deepseek-chat", "deepseek/deepseek-reasoner",
            "xai/grok-4", "xai/grok-3",
            "mistral/mistral-large-latest", "mistral/codestral-latest",
            "groq/llama-3.3-70b-versatile"
    };

    // Palet warna: tema terang (bawaan) dan tema gelap, diatur lewat applyTheme().
    // Catatan: nama variabel PURPLE* dipertahankan agar kode lain tidak perlu diubah,
    // tetapi nilainya sekarang berwarna BIRU.
    private static final String DEFAULT_NAME = "Christian";
    private boolean darkMode = false;
    private int BG_TOP, BG_BOTTOM, CARD, CARD_STROKE, SHEET, NAV_BG, ACCENT_SOFT;
    private int PURPLE, PURPLE_DEEP, PURPLE_LIGHT, BLUE, TEXT, MUTED, GREEN;
    private int CODE_BG, CODE_HEAD, CODE_FG, INLINE_BG, HR_COLOR, QUOTE_TXT, LINK;

    // Tab nav bawah (0-4) + halaman tambahan yang tidak ada di nav bawah
    private static final int TAB_HOME = 0, TAB_TOOLS = 1, TAB_CHAT = 2, TAB_HISTORY = 3, TAB_PROFILE = 4,
            TAB_MODEL = 5, TAB_SETTINGS = 6;
    // Jenis ikon vektor
    private static final int IC_HOME = 0, IC_GRID = 1, IC_SPARK = 2, IC_MODEL = 3, IC_GEAR = 4,
            IC_BACK = 5, IC_CHEV = 6, IC_HISTORY = 7, IC_USER = 8, IC_CHAT = 9, IC_CODE = 10,
            IC_NOTE = 11, IC_TRANSLATE = 12, IC_BULB = 13, IC_BUG = 14, IC_PEN = 15, IC_DOC = 16,
            IC_SCAN = 17, IC_MAIL = 18, IC_CAMERA = 19, IC_MOON = 20, IC_TRASH = 21, IC_PLUG = 22,
            IC_WAND = 23, IC_CLIP = 24, IC_IMAGE = 25, IC_UP = 26, IC_DOWNLOAD = 27, IC_COPY = 28,
            IC_EXPAND = 29, IC_CLOSE = 30, IC_CHECK = 31, IC_PLUS = 32, IC_CHEVDOWN = 33;

    private interface BoolCb { void run(boolean value); }

    // ------------------------------------------------------------ Font
    private Typeface tfRegular, tfBold, tfMono;

    private Typeface loadFont(String asset, Typeface fallback) {
        try { return Typeface.createFromAsset(getAssets(), asset); }
        catch (Exception e) { return fallback; }
    }

    /** Pakai font di assets/fonts/ bila ada; kalau tidak, pakai font sistem yang lebih rapi. */
    private void initFonts() {
        tfRegular = loadFont("fonts/regular.ttf", Typeface.create("sans-serif", Typeface.NORMAL));
        tfBold = loadFont("fonts/bold.ttf", Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tfMono = loadFont("fonts/mono.ttf", Typeface.MONOSPACE);
    }

    /** Span yang memakai Typeface kustom (tebal/mono pada markdown). */
    private static class FontSpan extends MetricAffectingSpan {
        private final Typeface tf;
        FontSpan(Typeface tf) { this.tf = tf; }
        @Override public void updateDrawState(TextPaint p) { p.setTypeface(tf); }
        @Override public void updateMeasureState(TextPaint p) { p.setTypeface(tf); }
    }

    private void applyTheme(boolean dark) {
        darkMode = dark;
        // Aksen biru (menggantikan ungu)
        PURPLE = Color.rgb(59, 130, 246);
        PURPLE_DEEP = Color.rgb(37, 99, 235);
        BLUE = Color.rgb(14, 165, 233);
        GREEN = Color.rgb(34, 197, 94);
        if (dark) {
            BG_TOP = Color.rgb(8, 14, 32);
            BG_BOTTOM = Color.rgb(14, 26, 58);
            CARD = Color.rgb(20, 30, 62);
            CARD_STROKE = Color.rgb(40, 58, 108);
            SHEET = Color.rgb(12, 20, 46);
            NAV_BG = Color.rgb(11, 18, 42);
            ACCENT_SOFT = Color.rgb(30, 50, 108);
            PURPLE_LIGHT = Color.rgb(147, 197, 253);
            TEXT = Color.rgb(241, 246, 255);
            MUTED = Color.rgb(152, 166, 196);
            CODE_BG = Color.rgb(7, 12, 28);
            CODE_HEAD = Color.rgb(24, 38, 78);
            CODE_FG = Color.rgb(190, 242, 220);
            INLINE_BG = Color.rgb(30, 48, 94);
            HR_COLOR = Color.rgb(60, 84, 134);
            QUOTE_TXT = Color.rgb(207, 216, 236);
            LINK = Color.rgb(150, 180, 255);
        } else {
            BG_TOP = Color.rgb(249, 251, 255);
            BG_BOTTOM = Color.rgb(232, 241, 253);
            CARD = Color.rgb(255, 255, 255);
            CARD_STROKE = Color.rgb(219, 230, 246);
            SHEET = Color.rgb(255, 255, 255);
            NAV_BG = Color.rgb(255, 255, 255);
            ACCENT_SOFT = Color.rgb(219, 234, 254);
            PURPLE_LIGHT = Color.rgb(37, 99, 235);
            TEXT = Color.rgb(15, 30, 52);
            MUTED = Color.rgb(100, 116, 145);
            CODE_BG = Color.rgb(244, 248, 255);
            CODE_HEAD = Color.rgb(226, 236, 252);
            CODE_FG = Color.rgb(29, 78, 216);
            INLINE_BG = Color.rgb(219, 234, 254);
            HR_COLOR = Color.rgb(200, 213, 235);
            QUOTE_TXT = Color.rgb(66, 80, 100);
            LINK = Color.rgb(37, 99, 235);
        }
    }

    /** Warna status bar / navigation bar dan ikon terang-gelap mengikuti tema. */
    private void setupSystemBars() {
        Window w = getWindow();
        w.setStatusBarColor(BG_TOP);
        w.setNavigationBarColor(NAV_BG);
        int flags = 0;
        if (!darkMode) {
            if (Build.VERSION.SDK_INT >= 23) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        w.getDecorView().setSystemUiVisibility(flags);
    }

    /** Satu lampiran yang sudah diproses (gambar / halaman PDF / teks). */
    private static class Attachment {
        String name;
        String kind;                 // "image", "pdf", "text"
        String text;                 // isi teks (file teks/docx)
        String note;                 // catatan tambahan, mis. jumlah halaman PDF
        Bitmap thumb;                // pratinjau kecil
        final List<String> dataUrls = new ArrayList<>(); // gambar base64 (data URL)
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private LinearLayout root, messages, attachBar, navBar, homeBox, settingsBox, historyBox, profileBox;
    private FrameLayout content;
    private final View[] pages = new View[7];
    private final FrameLayout[] navHolders = new FrameLayout[5];
    private final IconView[] navIcons = new IconView[5];
    private final TextView[] navLabels = new TextView[5];
    private int currentTab = TAB_HOME, prevTab = TAB_HOME;
    private EditText homeInput, modelSearch;
    private View emptyChat;
    private LinearLayout modelList, modelChipRow;
    private ScrollView modelScroll;
    private TextView modelFeaturedName, modelFeaturedSub, modelApplyBtn;
    // Halaman model: [0] provider, [1] kata cari (huruf kecil), [2] model terpilih, [3] kata cari asli
    private final String[] modelState = {"*", "", "", ""};
    private HorizontalScrollView attachScroll;
    private ScrollView chatScroll;
    private EditText input;
    private TextView status, modelPillText;
    private FrameLayout sendButton;
    private final List<String> allModelIds = new ArrayList<>();
    private final List<String> providerKeys = new ArrayList<>();
    private final Set<String> liveIds = new HashSet<>();
    private final List<ObjectAnimator> animators = new ArrayList<>();
    private String currentModel = DEFAULT_MODEL;
    private String currentProviderKey = "";
    private final JSONArray conversation = new JSONArray();
    // Konten lengkap (dengan gambar/isi file) per indeks pesan; hanya di memori sesi ini.
    private final SparseArray<Object> richContent = new SparseArray<>();
    private final List<Attachment> pending = new ArrayList<>();
    private int processing = 0;
    private Uri cameraUri = null;
    private boolean busy = false;

    // Request latar belakang (ChatService)
    private String pendingText = "";
    private List<Attachment> pendingAtts = new ArrayList<>();
    private boolean loadingShown = false;
    private final BroadcastReceiver doneReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String id = i.getStringExtra(ChatService.EXTRA_ID);
            if (id != null) handleResult(id);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        initFonts();
        applyTheme(prefs.getBoolean("dark_mode", false));
        setupSystemBars();
        buildUi();
        loadHistory();
        fetchModels(false);
        int openTab = prefs.getInt("open_tab", -1);
        if (openTab >= 0) {
            prefs.edit().remove("open_tab").apply();
            showTab(openTab);
        }

        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 200);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        ChatService.appVisible = true;
        IntentFilter f = new IntentFilter(ChatService.ACTION_DONE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(doneReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(doneReceiver, f);
        checkPending();
    }

    @Override
    protected void onStop() {
        ChatService.appVisible = false;
        try { unregisterReceiver(doneReceiver); } catch (Exception ignored) { }
        super.onStop();
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(gradient(BG_TOP, BG_BOTTOM, GradientDrawable.Orientation.TOP_BOTTOM, 0));
        setContentView(root);

        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        pages[TAB_HOME] = buildHomePage();
        pages[TAB_TOOLS] = buildToolsPage();
        pages[TAB_CHAT] = buildChatPage();
        pages[TAB_HISTORY] = buildHistoryPage();
        pages[TAB_PROFILE] = buildProfilePage();
        pages[TAB_MODEL] = buildModelPage();
        pages[TAB_SETTINGS] = buildSettingsPage();
        for (View p : pages) content.addView(p, new FrameLayout.LayoutParams(-1, -1));

        root.addView(buildNav(), new LinearLayout.LayoutParams(-1, -2));

        // Hindari tertutup status bar, tombol navigasi, dan keyboard.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int bottom = insets.getSystemWindowInsetBottom();
            boolean keyboard = bottom > dp(160);
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), keyboard ? bottom : 0);
            navBar.setVisibility(keyboard ? View.GONE : View.VISIBLE);
            navBar.setPadding(0, 0, 0, keyboard ? 0 : bottom);
            return insets;
        });
        root.requestApplyInsets();

        setModels(new String[]{prefs.getString(lastModelKey(), defaultModel()), defaultModel()});
        showTab(TAB_HOME);
    }

    // ------------------------------------------------------ Navigasi tab

    private View buildNav() {
        navBar = new LinearLayout(this);
        navBar.setOrientation(LinearLayout.VERTICAL);
        navBar.setBackgroundColor(NAV_BG);

        View line = new View(this);
        line.setBackgroundColor(CARD_STROKE);
        navBar.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(6), dp(8), dp(6), dp(8));
        navBar.addView(row, new LinearLayout.LayoutParams(-1, -2));

        String[] names = {"Beranda", "Tools", "", "Riwayat", "Profil"};
        int[] icons = {IC_HOME, IC_GRID, IC_SPARK, IC_HISTORY, IC_USER};
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            if (i == TAB_CHAT) {
                View fab = avatar(54);
                fab.setElevation(dp(6));
                item.addView(fab, new LinearLayout.LayoutParams(dp(54), dp(54)));
            } else {
                FrameLayout holder = new FrameLayout(this);
                IconView ic = new IconView(this, icons[i], MUTED);
                holder.addView(ic, new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
                item.addView(holder, new LinearLayout.LayoutParams(dp(52), dp(30)));
                TextView lb = label(names[i], MUTED, 10, false);
                lb.setGravity(Gravity.CENTER);
                lb.setSingleLine(true);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.topMargin = dp(2);
                item.addView(lb, lp);
                navHolders[i] = holder;
                navIcons[i] = ic;
                navLabels[i] = lb;
            }
            pressable(item);
            item.setOnClickListener(v -> showTab(idx));
            row.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
        }
        return navBar;
    }

    /** Tab nav bawah yang disorot untuk halaman tertentu. */
    private int navTabFor(int tab) {
        if (tab == TAB_SETTINGS) return TAB_PROFILE;
        if (tab == TAB_MODEL) return TAB_CHAT;
        return tab;
    }

    private void updateNav() {
        int shown = navTabFor(currentTab);
        for (int i = 0; i < 5; i++) {
            if (i == TAB_CHAT || navIcons[i] == null) continue;
            boolean active = i == shown;
            navIcons[i].setColor(active ? PURPLE_LIGHT : MUTED);
            navLabels[i].setTextColor(active ? PURPLE_LIGHT : MUTED);
            navLabels[i].setTypeface(active ? tfBold : tfRegular);
            navHolders[i].setBackground(active ? rounded(ACCENT_SOFT, 15, 0) : null);
        }
    }

    private void showTab(int tab) {
        if (tab != currentTab) prevTab = currentTab;
        currentTab = tab;
        for (int i = 0; i < pages.length; i++) {
            if (pages[i] != null) pages[i].setVisibility(i == tab ? View.VISIBLE : View.GONE);
        }
        hideKeyboard();
        if (tab == TAB_HOME) renderHome();
        else if (tab == TAB_HISTORY) renderHistory();
        else if (tab == TAB_PROFILE) renderProfile();
        else if (tab == TAB_SETTINGS) renderSettings();
        else if (tab == TAB_MODEL) {
            modelState[0] = "*";
            modelState[2] = currentModel;
            modelState[3] = "";
            if (modelSearch != null) modelSearch.setText("");
            renderModelPage();
        } else if (tab == TAB_CHAT) {
            updateHero();
            scrollToBottom();
        }
        updateNav();
    }

    private void goBack() {
        showTab(prevTab == currentTab ? TAB_HOME : prevTab);
    }

    @Override
    public void onBackPressed() {
        if (currentTab == TAB_SETTINGS) showTab(TAB_PROFILE);
        else if (currentTab != TAB_HOME) showTab(TAB_HOME);
        else super.onBackPressed();
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        View f = getCurrentFocus();
        if (imm != null && f != null) imm.hideSoftInputFromWindow(f.getWindowToken(), 0);
    }

    // ------------------------------------------------------------ Beranda

    private View buildHomePage() {
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        homeBox = new LinearLayout(this);
        homeBox.setOrientation(LinearLayout.VERTICAL);
        homeBox.setPadding(dp(16), dp(10), dp(16), dp(20));
        sv.addView(homeBox, new FrameLayout.LayoutParams(-1, -2));
        return sv;
    }

    private String userName() {
        return prefs.getString("user_name", DEFAULT_NAME).trim();
    }

    private void renderHome() {
        for (ObjectAnimator a : animators) a.cancel();
        animators.clear();
        homeBox.removeAllViews();

        // Bar atas: logo + nama, pil backend aktif
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        homeBox.addView(top, new LinearLayout.LayoutParams(-1, dp(44)));
        top.addView(avatar(34), new LinearLayout.LayoutParams(dp(34), dp(34)));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
        bp.leftMargin = dp(10);
        top.addView(label("ENC AI", TEXT, 17, true), bp);
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        LinearLayout pill = statusPill(backendName(), 0);
        pill.setOnClickListener(v -> showTab(TAB_PROFILE));
        top.addView(pill, new LinearLayout.LayoutParams(-2, -2));

        // Sapaan + orb
        LinearLayout greetRow = new LinearLayout(this);
        greetRow.setOrientation(LinearLayout.HORIZONTAL);
        greetRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams grp = new LinearLayout.LayoutParams(-1, -2);
        grp.topMargin = dp(16);
        homeBox.addView(greetRow, grp);

        LinearLayout greetCol = new LinearLayout(this);
        greetCol.setOrientation(LinearLayout.VERTICAL);
        greetCol.addView(label("Selamat datang,", TEXT, 22, false));
        String name = userName();
        greetCol.addView(label(name.isEmpty() ? "Halo" : name, TEXT, 30, true));
        TextView sub = label("ENC AI siap membantu kapan saja, di mana saja.", MUTED, 14, false);
        LinearLayout.LayoutParams subp = new LinearLayout.LayoutParams(-2, -2);
        subp.topMargin = dp(6);
        greetCol.addView(sub, subp);
        greetRow.addView(greetCol, new LinearLayout.LayoutParams(0, -2, 1));
        greetRow.addView(orb(84, true), new LinearLayout.LayoutParams(dp(84), dp(84)));

        // Kolom tanya cepat
        LinearLayout ask = new LinearLayout(this);
        ask.setOrientation(LinearLayout.HORIZONTAL);
        ask.setGravity(Gravity.CENTER_VERTICAL);
        ask.setPadding(dp(18), dp(6), dp(6), dp(6));
        ask.setBackground(rounded(CARD, 28, CARD_STROKE));
        LinearLayout.LayoutParams askp = new LinearLayout.LayoutParams(-1, -2);
        askp.topMargin = dp(18);
        homeBox.addView(ask, askp);

        homeInput = new EditText(this);
        homeInput.setHint("Tanyakan apa saja…");
        homeInput.setTypeface(tfRegular);
        homeInput.setTextColor(TEXT);
        homeInput.setHintTextColor(MUTED);
        homeInput.setTextSize(15);
        homeInput.setSingleLine(true);
        homeInput.setBackgroundColor(Color.TRANSPARENT);
        homeInput.setImeOptions(EditorInfo.IME_ACTION_SEND);
        homeInput.setOnEditorActionListener((tv, actionId, ev) -> {
            homeSend();
            return true;
        });
        ask.addView(homeInput, new LinearLayout.LayoutParams(0, dp(44), 1));

        FrameLayout go = gradButton(IC_UP);
        go.setOnClickListener(v -> homeSend());
        ask.addView(go, new LinearLayout.LayoutParams(dp(44), dp(44)));

        // Kartu fitur 2 x 2
        List<View> cards = new ArrayList<>();
        cards.add(featureCard(IC_CHAT, "Chat AI", "Obrolan pintar", true, () -> showTab(TAB_CHAT)));
        cards.add(featureCard(IC_CODE, "Bantu Kode", "Tulis & perbaiki kode", true,
                () -> fillPrompt("Bantu aku menulis kode untuk ")));
        cards.add(featureCard(IC_NOTE, "Ringkas Teks", "Cepat & akurat", true,
                () -> fillPrompt("Ringkas teks berikut:\n\n")));
        cards.add(featureCard(IC_TRANSLATE, "Terjemahkan", "Multi-bahasa", true,
                () -> fillPrompt("Terjemahkan teks berikut ke bahasa Inggris:\n\n")));
        LinearLayout.LayoutParams cgp = new LinearLayout.LayoutParams(-1, -2);
        cgp.topMargin = dp(14);
        homeBox.addView(cardGrid(cards, 2), cgp);

        // Banner ganti model
        LinearLayout banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.HORIZONTAL);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setPadding(dp(16), dp(14), dp(14), dp(14));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{PURPLE_DEEP, BLUE});
        bg.setCornerRadius(dp(20));
        banner.setBackground(bg);
        pressable(banner);
        banner.setOnClickListener(v -> showTab(TAB_MODEL));
        banner.addView(new IconView(this, IC_SPARK, Color.WHITE), new LinearLayout.LayoutParams(dp(30), dp(30)));
        LinearLayout bcol = new LinearLayout(this);
        bcol.setOrientation(LinearLayout.VERTICAL);
        bcol.addView(label("Ganti Model AI", Color.WHITE, 15, true));
        bcol.addView(label("Pilih dari " + allModelIds.size() + " model yang tersedia", Color.argb(215, 255, 255, 255), 12, false));
        LinearLayout.LayoutParams bcp = new LinearLayout.LayoutParams(0, -2, 1);
        bcp.leftMargin = dp(14);
        banner.addView(bcol, bcp);
        banner.addView(new IconView(this, IC_CHEV, Color.WHITE), new LinearLayout.LayoutParams(dp(20), dp(20)));
        LinearLayout.LayoutParams bnp = new LinearLayout.LayoutParams(-1, -2);
        bnp.topMargin = dp(10);
        bnp.leftMargin = dp(4);
        bnp.rightMargin = dp(4);
        homeBox.addView(banner, bnp);

        // Eksplorasi
        LinearLayout exHead = new LinearLayout(this);
        exHead.setOrientation(LinearLayout.HORIZONTAL);
        exHead.setGravity(Gravity.CENTER_VERTICAL);
        exHead.addView(label("Eksplorasi", TEXT, 17, true), new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout all = iconLabel(IC_CHEV, "Lihat semua", PURPLE_LIGHT, 12, false);
        all.setPadding(dp(8), dp(6), dp(4), dp(6));
        pressable(all);
        all.setOnClickListener(v -> showTab(TAB_TOOLS));
        exHead.addView(all, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams ehp = new LinearLayout.LayoutParams(-1, -2);
        ehp.topMargin = dp(20);
        ehp.leftMargin = dp(4);
        homeBox.addView(exHead, ehp);

        List<View> ex = new ArrayList<>();
        ex.add(featureCard(IC_DOC, "Analisis Dokumen", "Lampirkan PDF atau file", true, () -> runTool("@doc")));
        ex.add(featureCard(IC_CAMERA, "Tanya dari Foto", "Ambil foto lalu tanya", true, () -> {
            showTab(TAB_CHAT);
            takePhoto();
        }));
        LinearLayout.LayoutParams exp = new LinearLayout.LayoutParams(-1, -2);
        exp.topMargin = dp(4);
        homeBox.addView(cardGrid(ex, 2), exp);
    }

    private void homeSend() {
        String t = homeInput == null ? "" : homeInput.getText().toString().trim();
        if (t.isEmpty()) {
            showTab(TAB_CHAT);
            return;
        }
        if (busy) {
            toast("Tunggu jawaban selesai dulu");
            return;
        }
        homeInput.setText("");
        input.setText(t);
        showTab(TAB_CHAT);
        sendMessage();
    }

    // -------------------------------------------------------------- Tools

    private View buildToolsPage() {
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(14), dp(16), dp(20));
        sv.addView(box, new FrameLayout.LayoutParams(-1, -2));

        box.addView(label("Tools AI", TEXT, 26, true));
        TextView sub = label("Berbagai alat AI dalam satu aplikasi", MUTED, 14, false);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
        sp.topMargin = dp(2);
        box.addView(sub, sp);

        String[][] tools = {
                {"Ide Kreatif", "Brainstorm", "Beri aku ide kreatif tentang "},
                {"Kode", "Tulis & perbaiki", "Bantu aku menulis kode untuk "},
                {"Debug", "Cari bug", "Cari dan perbaiki bug pada kode berikut:\n\n"},
                {"Terjemah", "Multi-bahasa", "Terjemahkan teks berikut ke bahasa Inggris:\n\n"},
                {"Ringkas", "Cepat & akurat", "Ringkas teks berikut:\n\n"},
                {"Penulis", "Artikel & esai", "Tulis artikel tentang "},
                {"Dokumen", "Analisis file", "@doc"},
                {"OCR", "Scan teks", "@ocr"},
                {"Email", "Draf profesional", "Tuliskan email profesional tentang "}
        };
        int[] toolIcons = {IC_BULB, IC_CODE, IC_BUG, IC_TRANSLATE, IC_NOTE, IC_PEN, IC_DOC, IC_SCAN, IC_MAIL};
        List<View> cards = new ArrayList<>();
        for (int i = 0; i < tools.length; i++) {
            final String[] t = tools[i];
            cards.add(featureCard(toolIcons[i], t[0], t[1], true, () -> runTool(t[2])));
        }
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-1, -2);
        gp.topMargin = dp(16);
        box.addView(cardGrid(cards, 3), gp);

        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-2, -2);
        hp.topMargin = dp(22);
        hp.leftMargin = dp(4);
        box.addView(label("Fitur Unggulan", TEXT, 17, true), hp);

        LinearLayout banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.HORIZONTAL);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setPadding(dp(16), dp(14), dp(14), dp(14));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{PURPLE_DEEP, BLUE});
        bg.setCornerRadius(dp(20));
        banner.setBackground(bg);
        pressable(banner);
        banner.setOnClickListener(v -> fillPrompt("Bantu aku membuat prompt terbaik untuk tujuan berikut:\n\n"));
        banner.addView(new IconView(this, IC_WAND, Color.WHITE), new LinearLayout.LayoutParams(dp(30), dp(30)));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(label("Pembuat Prompt", Color.WHITE, 15, true));
        col.addView(label("Buat prompt terbaik untuk hasil maksimal", Color.argb(215, 255, 255, 255), 12, false));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1);
        cp.leftMargin = dp(14);
        banner.addView(col, cp);
        banner.addView(new IconView(this, IC_CHEV, Color.WHITE), new LinearLayout.LayoutParams(dp(20), dp(20)));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.topMargin = dp(10);
        bp.leftMargin = dp(4);
        bp.rightMargin = dp(4);
        box.addView(banner, bp);
        return sv;
    }

    private void runTool(String p) {
        if ("@doc".equals(p)) {
            showTab(TAB_CHAT);
            prefillInput("Analisis dokumen terlampir dan rangkum poin pentingnya.");
            showAttachMenu();
        } else if ("@ocr".equals(p)) {
            showTab(TAB_CHAT);
            prefillInput("Tulis ulang semua teks yang terlihat pada gambar ini.");
            showAttachMenu();
        } else {
            fillPrompt(p);
        }
    }

    private void prefillInput(String text) {
        input.setText(text);
        input.setSelection(text.length());
    }

    private void fillPrompt(String prompt) {
        showTab(TAB_CHAT);
        prefillInput(prompt);
        input.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
    }

    // --------------------------------------------------------------- Chat

    private View buildChatPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);

        // Header: avatar, nama + model aktif, tombol chat baru
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(10), dp(16), dp(10));
        page.addView(header, new LinearLayout.LayoutParams(-1, -2));

        header.addView(avatar(42), new LinearLayout.LayoutParams(dp(42), dp(42)));

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        titleCol.setPadding(dp(12), dp(2), dp(8), dp(2));
        titleCol.addView(label("ENC AI", TEXT, 17, true));
        LinearLayout subRow = new LinearLayout(this);
        subRow.setOrientation(LinearLayout.HORIZONTAL);
        subRow.setGravity(Gravity.CENTER_VERTICAL);
        View dot = new View(this);
        GradientDrawable dd = new GradientDrawable();
        dd.setShape(GradientDrawable.OVAL);
        dd.setColor(GREEN);
        dot.setBackground(dd);
        subRow.addView(dot, new LinearLayout.LayoutParams(dp(8), dp(8)));
        modelPillText = label("", MUTED, 12, false);
        modelPillText.setSingleLine(true);
        modelPillText.setEllipsize(TextUtils.TruncateAt.END);
        modelPillText.setMaxWidth(dp(190));
        LinearLayout.LayoutParams mtp = new LinearLayout.LayoutParams(-2, -2);
        mtp.leftMargin = dp(6);
        subRow.addView(modelPillText, mtp);
        LinearLayout.LayoutParams cap = new LinearLayout.LayoutParams(dp(14), dp(14));
        cap.leftMargin = dp(4);
        subRow.addView(new IconView(this, IC_CHEVDOWN, MUTED), cap);
        titleCol.addView(subRow);
        pressable(titleCol);
        titleCol.setOnClickListener(v -> showTab(TAB_MODEL));
        header.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1));

        View newChatBtn = iconButton(IC_PLUS, v -> newChat());
        header.addView(newChatBtn, new LinearLayout.LayoutParams(dp(42), dp(42)));

        // Area tengah: keadaan kosong atau daftar chat
        FrameLayout chatArea = new FrameLayout(this);
        page.addView(chatArea, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout empty = new LinearLayout(this);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(28), 0, dp(28), dp(24));
        empty.addView(avatar(76), new LinearLayout.LayoutParams(dp(76), dp(76)));
        TextView et = label("Mulai percakapan", TEXT, 21, true);
        et.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams etp = new LinearLayout.LayoutParams(-2, -2);
        etp.topMargin = dp(16);
        empty.addView(et, etp);
        TextView eh = label("Tanya apa saja, cari ide, atau lampirkan gambar dan file. Ketuk nama model di atas untuk menggantinya.", MUTED, 13, false);
        eh.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ehp = new LinearLayout.LayoutParams(-2, -2);
        ehp.topMargin = dp(6);
        empty.addView(eh, ehp);
        emptyChat = empty;
        chatArea.addView(empty, new FrameLayout.LayoutParams(-1, -1));

        chatScroll = new ScrollView(this);
        chatScroll.setFillViewport(false);
        chatScroll.setClipToPadding(false);
        chatScroll.setVerticalScrollBarEnabled(false);
        chatArea.addView(chatScroll, new FrameLayout.LayoutParams(-1, -1));

        messages = new LinearLayout(this);
        messages.setOrientation(LinearLayout.VERTICAL);
        messages.setPadding(dp(16), dp(6), dp(16), dp(12));
        chatScroll.addView(messages);

        // Bar lampiran
        attachScroll = new HorizontalScrollView(this);
        attachScroll.setHorizontalScrollBarEnabled(false);
        attachScroll.setVisibility(View.GONE);
        attachBar = new LinearLayout(this);
        attachBar.setOrientation(LinearLayout.HORIZONTAL);
        attachScroll.addView(attachBar);
        LinearLayout.LayoutParams asp = new LinearLayout.LayoutParams(-1, -2);
        asp.topMargin = dp(8);
        asp.leftMargin = dp(16);
        asp.rightMargin = dp(16);
        page.addView(attachScroll, asp);

        // Composer berbentuk pil
        LinearLayout composer = new LinearLayout(this);
        composer.setOrientation(LinearLayout.HORIZONTAL);
        composer.setGravity(Gravity.BOTTOM);
        composer.setPadding(dp(6), dp(6), dp(6), dp(6));
        composer.setBackground(rounded(CARD, 28, CARD_STROKE));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.topMargin = dp(8);
        cp.leftMargin = dp(12);
        cp.rightMargin = dp(12);
        page.addView(composer, cp);

        View attach = iconButton(IC_CLIP, v -> showAttachMenu());
        composer.addView(attach, new LinearLayout.LayoutParams(dp(44), dp(44)));

        input = new EditText(this);
        input.setTypeface(tfRegular);
        input.setTextColor(TEXT);
        input.setHintTextColor(MUTED);
        input.setHint("Ketik pesan…");
        input.setTextSize(15);
        input.setPadding(dp(12), dp(10), dp(8), dp(10));
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setMinHeight(dp(44));
        input.setMaxLines(5);
        input.setSingleLine(false);
        composer.addView(input, new LinearLayout.LayoutParams(0, -2, 1));

        sendButton = gradButton(IC_UP);
        sendButton.setOnClickListener(v -> sendMessage());
        LinearLayout.LayoutParams sendp = new LinearLayout.LayoutParams(dp(44), dp(44));
        sendp.leftMargin = dp(4);
        composer.addView(sendButton, sendp);

        status = label("ENC AI • Siap terhubung ke " + backendName(), MUTED, 10, false);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(6), 0, dp(6));
        page.addView(status, new LinearLayout.LayoutParams(-1, -2));
        return page;
    }

    private void updateHero() {
        if (emptyChat == null || chatScroll == null) return;
        boolean empty = conversation.length() == 0 && messages.getChildCount() == 0;
        emptyChat.setVisibility(empty ? View.VISIBLE : View.GONE);
        chatScroll.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    // ------------------------------------------------------------ Riwayat

    private View buildHistoryPage() {
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        historyBox = new LinearLayout(this);
        historyBox.setOrientation(LinearLayout.VERTICAL);
        historyBox.setPadding(dp(16), dp(14), dp(16), dp(20));
        sv.addView(historyBox, new FrameLayout.LayoutParams(-1, -2));
        return sv;
    }

    private void renderHistory() {
        historyBox.removeAllViews();

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(label("Riwayat", TEXT, 26, true));

        // Kumpulkan pasangan pertanyaan -> jawaban
        List<String[]> items = new ArrayList<>();
        for (int i = 0; i < conversation.length(); i++) {
            JSONObject m = conversation.optJSONObject(i);
            if (m == null || !"user".equals(m.optString("role"))) continue;
            String q = m.optString("content", "");
            int k = q.indexOf("\n\n[Lampiran:");
            if (k > 0) q = q.substring(0, k);
            String a = "";
            JSONObject next = conversation.optJSONObject(i + 1);
            if (next != null && "assistant".equals(next.optString("role"))) {
                a = next.optString("content", "").replaceAll("[*#`>_~|]", "").replaceAll("\\s+", " ").trim();
            }
            items.add(new String[]{q.trim(), a});
        }
        Collections.reverse(items); // terbaru di atas

        titles.addView(label(items.size() + " percakapan tersimpan", MUTED, 13, false));
        head.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(iconButton(IC_TRASH, v -> confirmClearHistory()), new LinearLayout.LayoutParams(dp(42), dp(42)));
        historyBox.addView(head, new LinearLayout.LayoutParams(-1, -2));

        if (items.isEmpty()) {
            LinearLayout empty = new LinearLayout(this);
            empty.setOrientation(LinearLayout.VERTICAL);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(24), dp(80), dp(24), dp(24));
            empty.addView(new IconView(this, IC_HISTORY, MUTED), new LinearLayout.LayoutParams(dp(52), dp(52)));
            TextView t = label("Belum ada riwayat", TEXT, 17, true);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
            tp.topMargin = dp(14);
            empty.addView(t, tp);
            TextView h = label("Percakapanmu akan muncul di sini.", MUTED, 13, false);
            h.setGravity(Gravity.CENTER);
            empty.addView(h);
            historyBox.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        } else {
            for (String[] it : items) {
                LinearLayout card = new LinearLayout(this);
                card.setOrientation(LinearLayout.HORIZONTAL);
                card.setGravity(Gravity.CENTER_VERTICAL);
                card.setPadding(dp(12), dp(12), dp(12), dp(12));
                card.setBackground(rounded(CARD, 16, CARD_STROKE));
                pressable(card);
                card.setOnClickListener(v -> showTab(TAB_CHAT));

                FrameLayout ic = new FrameLayout(this);
                ic.setBackground(rounded(ACCENT_SOFT, 12, 0));
                ic.addView(new IconView(this, IC_CHAT, PURPLE_LIGHT), new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
                card.addView(ic, new LinearLayout.LayoutParams(dp(40), dp(40)));

                LinearLayout col = new LinearLayout(this);
                col.setOrientation(LinearLayout.VERTICAL);
                TextView q = label(it[0].isEmpty() ? "(tanpa teks)" : it[0], TEXT, 14, true);
                q.setMaxLines(2);
                q.setEllipsize(TextUtils.TruncateAt.END);
                col.addView(q);
                if (!it[1].isEmpty()) {
                    TextView a = label(it[1], MUTED, 12, false);
                    a.setMaxLines(2);
                    a.setEllipsize(TextUtils.TruncateAt.END);
                    col.addView(a);
                }
                LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1);
                cl.leftMargin = dp(12);
                cl.rightMargin = dp(6);
                card.addView(col, cl);
                card.addView(new IconView(this, IC_CHEV, MUTED), new LinearLayout.LayoutParams(dp(18), dp(18)));

                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.topMargin = dp(10);
                historyBox.addView(card, lp);
            }
        }

        LinearLayout fresh = iconLabel(IC_PLUS, "Chat Baru", Color.WHITE, 15, true);
        fresh.setGravity(Gravity.CENTER);
        fresh.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable fb = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{PURPLE, PURPLE_DEEP});
        fb.setCornerRadius(dp(26));
        fresh.setBackground(fb);
        pressable(fresh);
        fresh.setOnClickListener(v -> {
            if (busy) { toast("Tunggu jawaban selesai dulu"); return; }
            newChat();
            showTab(TAB_CHAT);
        });
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, -2);
        fp.topMargin = dp(18);
        historyBox.addView(fresh, fp);
    }

    // ------------------------------------------------------------- Profil

    private View buildProfilePage() {
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        profileBox = new LinearLayout(this);
        profileBox.setOrientation(LinearLayout.VERTICAL);
        profileBox.setPadding(dp(16), dp(10), dp(16), dp(24));
        sv.addView(profileBox, new FrameLayout.LayoutParams(-1, -2));
        return sv;
    }

    private View statColumn(String number, String caption) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER);
        c.setPadding(0, dp(14), 0, dp(14));
        TextView n = label(number, TEXT, 20, true);
        n.setGravity(Gravity.CENTER);
        c.addView(n);
        TextView t = label(caption, MUTED, 11, false);
        t.setGravity(Gravity.CENTER);
        c.addView(t);
        return c;
    }

    private void renderProfile() {
        profileBox.removeAllViews();

        // Hero: avatar + nama + status
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER_HORIZONTAL);
        hero.setPadding(dp(16), dp(26), dp(16), dp(20));
        GradientDrawable hg = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{ACCENT_SOFT, Color.TRANSPARENT});
        hg.setCornerRadius(dp(24));
        hero.setBackground(hg);

        String nm = userName();
        String shown = nm.isEmpty() ? "Pengguna" : nm;

        FrameLayout av = new FrameLayout(this);
        GradientDrawable ag = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{PURPLE, PURPLE_DEEP});
        ag.setShape(GradientDrawable.OVAL);
        ag.setStroke(dp(3), PURPLE_LIGHT);
        av.setBackground(ag);
        av.addView(label(shown.substring(0, 1).toUpperCase(Locale.ROOT), Color.WHITE, 34, true),
                new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        hero.addView(av, new LinearLayout.LayoutParams(dp(88), dp(88)));

        TextView name = label(shown, TEXT, 20, true);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-2, -2);
        np.topMargin = dp(12);
        hero.addView(name, np);

        // Nama model tidak ditampilkan lagi di sini (sudah ada di header Chat & Pengaturan).
        // Pil di bawah hanya menunjukkan backend yang aktif.
        LinearLayout pill = statusPill(backendName() + " aktif", PURPLE);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-2, -2);
        pp.topMargin = dp(10);
        hero.addView(pill, pp);
        profileBox.addView(hero, new LinearLayout.LayoutParams(-1, -2));

        // Statistik
        int userMsgs = 0, aiMsgs = 0;
        for (int i = 0; i < conversation.length(); i++) {
            JSONObject m = conversation.optJSONObject(i);
            if (m == null) continue;
            if ("user".equals(m.optString("role"))) userMsgs++;
            else if ("assistant".equals(m.optString("role"))) aiMsgs++;
        }
        long first = prefs.getLong("first_run", 0);
        if (first == 0) {
            first = System.currentTimeMillis();
            prefs.edit().putLong("first_run", first).apply();
        }
        long days = (System.currentTimeMillis() - first) / 86400000L + 1;

        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        stats.setBackground(rounded(CARD, 18, CARD_STROKE));
        stats.addView(statColumn(String.valueOf(userMsgs), "Pertanyaan"), new LinearLayout.LayoutParams(0, -2, 1));
        View d1 = new View(this);
        d1.setBackgroundColor(CARD_STROKE);
        stats.addView(d1, new LinearLayout.LayoutParams(dp(1), -1));
        stats.addView(statColumn(String.valueOf(aiMsgs), "Jawaban"), new LinearLayout.LayoutParams(0, -2, 1));
        View d2 = new View(this);
        d2.setBackgroundColor(CARD_STROKE);
        stats.addView(d2, new LinearLayout.LayoutParams(dp(1), -1));
        stats.addView(statColumn(String.valueOf(days), "Hari Aktif"), new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(-1, -2);
        stp.topMargin = dp(16);
        profileBox.addView(stats, stp);

        // Menu
        LinearLayout menu = settingsGroup(profileBox);
        ((LinearLayout.LayoutParams) menu.getLayoutParams()).topMargin = dp(16);
        settingsRow(menu, IC_GEAR, "Pengaturan", "Koneksi, server, tampilan", null, () -> showTab(TAB_SETTINGS));
        settingsRow(menu, IC_HISTORY, "Riwayat Chat", userMsgs + " percakapan tersimpan", null, () -> showTab(TAB_HISTORY));
        settingsRow(menu, IC_USER, "Nama Panggilan", nm.isEmpty() ? "Belum diisi" : nm, null, this::showNameDialog);
        settingsRow(menu, IC_MOON, "Mode Gelap", darkMode ? "Aktif" : "Mati",
                toggle(darkMode, on -> {
                    prefs.edit().putBoolean("dark_mode", on).putInt("open_tab", TAB_PROFILE).apply();
                    recreate();
                }), null);
        settingsRow(menu, IC_TRASH, "Hapus Riwayat Chat", "Hapus percakapan yang tersimpan", null, this::confirmClearHistory);
    }

    // ---------------------------------------------------------- Pengaturan

    private View buildSettingsPage() {
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        settingsBox = new LinearLayout(this);
        settingsBox.setOrientation(LinearLayout.VERTICAL);
        settingsBox.setPadding(dp(16), dp(10), dp(16), dp(24));
        sv.addView(settingsBox, new FrameLayout.LayoutParams(-1, -2));
        return sv;
    }

    private void renderSettings() {
        settingsBox.removeAllViews();
        settingsBox.addView(pageHeader("Pengaturan", () -> showTab(TAB_PROFILE)), new LinearLayout.LayoutParams(-1, dp(46)));

        addSectionTitle(settingsBox, "AI & Aplikasi");
        LinearLayout g1 = settingsGroup(settingsBox);
        String url = getServerUrl();
        settingsRow(g1, IC_PLUG, "Koneksi & Server",
                backendName() + " • " + (url.isEmpty() ? "alamat belum diisi" : url), null, this::showSettings);
        settingsRow(g1, IC_SPARK, "Model Default", currentModel, null, () -> showTab(TAB_MODEL));

        addSectionTitle(settingsBox, "Tampilan");
        LinearLayout g2 = settingsGroup(settingsBox);
        settingsRow(g2, IC_MOON, "Mode Gelap", darkMode ? "Aktif" : "Mati (tema terang)",
                toggle(darkMode, on -> {
                    prefs.edit().putBoolean("dark_mode", on).putInt("open_tab", TAB_SETTINGS).apply();
                    recreate();
                }), null);

        addSectionTitle(settingsBox, "Profil");
        LinearLayout g3 = settingsGroup(settingsBox);
        String nm = userName();
        settingsRow(g3, IC_USER, "Nama Panggilan", nm.isEmpty() ? "Belum diisi" : nm, null, this::showNameDialog);

        addSectionTitle(settingsBox, "Privasi");
        LinearLayout g4 = settingsGroup(settingsBox);
        settingsRow(g4, IC_TRASH, "Hapus Riwayat Chat", "Hapus percakapan yang tersimpan", null, this::confirmClearHistory);

        TextView foot = label("ENC AI • tema " + (darkMode ? "gelap" : "terang"), MUTED, 11, false);
        foot.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-1, -2);
        fp.topMargin = dp(22);
        settingsBox.addView(foot, fp);
    }

    private void addSectionTitle(LinearLayout parent, String title) {
        TextView t = label(title, TEXT, 16, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(18);
        lp.bottomMargin = dp(8);
        lp.leftMargin = dp(4);
        parent.addView(t, lp);
    }

    private LinearLayout settingsGroup(LinearLayout parent) {
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.VERTICAL);
        g.setBackground(rounded(CARD, 18, CARD_STROKE));
        parent.addView(g, new LinearLayout.LayoutParams(-1, -2));
        return g;
    }

    private void settingsRow(LinearLayout group, int icon, String title, String sub, View trailing, final Runnable action) {
        if (group.getChildCount() > 0) {
            View div = new View(this);
            div.setBackgroundColor(CARD_STROKE);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, dp(1));
            dl.leftMargin = dp(66);
            group.addView(div, dl);
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12));

        FrameLayout ic = new FrameLayout(this);
        ic.setBackground(rounded(ACCENT_SOFT, 12, 0));
        ic.addView(new IconView(this, icon, PURPLE_LIGHT), new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
        row.addView(ic, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(label(title, TEXT, 15, true));
        TextView s = label(sub, MUTED, 12, false);
        s.setSingleLine(true);
        s.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(s);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1);
        cl.leftMargin = dp(14);
        cl.rightMargin = dp(8);
        row.addView(col, cl);

        if (trailing != null) {
            row.addView(trailing);
        } else {
            row.addView(new IconView(this, IC_CHEV, MUTED), new LinearLayout.LayoutParams(dp(18), dp(18)));
        }
        if (action != null) {
            pressable(row);
            row.setOnClickListener(v -> action.run());
        }
        group.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    /** Sakelar geser sederhana (tanpa resource). */
    private View toggle(final boolean on, final BoolCb cb) {
        FrameLayout track = new FrameLayout(this);
        int off = darkMode ? Color.rgb(66, 80, 120) : Color.rgb(200, 210, 228);
        track.setBackground(rounded(on ? PURPLE : off, 14, 0));
        View knob = new View(this);
        GradientDrawable kd = new GradientDrawable();
        kd.setShape(GradientDrawable.OVAL);
        kd.setColor(Color.WHITE);
        knob.setBackground(kd);
        FrameLayout.LayoutParams kp = new FrameLayout.LayoutParams(dp(22), dp(22));
        kp.gravity = Gravity.CENTER_VERTICAL | (on ? Gravity.RIGHT : Gravity.LEFT);
        kp.leftMargin = dp(3);
        kp.rightMargin = dp(3);
        track.addView(knob, kp);
        track.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(28)));
        pressable(track);
        track.setOnClickListener(v -> cb.run(!on));
        return track;
    }

    private void showStyled(AlertDialog dlg) {
        dlg.show();
        Window w = dlg.getWindow();
        if (w != null) w.setBackgroundDrawable(rounded(SHEET, 24, CARD_STROKE));
        Button pos = dlg.getButton(AlertDialog.BUTTON_POSITIVE);
        if (pos != null) { pos.setTextColor(PURPLE_LIGHT); pos.setTypeface(tfBold); }
        Button neu = dlg.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (neu != null) { neu.setTextColor(PURPLE_LIGHT); neu.setTypeface(tfBold); }
        Button neg = dlg.getButton(AlertDialog.BUTTON_NEGATIVE);
        if (neg != null) { neg.setTextColor(MUTED); neg.setTypeface(tfRegular); }
    }

    private void showNameDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(20), dp(22), dp(4));
        box.addView(label("Nama panggilan", TEXT, 18, true));
        final EditText e = styledInput("Nama untuk sapaan di Beranda", userName());
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(-1, -2);
        ep.topMargin = dp(14);
        box.addView(e, ep);
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setView(box)
                .setNegativeButton("Batal", null)
                .setPositiveButton("Simpan", (d, w) -> {
                    prefs.edit().putString("user_name", e.getText().toString().trim()).apply();
                    renderSettings();
                    renderProfile();
                })
                .create();
        showStyled(dlg);
    }

    private void confirmClearHistory() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(20), dp(22), dp(4));
        box.addView(label("Hapus riwayat chat?", TEXT, 18, true));
        TextView msg = label("Semua percakapan dan file hasil Hermes yang tersimpan di HP ini akan dihapus.", MUTED, 14, false);
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-2, -2);
        mp.topMargin = dp(10);
        box.addView(msg, mp);
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setView(box)
                .setNegativeButton("Batal", null)
                .setPositiveButton("Hapus", (d, w) -> {
                    if (busy) {
                        toast("Tunggu jawaban selesai dulu");
                        return;
                    }
                    newChat();
                    toast("Riwayat chat dihapus");
                })
                .create();
        showStyled(dlg);
    }

    // ------------------------------------------------ Komponen bersama

    /** Avatar bulat bergradien dengan bintang putih (logo ENC AI). */
    private FrameLayout avatar(int sizeDp) {
        FrameLayout f = new FrameLayout(this);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{PURPLE, PURPLE_DEEP});
        g.setShape(GradientDrawable.OVAL);
        f.setBackground(g);
        int in = Math.max(10, (int) (sizeDp * 0.52f));
        f.addView(new IconView(this, IC_SPARK, Color.WHITE), new FrameLayout.LayoutParams(dp(in), dp(in), Gravity.CENTER));
        return f;
    }

    private View iconButton(int icon, View.OnClickListener l) {
        FrameLayout f = new FrameLayout(this);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(CARD);
        d.setStroke(dp(1), CARD_STROKE);
        f.setBackground(d);
        f.addView(new IconView(this, icon, TEXT), new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
        pressable(f);
        f.setOnClickListener(l);
        return f;
    }

    /** Ikon yang bisa diketuk (area sentuh lebih besar dari ikonnya). */
    private View iconTap(int icon, int color, int iconDp, View.OnClickListener l) {
        FrameLayout f = new FrameLayout(this);
        f.setPadding(dp(10), dp(8), dp(10), dp(8));
        f.addView(new IconView(this, icon, color), new FrameLayout.LayoutParams(dp(iconDp), dp(iconDp), Gravity.CENTER));
        pressable(f);
        f.setOnClickListener(l);
        return f;
    }

    /** Ikon + teks dalam satu baris. */
    private LinearLayout iconLabel(int icon, String text, int color, int textSp, boolean bold) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        int s = Math.round(textSp * 1.25f);
        r.addView(new IconView(this, icon, color), new LinearLayout.LayoutParams(dp(s), dp(s)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(6);
        r.addView(label(text, color, textSp, bold), lp);
        return r;
    }

    /** Tombol bulat bergradien dengan ikon putih (kirim). */
    private FrameLayout gradButton(int icon) {
        FrameLayout f = new FrameLayout(this);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{PURPLE, PURPLE_DEEP});
        g.setShape(GradientDrawable.OVAL);
        f.setBackground(g);
        f.addView(new IconView(this, icon, Color.WHITE), new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        pressable(f);
        return f;
    }

    /** Pil status dengan titik hijau, tanpa karakter teks. */
    private LinearLayout statusPill(String text, int stroke) {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.HORIZONTAL);
        p.setGravity(Gravity.CENTER_VERTICAL);
        p.setPadding(dp(12), dp(7), dp(12), dp(7));
        p.setBackground(rounded(ACCENT_SOFT, 16, stroke));
        View dot = new View(this);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(GREEN);
        dot.setBackground(d);
        p.addView(dot, new LinearLayout.LayoutParams(dp(7), dp(7)));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = dp(7);
        p.addView(label(text, PURPLE_LIGHT, 12, true), lp);
        pressable(p);
        return p;
    }

    private LinearLayout pageHeader(String title, final Runnable back) {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.addView(iconButton(IC_BACK, v -> back.run()), new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.leftMargin = dp(14);
        h.addView(label(title, TEXT, 20, true), tp);
        return h;
    }

    private LinearLayout pageHeader(String title) {
        return pageHeader(title, this::goBack);
    }

    private LinearLayout featureCard(int icon, String title, String sub, boolean compact, final Runnable action) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(8), dp(14), dp(8), dp(14));
        card.setBackground(rounded(CARD, 18, CARD_STROKE));
        pressable(card);
        card.setOnClickListener(v -> action.run());

        FrameLayout ic = new FrameLayout(this);
        ic.setBackground(rounded(ACCENT_SOFT, 14, 0));
        ic.addView(new IconView(this, icon, PURPLE_LIGHT), new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER));
        card.addView(ic, new LinearLayout.LayoutParams(dp(44), dp(44)));

        TextView t = label(title, TEXT, 13, true);
        t.setGravity(Gravity.CENTER);
        t.setMaxLines(2);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.topMargin = dp(8);
        card.addView(t, tp);

        TextView s = label(sub, MUTED, 10, false);
        s.setGravity(Gravity.CENTER);
        s.setMaxLines(2);
        card.addView(s, new LinearLayout.LayoutParams(-2, -2));
        return card;
    }

    private LinearLayout cardGrid(List<View> cards, int cols) {
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < cards.size(); i += cols) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < cols; c++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
                lp.setMargins(dp(4), dp(4), dp(4), dp(4));
                if (i + c < cards.size()) row.addView(cards.get(i + c), lp);
                else row.addView(new View(this), lp);
            }
            g.addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
        return g;
    }

    /** Ikon vektor sederhana (digambar dengan Canvas, bisa diwarnai sesuai tema). */
    private class IconView extends View {
        private final int type;
        private int color;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF rect = new RectF();

        IconView(Context c, int type, int color) {
            super(c);
            this.type = type;
            this.color = color;
        }

        void setColor(int c) {
            color = c;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas cv) {
            float s = Math.min(getWidth(), getHeight()) / 24f;
            if (s <= 0) return;
            cv.save();
            cv.translate((getWidth() - 24 * s) / 2f, (getHeight() - 24 * s) / 2f);
            cv.scale(s, s);
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1.9f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            path.reset();
            switch (type) {
                case IC_HOME:
                    path.moveTo(3, 11); path.lineTo(12, 3.5f); path.lineTo(21, 11);
                    path.moveTo(5.5f, 9.5f); path.lineTo(5.5f, 20); path.lineTo(18.5f, 20); path.lineTo(18.5f, 9.5f);
                    cv.drawPath(path, paint);
                    break;
                case IC_GRID: {
                    float[][] g = {{4, 4}, {14, 4}, {4, 14}, {14, 14}};
                    for (float[] p : g) {
                        rect.set(p[0], p[1], p[0] + 6, p[1] + 6);
                        cv.drawRoundRect(rect, 1.8f, 1.8f, paint);
                    }
                    break;
                }
                case IC_SPARK:
                    paint.setStyle(Paint.Style.FILL);
                    path.moveTo(12, 2);
                    path.cubicTo(12.8f, 8.5f, 15.5f, 11.2f, 22, 12);
                    path.cubicTo(15.5f, 12.8f, 12.8f, 15.5f, 12, 22);
                    path.cubicTo(11.2f, 15.5f, 8.5f, 12.8f, 2, 12);
                    path.cubicTo(8.5f, 11.2f, 11.2f, 8.5f, 12, 2);
                    path.close();
                    cv.drawPath(path, paint);
                    break;
                case IC_MODEL: {
                    rect.set(7, 7, 17, 17);
                    cv.drawRoundRect(rect, 2f, 2f, paint);
                    float[] pins = {10, 14};
                    for (float p : pins) {
                        path.moveTo(p, 3); path.lineTo(p, 7);
                        path.moveTo(p, 17); path.lineTo(p, 21);
                        path.moveTo(3, p); path.lineTo(7, p);
                        path.moveTo(17, p); path.lineTo(21, p);
                    }
                    cv.drawPath(path, paint);
                    break;
                }
                case IC_GEAR:
                    cv.drawCircle(12, 12, 3.2f, paint);
                    cv.drawCircle(12, 12, 7f, paint);
                    for (int k = 0; k < 8; k++) {
                        double a = Math.toRadians(k * 45);
                        float cs = (float) Math.cos(a), sn = (float) Math.sin(a);
                        path.moveTo(12 + 7f * cs, 12 + 7f * sn);
                        path.lineTo(12 + 10f * cs, 12 + 10f * sn);
                    }
                    cv.drawPath(path, paint);
                    break;
                case IC_BACK:
                    path.moveTo(19, 12); path.lineTo(5, 12);
                    path.moveTo(11, 6); path.lineTo(5, 12); path.lineTo(11, 18);
                    cv.drawPath(path, paint);
                    break;
                case IC_CHEV:
                    path.moveTo(9, 5.5f); path.lineTo(15.5f, 12); path.lineTo(9, 18.5f);
                    cv.drawPath(path, paint);
                    break;
                case IC_HISTORY:
                    cv.drawCircle(12, 12, 9, paint);
                    path.moveTo(12, 7); path.lineTo(12, 12); path.lineTo(15.5f, 14);
                    cv.drawPath(path, paint);
                    break;
                case IC_USER:
                    cv.drawCircle(12, 8, 4, paint);
                    path.moveTo(4, 21);
                    path.cubicTo(4, 16.5f, 7.5f, 14, 12, 14);
                    path.cubicTo(16.5f, 14, 20, 16.5f, 20, 21);
                    cv.drawPath(path, paint);
                    break;
                case IC_CHAT:
                    rect.set(3, 4, 21, 17);
                    cv.drawRoundRect(rect, 3.5f, 3.5f, paint);
                    path.moveTo(8, 17); path.lineTo(8, 21); path.lineTo(12.5f, 17);
                    cv.drawPath(path, paint);
                    break;
                case IC_CODE:
                    path.moveTo(8, 7); path.lineTo(3, 12); path.lineTo(8, 17);
                    path.moveTo(16, 7); path.lineTo(21, 12); path.lineTo(16, 17);
                    path.moveTo(13.5f, 5); path.lineTo(10.5f, 19);
                    cv.drawPath(path, paint);
                    break;
                case IC_NOTE:
                    rect.set(5, 3, 19, 21);
                    cv.drawRoundRect(rect, 2.5f, 2.5f, paint);
                    path.moveTo(9, 9); path.lineTo(15, 9);
                    path.moveTo(9, 13); path.lineTo(15, 13);
                    path.moveTo(9, 17); path.lineTo(12.5f, 17);
                    cv.drawPath(path, paint);
                    break;
                case IC_TRANSLATE:
                    cv.drawCircle(12, 12, 9, paint);
                    path.moveTo(3, 12); path.lineTo(21, 12);
                    cv.drawPath(path, paint);
                    rect.set(8, 3, 16, 21);
                    cv.drawOval(rect, paint);
                    break;
                case IC_BULB:
                    path.moveTo(9, 15);
                    path.cubicTo(5.5f, 12.5f, 6, 4, 12, 4);
                    path.cubicTo(18, 4, 18.5f, 12.5f, 15, 15);
                    path.close();
                    path.moveTo(9.5f, 18.5f); path.lineTo(14.5f, 18.5f);
                    path.moveTo(10.5f, 21.5f); path.lineTo(13.5f, 21.5f);
                    cv.drawPath(path, paint);
                    break;
                case IC_BUG:
                    rect.set(8, 8, 16, 20);
                    cv.drawRoundRect(rect, 4, 4, paint);
                    cv.drawCircle(12, 5.5f, 2, paint);
                    path.moveTo(12, 10); path.lineTo(12, 20);
                    path.moveTo(8, 12); path.lineTo(4, 10);
                    path.moveTo(16, 12); path.lineTo(20, 10);
                    path.moveTo(8, 16.5f); path.lineTo(4, 18.5f);
                    path.moveTo(16, 16.5f); path.lineTo(20, 18.5f);
                    cv.drawPath(path, paint);
                    break;
                case IC_PEN:
                    path.moveTo(4, 20); path.lineTo(5, 15.5f); path.lineTo(16, 4.5f);
                    path.lineTo(19.5f, 8); path.lineTo(8.5f, 19); path.close();
                    path.moveTo(14, 6.5f); path.lineTo(17.5f, 10);
                    cv.drawPath(path, paint);
                    break;
                case IC_DOC:
                    path.moveTo(6, 3); path.lineTo(14, 3); path.lineTo(19, 8);
                    path.lineTo(19, 21); path.lineTo(6, 21); path.close();
                    path.moveTo(14, 3); path.lineTo(14, 8); path.lineTo(19, 8);
                    cv.drawPath(path, paint);
                    break;
                case IC_SCAN:
                    path.moveTo(4, 8); path.lineTo(4, 4); path.lineTo(8, 4);
                    path.moveTo(16, 4); path.lineTo(20, 4); path.lineTo(20, 8);
                    path.moveTo(20, 16); path.lineTo(20, 20); path.lineTo(16, 20);
                    path.moveTo(8, 20); path.lineTo(4, 20); path.lineTo(4, 16);
                    path.moveTo(8, 12); path.lineTo(16, 12);
                    cv.drawPath(path, paint);
                    break;
                case IC_MAIL:
                    rect.set(3, 5, 21, 19);
                    cv.drawRoundRect(rect, 2.5f, 2.5f, paint);
                    path.moveTo(3.5f, 7); path.lineTo(12, 13); path.lineTo(20.5f, 7);
                    cv.drawPath(path, paint);
                    break;
                case IC_CAMERA:
                    rect.set(3, 7, 21, 20);
                    cv.drawRoundRect(rect, 3, 3, paint);
                    cv.drawCircle(12, 13.5f, 3.5f, paint);
                    path.moveTo(8.5f, 7); path.lineTo(9.5f, 4.5f); path.lineTo(14.5f, 4.5f); path.lineTo(15.5f, 7);
                    cv.drawPath(path, paint);
                    break;
                case IC_MOON:
                    path.moveTo(20.5f, 14.5f);
                    path.cubicTo(15, 16.5f, 8.5f, 12, 9.5f, 3.5f);
                    path.cubicTo(5, 5, 2.5f, 9.5f, 4, 14.5f);
                    path.cubicTo(5.5f, 19.5f, 11, 22, 16, 20);
                    path.cubicTo(18, 19.3f, 19.7f, 17.2f, 20.5f, 14.5f);
                    path.close();
                    cv.drawPath(path, paint);
                    break;
                case IC_TRASH:
                    path.moveTo(4, 7); path.lineTo(20, 7);
                    path.moveTo(9, 7); path.lineTo(9, 4); path.lineTo(15, 4); path.lineTo(15, 7);
                    path.moveTo(6, 7); path.lineTo(7, 20); path.lineTo(17, 20); path.lineTo(18, 7);
                    path.moveTo(10, 11); path.lineTo(10, 16);
                    path.moveTo(14, 11); path.lineTo(14, 16);
                    cv.drawPath(path, paint);
                    break;
                case IC_PLUG:
                    path.moveTo(9, 3); path.lineTo(9, 8);
                    path.moveTo(15, 3); path.lineTo(15, 8);
                    path.moveTo(12, 13); path.lineTo(12, 21);
                    cv.drawPath(path, paint);
                    rect.set(6, 8, 18, 13);
                    cv.drawRoundRect(rect, 2.5f, 2.5f, paint);
                    break;
                case IC_WAND:
                    path.moveTo(4, 20); path.lineTo(15, 9);
                    path.moveTo(17, 3); path.lineTo(17, 7);
                    path.moveTo(15, 5); path.lineTo(19, 5);
                    path.moveTo(20, 11); path.lineTo(20, 14);
                    path.moveTo(18.5f, 12.5f); path.lineTo(21.5f, 12.5f);
                    cv.drawPath(path, paint);
                    break;
                case IC_CLIP:
                    path.moveTo(19.5f, 11.5f); path.lineTo(11.5f, 19.5f);
                    path.cubicTo(9.5f, 21.5f, 6.5f, 21.5f, 4.5f, 19.5f);
                    path.cubicTo(2.5f, 17.5f, 2.5f, 14.5f, 4.5f, 12.5f);
                    path.lineTo(13, 4);
                    path.cubicTo(14.4f, 2.6f, 16.6f, 2.6f, 18, 4);
                    path.cubicTo(19.4f, 5.4f, 19.4f, 7.6f, 18, 9);
                    path.lineTo(10, 17);
                    path.cubicTo(9.2f, 17.8f, 8, 17.8f, 7.2f, 17);
                    path.cubicTo(6.4f, 16.2f, 6.4f, 15, 7.2f, 14.2f);
                    path.lineTo(14.5f, 7);
                    cv.drawPath(path, paint);
                    break;
                case IC_IMAGE:
                    rect.set(3, 4, 21, 20);
                    cv.drawRoundRect(rect, 3, 3, paint);
                    cv.drawCircle(9, 10, 1.8f, paint);
                    path.moveTo(3, 17); path.lineTo(8.5f, 12); path.lineTo(13, 16);
                    path.lineTo(16, 13); path.lineTo(21, 18);
                    cv.drawPath(path, paint);
                    break;
                case IC_UP:
                    path.moveTo(12, 19); path.lineTo(12, 5);
                    path.moveTo(5.5f, 11.5f); path.lineTo(12, 5); path.lineTo(18.5f, 11.5f);
                    cv.drawPath(path, paint);
                    break;
                case IC_DOWNLOAD:
                    path.moveTo(12, 4); path.lineTo(12, 15);
                    path.moveTo(6.5f, 10); path.lineTo(12, 15.5f); path.lineTo(17.5f, 10);
                    path.moveTo(5, 20); path.lineTo(19, 20);
                    cv.drawPath(path, paint);
                    break;
                case IC_COPY:
                    rect.set(9, 9, 20, 20);
                    cv.drawRoundRect(rect, 2.5f, 2.5f, paint);
                    path.moveTo(15, 5.5f); path.lineTo(15, 5);
                    path.quadTo(15, 4, 14, 4); path.lineTo(5.5f, 4);
                    path.quadTo(4, 4, 4, 5.5f); path.lineTo(4, 14);
                    path.quadTo(4, 15, 5, 15); path.lineTo(9, 15);
                    cv.drawPath(path, paint);
                    break;
                case IC_EXPAND:
                    path.moveTo(14, 4); path.lineTo(20, 4); path.lineTo(20, 10);
                    path.moveTo(20, 4); path.lineTo(13, 11);
                    path.moveTo(10, 20); path.lineTo(4, 20); path.lineTo(4, 14);
                    path.moveTo(4, 20); path.lineTo(11, 13);
                    cv.drawPath(path, paint);
                    break;
                case IC_CLOSE:
                    path.moveTo(6, 6); path.lineTo(18, 18);
                    path.moveTo(18, 6); path.lineTo(6, 18);
                    cv.drawPath(path, paint);
                    break;
                case IC_CHECK:
                    path.moveTo(5, 12.5f); path.lineTo(10, 17.5f); path.lineTo(19, 7);
                    cv.drawPath(path, paint);
                    break;
                case IC_PLUS:
                    path.moveTo(12, 5); path.lineTo(12, 19);
                    path.moveTo(5, 12); path.lineTo(19, 12);
                    cv.drawPath(path, paint);
                    break;
                case IC_CHEVDOWN:
                    path.moveTo(6, 9); path.lineTo(12, 15.5f); path.lineTo(18, 9);
                    cv.drawPath(path, paint);
                    break;
                default:
                    break;
            }
            cv.restore();
        }
    }

    /** Orb bercahaya (inti radial gradien + cincin tipis). */
    private FrameLayout orb(int sizeDp, boolean pulse) {
        FrameLayout f = new FrameLayout(this);

        View glow = new View(this);
        GradientDrawable gg = new GradientDrawable();
        gg.setShape(GradientDrawable.OVAL);
        gg.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        gg.setGradientRadius(dp(sizeDp) * 0.5f);
        gg.setColors(new int[]{Color.argb(110, 59, 130, 246), Color.argb(0, 59, 130, 246)});
        glow.setBackground(gg);
        f.addView(glow, new FrameLayout.LayoutParams(-1, -1));

        int coreSize = (int) (sizeDp * 0.62f);
        View core = new View(this);
        GradientDrawable cg = new GradientDrawable();
        cg.setShape(GradientDrawable.OVAL);
        cg.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        cg.setGradientRadius(dp(coreSize) * 0.75f);
        cg.setGradientCenter(0.35f, 0.3f);
        cg.setColors(new int[]{
                Color.rgb(186, 220, 253), Color.rgb(59, 130, 246),
                Color.rgb(30, 64, 175), Color.rgb(12, 20, 60)});
        cg.setStroke(dp(1), Color.argb(120, 147, 197, 253));
        core.setBackground(cg);
        f.addView(core, new FrameLayout.LayoutParams(dp(coreSize), dp(coreSize), Gravity.CENTER));

        if (pulse) {
            ObjectAnimator ax = ObjectAnimator.ofFloat(core, "scaleX", 1f, 1.08f);
            ObjectAnimator ay = ObjectAnimator.ofFloat(core, "scaleY", 1f, 1.08f);
            for (ObjectAnimator a : new ObjectAnimator[]{ax, ay}) {
                a.setDuration(1800);
                a.setRepeatCount(ValueAnimator.INFINITE);
                a.setRepeatMode(ValueAnimator.REVERSE);
                a.start();
                animators.add(a);
            }
        }
        return f;
    }

    private void pressable(View v) {
        v.setOnTouchListener((view, ev) -> {
            int a = ev.getAction();
            if (a == MotionEvent.ACTION_DOWN) {
                view.setAlpha(0.6f);
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                view.setAlpha(view == sendButton && busy ? 0.5f : 1f);
            }
            return false;
        });
    }

    private TextView label(String value, int color, int size, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextColor(color);
        t.setTextSize(size);
        t.setTypeface(bold ? tfBold : tfRegular);
        return t;
    }

    private GradientDrawable rounded(int color, int radiusDp, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) d.setStroke(dp(1), strokeColor);
        return d;
    }

    private GradientDrawable gradient(int c1, int c2, GradientDrawable.Orientation o, int radiusDp) {
        GradientDrawable d = new GradientDrawable(o, new int[]{c1, c2});
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private void setCorners(GradientDrawable d, int tl, int tr, int br, int bl) {
        d.setCornerRadii(new float[]{dp(tl), dp(tl), dp(tr), dp(tr), dp(br), dp(br), dp(bl), dp(bl)});
    }

    private void roundClip(View v, int radiusDp) {
        final float r = dp(radiusDp);
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
            }
        });
        v.setClipToOutline(true);
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ------------------------------------------------------- Lampiran

    /** Menu kecil: ambil foto dengan kamera atau pilih file. */
    private void showAttachMenu() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(16), dp(14), dp(10));
        box.addView(label("Tambah lampiran", TEXT, 17, true));

        final AlertDialog[] holder = new AlertDialog[1];
        String[][] opts = {
                {"Ambil foto", "Buka kamera"},
                {"Gambar & file", "Galeri, PDF, DOCX, teks/kode"}
        };
        int[] optIcons = {IC_CAMERA, IC_IMAGE};
        for (int i = 0; i < opts.length; i++) {
            final int which = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(12), dp(14), dp(12));
            row.setBackground(rounded(CARD, 16, CARD_STROKE));
            pressable(row);
            row.addView(new IconView(this, optIcons[i], PURPLE_LIGHT), new LinearLayout.LayoutParams(dp(26), dp(26)));
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(label(opts[i][0], TEXT, 15, true));
            col.addView(label(opts[i][1], MUTED, 12, false));
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
            cl.leftMargin = dp(14);
            row.addView(col, cl);
            row.setOnClickListener(v -> {
                if (holder[0] != null) holder[0].dismiss();
                if (which == 0) takePhoto(); else pickFiles();
            });
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
            rp.topMargin = dp(10);
            box.addView(row, rp);
        }

        AlertDialog dlg = new AlertDialog.Builder(this).setView(box).create();
        holder[0] = dlg;
        dlg.show();
        Window w = dlg.getWindow();
        if (w != null) w.setBackgroundDrawable(rounded(SHEET, 24, CARD_STROKE));
    }

    /** Buka aplikasi kamera; foto disimpan ke Pictures/ENC AI lewat MediaStore. */
    private void takePhoto() {
        if (busy) {
            toast("Tunggu jawaban selesai dulu");
            return;
        }
        if (pending.size() + processing >= MAX_ATTACHMENTS) {
            toast("Maksimal " + MAX_ATTACHMENTS + " lampiran per pesan");
            return;
        }
        if (Build.VERSION.SDK_INT < 29) {
            toast("Kamera langsung butuh Android 10+. Gunakan \"Gambar & file\" untuk memilih foto.");
            return;
        }
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, "ENC_AI_" + System.currentTimeMillis() + ".jpg");
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            cv.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ENC AI");
            Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new Exception("Tidak bisa menyiapkan file foto");
            cameraUri = uri;
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, uri);
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(i, REQ_CAMERA);
        } catch (android.content.ActivityNotFoundException e) {
            discardCameraUri();
            toast("Tidak ada aplikasi kamera");
        } catch (Exception e) {
            discardCameraUri();
            toast("Gagal membuka kamera: " + e.getMessage());
        }
    }

    private void discardCameraUri() {
        if (cameraUri != null) {
            try { getContentResolver().delete(cameraUri, null, null); } catch (Exception ignored) {}
            cameraUri = null;
        }
    }

    private void pickFiles() {
        if (busy) {
            toast("Tunggu jawaban selesai dulu");
            return;
        }
        if (pending.size() + processing >= MAX_ATTACHMENTS) {
            toast("Maksimal " + MAX_ATTACHMENTS + " lampiran per pesan");
            return;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "image/*", "application/pdf", "text/*", "application/json", "application/xml",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/octet-stream"});
        try {
            startActivityForResult(i, REQ_PICK_FILES);
        } catch (Exception e) {
            toast("Tidak ada aplikasi pemilih file");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_CAMERA) {
            Uri photo = cameraUri;
            cameraUri = null;
            if (photo == null) return;
            if (resultCode == RESULT_OK) {
                List<Uri> one = new ArrayList<>();
                one.add(photo);
                queueUris(one);
            } else { // dibatalkan: buang entri kosong di galeri
                try { getContentResolver().delete(photo, null, null); } catch (Exception ignored) {}
            }
            return;
        }
        if (requestCode != REQ_PICK_FILES || resultCode != RESULT_OK || data == null) return;

        List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        queueUris(uris);
    }

    private void queueUris(List<Uri> uris) {
        for (Uri uri : uris) {
            if (pending.size() + processing >= MAX_ATTACHMENTS) {
                toast("Maksimal " + MAX_ATTACHMENTS + " lampiran per pesan");
                break;
            }
            processing++;
            status.setText("Memproses lampiran…");
            executor.execute(() -> {
                Attachment att = null;
                String error = null;
                try {
                    att = processUri(uri);
                } catch (Exception e) {
                    error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                }
                final Attachment result = att;
                final String err = error;
                mainHandler.post(() -> {
                    processing--;
                    if (result != null) {
                        pending.add(result);
                        renderAttachBar();
                    } else {
                        toast("Gagal melampirkan: " + err);
                    }
                    if (processing == 0 && !busy) status.setText("ENC AI • Siap terhubung ke " + backendName());
                });
            });
        }
    }

    private void renderAttachBar() {
        attachBar.removeAllViews();
        attachScroll.setVisibility(pending.isEmpty() ? View.GONE : View.VISIBLE);
        for (final Attachment a : new ArrayList<>(pending)) {
            LinearLayout chip = new LinearLayout(this);
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(dp(6), dp(6), dp(10), dp(6));
            chip.setBackground(rounded(CARD, 16, CARD_STROKE));

            if (a.thumb != null) {
                ImageView iv = new ImageView(this);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setImageBitmap(a.thumb);
                roundClip(iv, 10);
                chip.addView(iv, new LinearLayout.LayoutParams(dp(36), dp(36)));
            } else {
                FrameLayout icon = new FrameLayout(this);
                icon.setBackground(rounded(ACCENT_SOFT, 10, 0));
                icon.addView(new IconView(this, IC_DOC, PURPLE_LIGHT), new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
                chip.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));
            }

            TextView name = label(a.name, TEXT, 12, false);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            name.setMaxWidth(dp(120));
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-2, -2);
            np.leftMargin = dp(8);
            chip.addView(name, np);

            View close = iconTap(IC_CLOSE, MUTED, 14, v -> {
                pending.remove(a);
                renderAttachBar();
            });
            chip.addView(close, new LinearLayout.LayoutParams(-2, -2));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(8);
            attachBar.addView(chip, lp);
        }
    }

    /** Baca & ubah file terpilih menjadi Attachment. Dijalankan di thread latar. */
    private Attachment processUri(Uri uri) throws Exception {
        String name = "file";
        long size = -1;
        Cursor cur = null;
        try {
            cur = getContentResolver().query(uri, null, null, null, null);
            if (cur != null && cur.moveToFirst()) {
                int ni = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int si = cur.getColumnIndex(OpenableColumns.SIZE);
                if (ni >= 0 && !cur.isNull(ni)) name = cur.getString(ni);
                if (si >= 0 && !cur.isNull(si)) size = cur.getLong(si);
            }
        } catch (Exception ignored) {
        } finally {
            if (cur != null) cur.close();
        }
        if (size > MAX_FILE_BYTES) throw new Exception(name + " terlalu besar (maks 20 MB)");

        String mime = getContentResolver().getType(uri);
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (mime == null || mime.equals("application/octet-stream")) mime = guessMime(ext, mime);

        Attachment att = new Attachment();
        att.name = name;

        if (mime.startsWith("image/")) {
            att.kind = "image";
            Bitmap bmp = decodeBitmap(uri, MAX_IMAGE_DIM);
            att.dataUrls.add(toDataUrl(bmp));
            att.thumb = scaleDown(bmp, 320);
            return att;
        }

        if (mime.equals("application/pdf")) {
            att.kind = "pdf";
            renderPdf(uri, att);
            return att;
        }

        if (ext.equals("docx")
                || mime.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document")) {
            att.kind = "text";
            att.text = extractDocx(uri);
            if (att.text.trim().isEmpty()) throw new Exception(name + " tidak berisi teks");
            return att;
        }

        if (mime.startsWith("text/") || mime.contains("json") || mime.contains("xml")
                || mime.contains("javascript") || TEXT_EXT.contains(ext)) {
            att.kind = "text";
            att.text = readText(uri);
            return att;
        }

        throw new Exception("Jenis file " + (ext.isEmpty() ? mime : "." + ext) + " belum didukung");
    }

    private String guessMime(String ext, String fallback) {
        switch (ext) {
            case "png": return "image/png";
            case "jpg": case "jpeg": return "image/jpeg";
            case "webp": return "image/webp";
            case "gif": return "image/gif";
            case "bmp": return "image/bmp";
            case "heic": case "heif": return "image/heic";
            case "pdf": return "application/pdf";
            case "docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            default: return fallback == null ? "application/octet-stream" : fallback;
        }
    }

    private Bitmap decodeBitmap(Uri uri, int maxDim) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, o);
        }
        if (o.outWidth <= 0 || o.outHeight <= 0) throw new Exception("Gambar tidak bisa dibaca");
        int sample = 1;
        while (o.outWidth / sample > maxDim * 2 || o.outHeight / sample > maxDim * 2) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        Bitmap bmp;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            bmp = BitmapFactory.decodeStream(in, null, o);
        }
        if (bmp == null) throw new Exception("Format gambar tidak didukung");
        bmp = scaleDown(bmp, maxDim);
        int deg = exifRotation(uri);
        if (deg != 0) {
            Matrix m = new Matrix();
            m.postRotate(deg);
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
        }
        return bmp;
    }

    private int exifRotation(Uri uri) {
        if (Build.VERSION.SDK_INT < 24) return 0;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return 0;
            ExifInterface exif = new ExifInterface(in);
            int o = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (o == ExifInterface.ORIENTATION_ROTATE_90) return 90;
            if (o == ExifInterface.ORIENTATION_ROTATE_180) return 180;
            if (o == ExifInterface.ORIENTATION_ROTATE_270) return 270;
        } catch (Exception ignored) {}
        return 0;
    }

    private Bitmap scaleDown(Bitmap src, int maxDim) {
        int w = src.getWidth(), h = src.getHeight();
        int longest = Math.max(w, h);
        if (longest <= maxDim) return src;
        float f = (float) maxDim / longest;
        return Bitmap.createScaledBitmap(src, Math.max(1, Math.round(w * f)), Math.max(1, Math.round(h * f)), true);
    }

    private String toDataUrl(Bitmap bmp) {
        Bitmap src = bmp;
        if (bmp.hasAlpha()) { // JPEG tidak punya alpha: ratakan di atas latar putih
            src = Bitmap.createBitmap(bmp.getWidth(), bmp.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas cv = new Canvas(src);
            cv.drawColor(Color.WHITE);
            cv.drawBitmap(bmp, 0, 0, null);
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        src.compress(Bitmap.CompressFormat.JPEG, 82, bos);
        return "data:image/jpeg;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
    }

    /** Render beberapa halaman pertama PDF menjadi gambar. */
    private void renderPdf(Uri uri, Attachment att) throws Exception {
        ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r");
        if (pfd == null) throw new Exception("PDF tidak bisa dibuka");
        PdfRenderer renderer = null;
        try {
            renderer = new PdfRenderer(pfd);
            int total = renderer.getPageCount();
            int count = Math.min(total, MAX_PDF_PAGES);
            for (int i = 0; i < count; i++) {
                PdfRenderer.Page page = renderer.openPage(i);
                try {
                    float scale = 1000f / page.getWidth();
                    int w = 1000;
                    int h = Math.max(1, Math.round(page.getHeight() * scale));
                    Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    bmp.eraseColor(Color.WHITE);
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    att.dataUrls.add(toDataUrl(bmp));
                    if (i == 0) att.thumb = scaleDown(bmp, 320);
                } finally {
                    page.close();
                }
            }
            att.note = "PDF " + total + " halaman, " + count + " halaman pertama dikirim sebagai gambar";
        } catch (SecurityException e) {
            throw new Exception("PDF terkunci/berpassword");
        } finally {
            if (renderer != null) renderer.close();
            pfd.close();
        }
    }

    private String readText(Uri uri) throws Exception {
        final int max = maxTextChars();
        StringBuilder sb = new StringBuilder();
        boolean truncated = false;
        try (InputStream in = getContentResolver().openInputStream(uri);
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) != -1) {
                if (sb.length() + n > max) {
                    sb.append(buf, 0, max - sb.length());
                    truncated = true;
                    break;
                }
                sb.append(buf, 0, n);
            }
        }
        if (truncated) sb.append("\n[... dipotong, file terlalu panjang ...]");
        return sb.toString();
    }

    /** Ambil teks polos dari .docx (zip → word/document.xml). */
    private String extractDocx(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri);
             ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if ("word/document.xml".equals(e.getName())) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = zis.read(buf)) != -1) bos.write(buf, 0, n);
                    String xml = bos.toString("UTF-8");
                    String t = xml.replace("</w:p>", "\n").replace("<w:tab/>", "\t").replace("<w:br/>", "\n");
                    t = t.replaceAll("<[^>]+>", "");
                    t = t.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                            .replace("&apos;", "'").replace("&amp;", "&");
                    final int max = maxTextChars();
                    if (t.length() > max) t = t.substring(0, max) + "\n[... dipotong ...]";
                    return t;
                }
            }
        }
        throw new Exception("Isi dokumen Word tidak ditemukan");
    }

    /** Pesan untuk riwayat tersimpan: teks + daftar nama lampiran. */
    private String historyText(String text, List<Attachment> atts) {
        StringBuilder names = new StringBuilder();
        for (Attachment a : atts) {
            if (names.length() > 0) names.append(", ");
            names.append(a.name);
        }
        return text + "\n\n[Lampiran: " + names + "]";
    }

    /** Konten lengkap yang dikirim ke model (gaya OpenAI vision). */
    private Object buildRichContent(String text, List<Attachment> atts) throws Exception {
        StringBuilder full = new StringBuilder(text);
        boolean hasImages = false;
        JSONArray fileParts = new JSONArray();
        for (Attachment a : atts) {
            if ("text".equals(a.kind)) {
                if (isHermes() && a.text.length() > INLINE_TEXT_MAX) {
                    JSONObject fp = new JSONObject();
                    fp.put("type", "file");
                    fp.put("name", a.name);
                    fp.put("text", a.text);
                    fileParts.put(fp);
                    full.append("\n\n[File terlampir: ").append(a.name).append("]");
                } else {
                    full.append("\n\n--- Isi file: ").append(a.name).append(" ---\n")
                            .append(a.text).append("\n--- Akhir file ---");
                }
            } else if ("pdf".equals(a.kind)) {
                full.append("\n\n[").append(a.name).append(": ").append(a.note).append("]");
            } else {
                full.append("\n\n[Gambar terlampir: ").append(a.name).append("]");
            }
            if (!a.dataUrls.isEmpty()) hasImages = true;
        }
        if (!hasImages && fileParts.length() == 0) return full.toString();

        JSONArray parts = new JSONArray();
        JSONObject textPart = new JSONObject();
        textPart.put("type", "text");
        textPart.put("text", full.toString());
        parts.put(textPart);
        for (Attachment a : atts) {
            for (String url : a.dataUrls) {
                JSONObject img = new JSONObject();
                img.put("type", "image_url");
                img.put("image_url", new JSONObject().put("url", url));
                parts.put(img);
            }
        }
        for (int i = 0; i < fileParts.length(); i++) parts.put(fileParts.getJSONObject(i));
        return parts;
    }

    /** Ambil hanya bagian teks dari konten multimodal. */
    private String textOnly(JSONArray parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) {
            JSONObject p = parts.optJSONObject(i);
            if (p != null && "text".equals(p.optString("type"))) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(p.optString("text"));
            }
        }
        return sb.toString();
    }

    /** Salin riwayat dan ganti pesan ber-lampiran dengan konten lengkapnya. */
    private JSONArray buildPayloadMessages() throws Exception {
        JSONArray out = new JSONArray();
        int lastUser = -1;
        for (int i = conversation.length() - 1; i >= 0; i--) {
            if ("user".equals(conversation.getJSONObject(i).optString("role"))) {
                lastUser = i;
                break;
            }
        }
        for (int i = 0; i < conversation.length(); i++) {
            JSONObject src = conversation.getJSONObject(i);
            JSONObject copy = new JSONObject();
            copy.put("role", src.optString("role"));
            Object rich = richContent.get(i);
            if (rich instanceof JSONArray && isHermes() && i != lastUser) {
                rich = textOnly((JSONArray) rich);
            }
            copy.put("content", rich != null ? rich : src.optString("content"));
            out.put(copy);
        }
        return out;
    }

    // ------------------------------------------------------------ Chat

    private void addBubble(String text, boolean user) {
        addBubbleInternal(text, user, false);
    }

    private void addLoadingBubble() {
        addBubbleInternal(isHermes() ? "Menghubungi Hermes (InstaBox dibangunkan bila tidur)…" : "Sedang menghubungkan ke 9Router…", false, true);
        loadingShown = true;
    }

    /** Bubble pengguna dengan pratinjau lampiran. */
    private void addUserBubbleWithAttachments(String text, List<Attachment> atts) {
        int maxW = (int) (getResources().getDisplayMetrics().widthPixels * 0.76f);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{PURPLE_DEEP, BLUE});
        setCorners(bg, 20, 20, 5, 20);
        box.setBackground(bg);

        for (Attachment a : atts) {
            if (a.thumb != null) {
                ImageView iv = new ImageView(this);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setImageBitmap(a.thumb);
                roundClip(iv, 14);
                LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(Math.min(maxW - dp(16), dp(220)), dp(150));
                ip.bottomMargin = dp(6);
                box.addView(iv, ip);
                if ("pdf".equals(a.kind)) {
                    LinearLayout cap = iconLabel(IC_DOC, a.name, Color.WHITE, 12, false);
                    cap.setPadding(dp(4), 0, dp(4), dp(6));
                    box.addView(cap);
                }
            } else {
                LinearLayout f = iconLabel(IC_DOC, a.name, Color.WHITE, 13, false);
                f.setPadding(dp(10), dp(8), dp(10), dp(8));
                f.setBackground(rounded(Color.argb(60, 255, 255, 255), 12, 0));
                LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(-2, -2);
                fp.bottomMargin = dp(6);
                box.addView(f, fp);
            }
        }

        TextView body = label(text, Color.WHITE, 15, false);
        body.setTextIsSelectable(true);
        body.setLineSpacing(dp(2), 1.0f);
        body.setMaxWidth(maxW - dp(16));
        body.setPadding(dp(6), dp(2), dp(6), dp(2));
        box.addView(body);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.gravity = Gravity.RIGHT;
        lp.topMargin = dp(8);
        lp.bottomMargin = dp(4);
        messages.addView(box, lp);
        scrollToBottom();
    }

    private void addBubbleInternal(String text, boolean user, boolean loading) {
        int maxW = (int) (getResources().getDisplayMetrics().widthPixels * 0.76f);

        if (user) {
            TextView body = label(text, Color.WHITE, 15, false);
            body.setTextIsSelectable(true);
            body.setLineSpacing(dp(2), 1.0f);
            body.setMaxWidth(maxW);
            body.setPadding(dp(14), dp(10), dp(14), dp(10));
            GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    new int[]{PURPLE_DEEP, BLUE});
            setCorners(bg, 20, 20, 5, 20);
            body.setBackground(bg);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.gravity = Gravity.RIGHT;
            lp.topMargin = dp(8);
            lp.bottomMargin = dp(4);
            messages.addView(body, lp);
            scrollToBottom();
            return;
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.topMargin = dp(8);
        rp.bottomMargin = dp(4);
        messages.addView(row, rp);

        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(dp(32), dp(32));
        ap.topMargin = dp(2);
        ap.rightMargin = dp(8);
        row.addView(avatar(32), ap);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        row.addView(column, new LinearLayout.LayoutParams(0, -2, 1));

        final StringBuilder plain = new StringBuilder();
        if (loading) {
            TextView body = label(text, MUTED, 15, false);
            body.setTypeface(tfRegular, Typeface.ITALIC);
            body.setLineSpacing(dp(2), 1.0f);
            body.setMaxWidth(maxW + dp(24));
            body.setPadding(dp(14), dp(10), dp(14), dp(10));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(CARD);
            bg.setStroke(dp(1), CARD_STROKE);
            setCorners(bg, 5, 20, 20, 20);
            body.setBackground(bg);
            column.addView(body, new LinearLayout.LayoutParams(-2, -2));
        } else {
            View bubble = buildAssistantContent(text, maxW, plain);
            boolean hasCode = text.contains("```");
            column.addView(bubble, new LinearLayout.LayoutParams(hasCode ? -1 : -2, -2));

            LinearLayout copy = iconLabel(IC_COPY, "Salin", MUTED, 12, false);
            copy.setPadding(dp(6), dp(6), dp(10), dp(4));
            pressable(copy);
            copy.setOnClickListener(v -> copyText(plain.toString().trim(), "Jawaban disalin"));
            column.addView(copy, new LinearLayout.LayoutParams(-2, -2));
        }
        scrollToBottom();
    }

    // ------------------------------------------------- File dari Hermes

    /** Kartu file untuk tiap file dari Hermes. Ketuk = pratinjau, ikon unduh = simpan. */
    private void addFileChips(List<String[]> files) {
        int maxW = (int) (getResources().getDisplayMetrics().widthPixels * 0.76f);
        for (final String[] f : files) {
            final String ext = extOf(f[0]);

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(10), dp(10), dp(6), dp(10));
            card.setBackground(rounded(CARD, 16, CARD_STROKE));
            pressable(card);
            card.setOnClickListener(v -> showFilePreview(f));

            TextView badge = label(badgeText(ext), Color.WHITE, 11, true);
            badge.setGravity(Gravity.CENTER);
            badge.setSingleLine(true);
            badge.setBackground(rounded(badgeColor(ext), 10, 0));
            card.addView(badge, new LinearLayout.LayoutParams(dp(42), dp(42)));

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            TextView name = label(f[0], TEXT, 14, true);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            col.addView(name);
            col.addView(label(typeLabel(ext) + " · " + formatSize(fileSize(f)), MUTED, 11, false));
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1);
            cl.leftMargin = dp(12);
            card.addView(col, cl);

            View dl = iconTap(IC_DOWNLOAD, PURPLE_LIGHT, 20, v -> saveGeneratedFile(f));
            card.addView(dl, new LinearLayout.LayoutParams(-2, -2));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(maxW, -2);
            lp.leftMargin = dp(40);
            lp.topMargin = dp(4);
            lp.bottomMargin = dp(4);
            messages.addView(card, lp);
        }
        scrollToBottom();
    }

    // ---- Penyimpanan file hasil Hermes

    /** Simpan file hasil Hermes ke filesDir/generated, kembalikan metadata untuk riwayat. */
    private JSONArray persistFiles(List<String[]> files) {
        JSONArray meta = new JSONArray();
        File dir = new File(getFilesDir(), GENERATED_DIR);
        if (!dir.exists() && !dir.mkdirs()) return meta;
        for (String[] f : files) {
            try {
                String safe = f[0].replaceAll("[^\\w.\\-]", "_");
                if (safe.length() > 80) safe = safe.substring(safe.length() - 80);
                String stored = System.currentTimeMillis() + "_" + meta.length() + "_" + safe;
                File out = new File(dir, stored);
                byte[] bytes = Base64.decode(f[2], Base64.DEFAULT);
                try (OutputStream os = new FileOutputStream(out)) {
                    os.write(bytes);
                }
                JSONObject m = new JSONObject();
                m.put("name", f[0]);
                m.put("mime", f[1]);
                m.put("stored", stored);
                meta.put(m);
            } catch (Exception ignored) { }
        }
        return meta;
    }

    /** Baca kembali file tersimpan menjadi format {nama, mime, base64}. */
    private List<String[]> restoreFiles(JSONArray meta) {
        List<String[]> out = new ArrayList<>();
        File dir = new File(getFilesDir(), GENERATED_DIR);
        for (int i = 0; i < meta.length(); i++) {
            JSONObject m = meta.optJSONObject(i);
            if (m == null) continue;
            String stored = m.optString("stored", "");
            if (stored.isEmpty() || stored.contains("/") || stored.contains("..")) continue;
            File f = new File(dir, stored);
            if (!f.exists()) continue;
            try {
                byte[] bytes = readFileBytes(f);
                out.add(new String[]{
                        m.optString("name", "file"),
                        m.optString("mime", "application/octet-stream"),
                        Base64.encodeToString(bytes, Base64.NO_WRAP)});
            } catch (Exception ignored) { }
        }
        return out;
    }

    private byte[] readFileBytes(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toByteArray();
        }
    }

    /** Hapus semua file hasil Hermes yang tersimpan (dipanggil saat chat baru). */
    private void clearGeneratedFiles() {
        File[] old = new File(getFilesDir(), GENERATED_DIR).listFiles();
        if (old == null) return;
        for (File x : old) {
            try { x.delete(); } catch (Exception ignored) { }
        }
    }

    /** Pratinjau layar penuh: teks/kode/markdown, gambar; jenis lain hanya info + tombol simpan. */
    private void showFilePreview(final String[] f) {
        final String ext = extOf(f[0]);
        final byte[] bytes;
        try {
            bytes = Base64.decode(f[2], Base64.DEFAULT);
        } catch (Exception e) {
            toast("Data file rusak");
            return;
        }

        final boolean isText = isTextFile(f[1], ext);
        final boolean isImage = !isText && f[1] != null && f[1].startsWith("image/");
        String content = "";
        if (isText) {
            content = new String(bytes, StandardCharsets.UTF_8);
            if (content.length() > 200000) content = content.substring(0, 200000) + "\n[... dipotong untuk pratinjau ...]";
        }
        final String textContent = content;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(SHEET);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(8), dp(4), dp(8));
        bar.setBackgroundColor(CODE_HEAD);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        TextView title = label(f[0], TEXT, 15, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        titleCol.addView(title);
        titleCol.addView(label(typeLabel(ext) + " · " + formatSize(bytes.length), MUTED, 11, false));
        bar.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1));

        final AlertDialog[] holder = new AlertDialog[1];

        if (isText) {
            bar.addView(iconTap(IC_COPY, TEXT, 20, v -> copyText(textContent, "Isi file disalin")),
                    new LinearLayout.LayoutParams(-2, -2));
        }
        bar.addView(iconTap(IC_DOWNLOAD, PURPLE_LIGHT, 20, v -> saveGeneratedFile(f)),
                new LinearLayout.LayoutParams(-2, -2));
        bar.addView(iconTap(IC_CLOSE, TEXT, 20, v -> { if (holder[0] != null) holder[0].dismiss(); }),
                new LinearLayout.LayoutParams(-2, -2));
        box.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        View body = null;
        if (isText) {
            if ("md".equals(ext) || "markdown".equals(ext)) {
                ScrollView sv = new ScrollView(this);
                TextView tv = label("", TEXT, 15, false);
                tv.setText(renderMarkdown(textContent));
                tv.setTextIsSelectable(true);
                tv.setLineSpacing(dp(2), 1.0f);
                tv.setPadding(dp(16), dp(14), dp(16), dp(24));
                sv.addView(tv);
                body = sv;
            } else {
                ScrollView vs = new ScrollView(this);
                HorizontalScrollView hs = new HorizontalScrollView(this);
                TextView tv = new TextView(this);
                tv.setText(textContent);
                tv.setTypeface(tfMono);
                tv.setTextSize(13f);
                tv.setTextColor(CODE_FG);
                tv.setTextIsSelectable(true);
                tv.setPadding(dp(16), dp(14), dp(16), dp(24));
                hs.addView(tv);
                vs.addView(hs);
                body = vs;
            }
        } else if (isImage) {
            Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bmp != null) {
                ImageView iv = new ImageView(this);
                iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                iv.setImageBitmap(bmp);
                iv.setPadding(dp(12), dp(12), dp(12), dp(12));
                body = iv;
            }
        }
        if (body == null) {
            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.setGravity(Gravity.CENTER);
            info.setPadding(dp(24), dp(24), dp(24), dp(24));
            TextView badge = label(badgeText(ext), Color.WHITE, 16, true);
            badge.setGravity(Gravity.CENTER);
            badge.setBackground(rounded(badgeColor(ext), 16, 0));
            info.addView(badge, new LinearLayout.LayoutParams(dp(72), dp(72)));
            TextView msg = label("Pratinjau tidak tersedia untuk jenis file ini.\nSimpan file lalu buka dengan aplikasi yang sesuai.", MUTED, 13, false);
            msg.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-2, -2);
            mp.topMargin = dp(16);
            info.addView(msg, mp);
            LinearLayout save = iconLabel(IC_DOWNLOAD, "Simpan file", Color.WHITE, 14, true);
            save.setGravity(Gravity.CENTER);
            save.setPadding(dp(20), dp(12), dp(20), dp(12));
            GradientDrawable sg = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{PURPLE, BLUE});
            sg.setCornerRadius(dp(22));
            save.setBackground(sg);
            pressable(save);
            save.setOnClickListener(v -> saveGeneratedFile(f));
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
            sp.topMargin = dp(20);
            info.addView(save, sp);
            body = info;
        }
        box.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));

        AlertDialog dlg = new AlertDialog.Builder(this).setView(box).create();
        holder[0] = dlg;
        dlg.show();
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(SHEET));
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        }
    }

    // ---- Pembantu kartu file

    private String extOf(String name) {
        int d = name == null ? -1 : name.lastIndexOf('.');
        return d >= 0 && d < name.length() - 1 ? name.substring(d + 1).toLowerCase(Locale.ROOT) : "";
    }

    private long fileSize(String[] f) {
        String d = f[2] == null ? "" : f[2];
        int pad = d.endsWith("==") ? 2 : d.endsWith("=") ? 1 : 0;
        return Math.max(0, d.length() * 3L / 4 - pad);
    }

    private String formatSize(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(Locale.US, "%.1f KB", b / 1024.0);
        return String.format(Locale.US, "%.1f MB", b / (1024.0 * 1024.0));
    }

    private boolean isTextFile(String mime, String ext) {
        String m = mime == null ? "" : mime;
        return m.startsWith("text/") || m.contains("json") || m.contains("xml")
                || m.contains("javascript") || TEXT_EXT.contains(ext) || "svg".equals(ext);
    }

    private String badgeText(String ext) {
        if (ext.isEmpty()) return "FILE";
        String u = ext.toUpperCase(Locale.ROOT);
        return u.length() > 4 ? u.substring(0, 4) : u;
    }

    private String typeLabel(String ext) {
        switch (ext) {
            case "py": return "Python";
            case "js": return "JavaScript";
            case "ts": return "TypeScript";
            case "java": return "Java";
            case "kt": return "Kotlin";
            case "lua": return "Lua";
            case "html": case "htm": return "HTML";
            case "css": return "CSS";
            case "json": return "JSON";
            case "md": case "markdown": return "Markdown";
            case "txt": return "Teks";
            case "csv": return "CSV";
            case "xml": return "XML";
            case "sh": return "Shell";
            case "pdf": return "PDF";
            case "docx": return "Dokumen Word";
            case "xlsx": return "Spreadsheet Excel";
            case "pptx": return "Presentasi PowerPoint";
            case "png": case "jpg": case "jpeg": case "gif": case "webp": return "Gambar";
            case "svg": return "SVG";
            case "zip": return "Arsip ZIP";
            default: return ext.isEmpty() ? "File" : ext.toUpperCase(Locale.ROOT);
        }
    }

    private int badgeColor(String ext) {
        switch (ext) {
            case "py": return Color.rgb(55, 118, 171);
            case "js": case "ts": return Color.rgb(190, 150, 20);
            case "java": case "kt": return Color.rgb(200, 90, 40);
            case "html": case "htm": return Color.rgb(227, 99, 52);
            case "css": return Color.rgb(38, 110, 200);
            case "json": case "xml": case "csv": return Color.rgb(100, 110, 140);
            case "md": case "markdown": case "txt": return Color.rgb(80, 90, 130);
            case "sh": case "lua": return Color.rgb(60, 140, 90);
            case "pdf": return Color.rgb(215, 60, 60);
            case "docx": return Color.rgb(43, 87, 154);
            case "xlsx": return Color.rgb(33, 115, 70);
            case "pptx": return Color.rgb(210, 71, 38);
            case "png": case "jpg": case "jpeg": case "gif": case "webp": case "svg": return Color.rgb(8, 145, 178);
            case "zip": return Color.rgb(140, 110, 60);
            default: return PURPLE_DEEP;
        }
    }

    /** Simpan file ke Download/ENC AI lewat MediaStore (Android 10+). */
    private void saveGeneratedFile(String[] f) {
        if (Build.VERSION.SDK_INT < 29) {
            toast("Simpan file butuh Android 10+");
            return;
        }
        try {
            byte[] bytes = Base64.decode(f[2], Base64.DEFAULT);
            if (bytes.length == 0) throw new Exception("data file kosong");
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, f[0]);
            cv.put(MediaStore.Downloads.MIME_TYPE, f[1]);
            cv.put(MediaStore.Downloads.RELATIVE_PATH, "Download/ENC AI");
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new Exception("tidak bisa membuat file");
            try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                if (os == null) throw new Exception("tidak bisa menulis file");
                os.write(bytes);
            }
            toast("Tersimpan di Download/ENC AI/" + f[0]);
        } catch (Exception e) {
            toast("Gagal menyimpan: " + e.getMessage());
        }
    }

    // ------------------------------------------------- Kartu kode (salin / perbesar)

    private void copyText(String value, String toastMsg) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("ENC AI", value));
        toast(toastMsg);
    }

    /** Pisahkan teks dan blok kode ```...``` . Tiap elemen: {"text"|"code", bahasa, isi}. */
    private List<String[]> splitCodeBlocks(String md) {
        List<String[]> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inCode = false;
        String lang = "";
        for (String line : md.replace("\r\n", "\n").split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("```")) {
                if (!inCode) {
                    out.add(new String[]{"text", "", cur.toString()});
                    cur.setLength(0);
                    inCode = true;
                    String l = t.substring(3).trim();
                    int sp = l.indexOf(' ');
                    lang = sp > 0 ? l.substring(0, sp) : l;
                } else {
                    out.add(new String[]{"code", lang, trimEndNewlines(cur)});
                    cur.setLength(0);
                    inCode = false;
                }
                continue;
            }
            cur.append(line).append('\n');
        }
        if (inCode) out.add(new String[]{"code", lang, trimEndNewlines(cur)});
        else out.add(new String[]{"text", "", cur.toString()});
        return out;
    }

    private String trimEndNewlines(StringBuilder sb) {
        int end = sb.length();
        while (end > 0 && (sb.charAt(end - 1) == '\n' || sb.charAt(end - 1) == '\r')) end--;
        return sb.substring(0, end);
    }

    /** Gelembung jawaban: teks (markdown) dan kartu kode bergantian. */
    private View buildAssistantContent(String md, int maxW, StringBuilder plain) {
        LinearLayout bubble = new LinearLayout(this);
        bubble.setOrientation(LinearLayout.VERTICAL);
        bubble.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setStroke(dp(1), CARD_STROKE);
        setCorners(bg, 5, 20, 20, 20);
        bubble.setBackground(bg);

        boolean hasCode = md.contains("```");
        boolean first = true;
        for (String[] seg : splitCodeBlocks(md)) {
            if ("code".equals(seg[0])) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
                lp.topMargin = first ? 0 : dp(8);
                lp.bottomMargin = dp(6);
                bubble.addView(codeCard(seg[1], seg[2]), lp);
                plain.append(seg[2]).append("\n\n");
                first = false;
            } else {
                if (seg[2].trim().isEmpty()) continue;
                CharSequence rendered = renderMarkdown(seg[2]);
                if (rendered.length() == 0) continue;
                TextView tv = label("", TEXT, 15, false);
                tv.setText(rendered);
                tv.setTextIsSelectable(true);
                tv.setLineSpacing(dp(2), 1.0f);
                if (!hasCode) tv.setMaxWidth(maxW);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                if (!first) lp.topMargin = dp(4);
                bubble.addView(tv, lp);
                plain.append(rendered.toString()).append("\n\n");
                first = false;
            }
        }
        return bubble;
    }

    /** Kartu kode: judul (bahasa atau "Kode"), tombol salin, tombol perbesar, isi bisa digeser. */
    private View codeCard(final String lang, final String code) {
        final String title = lang == null || lang.isEmpty() ? "Kode" : lang;

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(CODE_BG, 12, CARD_STROKE));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), dp(2), dp(4), dp(2));
        GradientDrawable hbg = new GradientDrawable();
        hbg.setColor(CODE_HEAD);
        setCorners(hbg, 12, 12, 0, 0);
        header.setBackground(hbg);

        TextView name = label(title, MUTED, 12, true);
        header.addView(name, new LinearLayout.LayoutParams(0, -2, 1));

        header.addView(iconTap(IC_COPY, TEXT, 17, v -> copyText(code, "Kode disalin")),
                new LinearLayout.LayoutParams(-2, -2));
        header.addView(iconTap(IC_EXPAND, TEXT, 17, v -> showCodeFullscreen(title, code)),
                new LinearLayout.LayoutParams(-2, -2));
        card.addView(header, new LinearLayout.LayoutParams(-1, -2));

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        TextView tv = new TextView(this);
        tv.setText(code);
        tv.setTypeface(tfMono);
        tv.setTextSize(12.5f);
        tv.setTextColor(CODE_FG);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(12), dp(10), dp(12), dp(10));
        int lines = 1;
        for (int i = 0; i < code.length(); i++) if (code.charAt(i) == '\n') lines++;
        final int previewLines = 14;
        if (lines > previewLines) tv.setMaxLines(previewLines);
        hs.addView(tv);
        card.addView(hs, new LinearLayout.LayoutParams(-1, -2));

        if (lines > previewLines) {
            TextView more = label("… " + (lines - previewLines) + " baris lagi • ketuk ikon perbesar untuk membuka semua", MUTED, 11, false);
            more.setPadding(dp(12), 0, dp(12), dp(8));
            card.addView(more);
        }
        return card;
    }

    /** Tampilan penuh layar untuk satu blok kode, dengan tombol salin dan tutup. */
    private void showCodeFullscreen(String title, final String code) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(SHEET);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(10), dp(8), dp(10));
        bar.setBackgroundColor(CODE_HEAD);
        bar.addView(label(title, TEXT, 15, true), new LinearLayout.LayoutParams(0, -2, 1));

        final AlertDialog[] holder = new AlertDialog[1];
        bar.addView(iconTap(IC_COPY, TEXT, 20, v -> copyText(code, "Kode disalin")),
                new LinearLayout.LayoutParams(-2, -2));
        bar.addView(iconTap(IC_CLOSE, TEXT, 20, v -> { if (holder[0] != null) holder[0].dismiss(); }),
                new LinearLayout.LayoutParams(-2, -2));
        box.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        ScrollView vs = new ScrollView(this);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(code);
        tv.setTypeface(tfMono);
        tv.setTextSize(13f);
        tv.setTextColor(CODE_FG);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(16), dp(14), dp(16), dp(24));
        hs.addView(tv);
        vs.addView(hs);
        box.addView(vs, new LinearLayout.LayoutParams(-1, 0, 1));

        AlertDialog dlg = new AlertDialog.Builder(this).setView(box).create();
        holder[0] = dlg;
        dlg.show();
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(SHEET));
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
        }
    }

    // ------------------------------------------------------- Markdown

    private static final int SPAN = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE;
    private static final Pattern MD_HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern MD_HR = Pattern.compile("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$");
    private static final Pattern MD_BULLET = Pattern.compile("^(\\s*)[-*+•]\\s+(.*)$");
    private static final Pattern MD_NUMBERED = Pattern.compile("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$");
    private static final Pattern MD_TABLE_SEP = Pattern.compile("^\\s*\\|?[\\s:|-]+\\|?\\s*$");

    /** Ubah teks markdown dari model menjadi teks berformat (tanpa tanda ** # ` dll). */
    private CharSequence renderMarkdown(String md) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        if (md == null || md.isEmpty()) return out;
        String[] lines = md.replace("\r\n", "\n").split("\n", -1);
        boolean inCode = false;
        boolean prevTable = false;
        int codeStart = 0;

        for (String raw : lines) {
            String t = raw.trim();

            // Blok kode ```
            if (t.startsWith("```")) {
                if (!inCode) {
                    inCode = true;
                    ensureBlankLine(out);
                    codeStart = out.length();
                } else {
                    inCode = false;
                    styleCodeBlock(out, codeStart);
                }
                continue;
            }
            if (inCode) {
                out.append(raw).append('\n');
                continue;
            }

            // Tabel | a | b |
            boolean isTable = t.startsWith("|") && t.indexOf('|', 1) > 0;
            if (isTable) {
                if (t.indexOf('-') >= 0 && MD_TABLE_SEP.matcher(t).matches()) continue;
                StringBuilder row = new StringBuilder();
                for (String cell : t.split("\\|")) {
                    String c = cell.trim();
                    if (c.isEmpty()) continue;
                    if (row.length() > 0) row.append("  │  ");
                    row.append(c);
                }
                int s = out.length();
                appendInline(out, row.toString());
                if (!prevTable) out.setSpan(new FontSpan(tfBold), s, out.length(), SPAN);
                out.append('\n');
                prevTable = true;
                continue;
            }
            prevTable = false;

            if (t.isEmpty()) {
                ensureBlankLine(out);
                continue;
            }

            // Garis pemisah ---
            if (MD_HR.matcher(t).matches()) {
                ensureNewline(out);
                int s = out.length();
                out.append("────────────────────\n");
                out.setSpan(new ForegroundColorSpan(HR_COLOR), s, out.length() - 1, SPAN);
                continue;
            }

            // Judul # ## ###
            Matcher hm = MD_HEADING.matcher(t);
            if (hm.matches()) {
                ensureBlankLine(out);
                int level = hm.group(1).length();
                int s = out.length();
                appendInline(out, hm.group(2).trim());
                int e = out.length();
                float size = level == 1 ? 1.35f : level == 2 ? 1.2f : level == 3 ? 1.1f : 1.0f;
                out.setSpan(new RelativeSizeSpan(size), s, e, SPAN);
                out.setSpan(new FontSpan(tfBold), s, e, SPAN);
                if (level <= 2) out.setSpan(new ForegroundColorSpan(PURPLE_LIGHT), s, e, SPAN);
                out.append('\n');
                continue;
            }

            // Kutipan >
            if (t.startsWith(">")) {
                int s = out.length();
                appendInline(out, t.replaceFirst("^>+\\s?", ""));
                int e = out.length();
                out.setSpan(new ForegroundColorSpan(QUOTE_TXT), s, e, SPAN);
                out.append('\n');
                out.setSpan(new QuoteSpan(PURPLE), s, out.length(), SPAN);
                continue;
            }

            String ln = raw.replace("\t", "    ");

            // Daftar berpoin - * +
            Matcher bm = MD_BULLET.matcher(ln);
            if (bm.matches()) {
                int level = Math.min(3, bm.group(1).length() / 2);
                int s = out.length();
                appendInline(out, bm.group(2));
                out.append('\n');
                out.setSpan(new BulletSpan(dp(8), PURPLE_LIGHT), s, out.length(), SPAN);
                if (level > 0) out.setSpan(new LeadingMarginSpan.Standard(dp(16) * level), s, out.length(), SPAN);
                continue;
            }

            // Daftar bernomor 1. 2.
            Matcher nm = MD_NUMBERED.matcher(ln);
            if (nm.matches()) {
                int level = Math.min(3, nm.group(1).length() / 2);
                int s = out.length();
                out.append(nm.group(2)).append(". ");
                int pe = out.length();
                out.setSpan(new FontSpan(tfBold), s, pe, SPAN);
                out.setSpan(new ForegroundColorSpan(PURPLE_LIGHT), s, pe, SPAN);
                appendInline(out, nm.group(3));
                out.append('\n');
                out.setSpan(new LeadingMarginSpan.Standard(dp(16) * level, dp(16) * level + dp(20)), s, out.length(), SPAN);
                continue;
            }

            // Paragraf biasa
            appendInline(out, t);
            out.append('\n');
        }

        if (inCode) styleCodeBlock(out, codeStart);
        while (out.length() > 0 && out.charAt(out.length() - 1) == '\n') {
            out.delete(out.length() - 1, out.length());
        }
        return out;
    }

    private void ensureNewline(SpannableStringBuilder out) {
        if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') out.append('\n');
    }

    private void ensureBlankLine(SpannableStringBuilder out) {
        if (out.length() == 0) return;
        ensureNewline(out);
        if (out.length() < 2 || out.charAt(out.length() - 2) != '\n') out.append('\n');
    }

    private void styleCodeBlock(SpannableStringBuilder out, int start) {
        int end = out.length();
        if (end > start && out.charAt(end - 1) == '\n') end--;
        if (end <= start) return;
        out.setSpan(new FontSpan(tfMono), start, end, SPAN);
        out.setSpan(new BackgroundColorSpan(CODE_BG), start, end, SPAN);
        out.setSpan(new ForegroundColorSpan(CODE_FG), start, end, SPAN);
        out.setSpan(new RelativeSizeSpan(0.88f), start, end, SPAN);
    }

    /** Format dalam satu baris: **tebal**, *miring*, `kode`, ~~coret~~, [teks](url). */
    private void appendInline(SpannableStringBuilder out, String s) {
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);

            if (c == '\\' && i + 1 < n && "\\`*_{}[]()#+-.!|~>".indexOf(s.charAt(i + 1)) >= 0) {
                out.append(s.charAt(i + 1));
                i += 2;
                continue;
            }

            if (c == '`') {
                int j = s.indexOf('`', i + 1);
                if (j > i + 1) {
                    int st = out.length();
                    out.append(s, i + 1, j);
                    out.setSpan(new FontSpan(tfMono), st, out.length(), SPAN);
                    out.setSpan(new BackgroundColorSpan(INLINE_BG), st, out.length(), SPAN);
                    out.setSpan(new ForegroundColorSpan(PURPLE_LIGHT), st, out.length(), SPAN);
                    i = j + 1;
                    continue;
                }
            }

            if ((c == '*' || c == '_') && i + 1 < n && s.charAt(i + 1) == c) {
                String mk = "" + c + c;
                int j = s.indexOf(mk, i + 2);
                if (j > i + 2) {
                    int st = out.length();
                    appendInline(out, s.substring(i + 2, j));
                    out.setSpan(new FontSpan(tfBold), st, out.length(), SPAN);
                    i = j + 2;
                    continue;
                }
            }

            if (c == '~' && s.startsWith("~~", i)) {
                int j = s.indexOf("~~", i + 2);
                if (j > i + 2) {
                    int st = out.length();
                    appendInline(out, s.substring(i + 2, j));
                    out.setSpan(new StrikethroughSpan(), st, out.length(), SPAN);
                    i = j + 2;
                    continue;
                }
            }

            if (c == '[') {
                int close = s.indexOf("](", i + 1);
                int end = close > 0 ? s.indexOf(')', close + 2) : -1;
                if (close > i && end > close) {
                    int st = out.length();
                    appendInline(out, s.substring(i + 1, close));
                    int le = out.length();
                    out.setSpan(new UnderlineSpan(), st, le, SPAN);
                    out.setSpan(new ForegroundColorSpan(LINK), st, le, SPAN);
                    String url = s.substring(close + 2, end).trim();
                    if (!url.isEmpty()) {
                        int us = out.length();
                        out.append(" (").append(url).append(")");
                        out.setSpan(new ForegroundColorSpan(MUTED), us, out.length(), SPAN);
                    }
                    i = end + 1;
                    continue;
                }
            }

            if (c == '*' && i + 1 < n && !Character.isWhitespace(s.charAt(i + 1))) {
                int j = s.indexOf('*', i + 1);
                if (j > i + 1 && !Character.isWhitespace(s.charAt(j - 1))) {
                    int st = out.length();
                    appendInline(out, s.substring(i + 1, j));
                    out.setSpan(new StyleSpan(Typeface.ITALIC), st, out.length(), SPAN);
                    i = j + 1;
                    continue;
                }
            }

            if (c == '_' && (i == 0 || !Character.isLetterOrDigit(s.charAt(i - 1)))
                    && i + 1 < n && !Character.isWhitespace(s.charAt(i + 1))) {
                int j = s.indexOf('_', i + 1);
                if (j > i + 1 && !Character.isWhitespace(s.charAt(j - 1))
                        && (j + 1 >= n || !Character.isLetterOrDigit(s.charAt(j + 1)))) {
                    int st = out.length();
                    appendInline(out, s.substring(i + 1, j));
                    out.setSpan(new StyleSpan(Typeface.ITALIC), st, out.length(), SPAN);
                    i = j + 1;
                    continue;
                }
            }

            out.append(c);
            i++;
        }
    }

    private void scrollToBottom() {
        if (chatScroll != null) chatScroll.post(() -> chatScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void newChat() {
        if (busy) {
            toast("Tunggu jawaban selesai dulu");
            return;
        }
        while (conversation.length() > 0) conversation.remove(0);
        richContent.clear();
        pending.clear();
        renderAttachBar();
        messages.removeAllViews();
        clearGeneratedFiles();
        saveHistory();
        updateHero();
        if (currentTab == TAB_HISTORY) renderHistory();
        if (currentTab == TAB_PROFILE) renderProfile();
    }

    private void sendMessage() {
        if (busy) return;
        if (processing > 0) {
            toast("Lampiran masih diproses…");
            return;
        }
        String typed = input.getText().toString().trim();
        final List<Attachment> atts = new ArrayList<>(pending);
        if (typed.isEmpty() && atts.isEmpty()) return;
        final String text = typed.isEmpty() ? "Tolong analisis lampiran ini." : typed;
        String model = selectedModel();

        input.setText("");
        pending.clear();
        renderAttachBar();

        try {
            JSONObject userMessage = new JSONObject();
            userMessage.put("role", "user");
            if (atts.isEmpty()) {
                addBubble(text, true);
                userMessage.put("content", text);
                conversation.put(userMessage);
            } else {
                addUserBubbleWithAttachments(text, atts);
                userMessage.put("content", historyText(text, atts));
                int idx = conversation.length();
                conversation.put(userMessage);
                richContent.put(idx, buildRichContent(text, atts));
            }
        } catch (Exception e) {
            toast("Gagal menyiapkan pesan: " + e.getMessage());
            return;
        }
        updateHero();

        JSONArray payloadMessages;
        try {
            payloadMessages = buildPayloadMessages();
        } catch (Exception e) {
            toast("Gagal menyiapkan pesan: " + e.getMessage());
            return;
        }

        setBusy(true);
        addLoadingBubble();

        final String reqId = String.valueOf(System.currentTimeMillis());
        pendingText = text;
        pendingAtts = atts;
        prefs.edit().putString("pending_req", reqId).putString("pending_text", text).apply();
        saveHistory();

        try {
            JSONObject payload = new JSONObject();
            payload.put("model", model);
            payload.put("messages", payloadMessages);
            payload.put("stream", isHermes());

            String base = getServerUrl();
            if (base.isEmpty()) {
                throw new Exception("URL " + backendName() + " belum diisi. Buka Profil > Pengaturan lalu isi Server URL.");
            }
            File pf = ChatService.payloadFile(this, reqId);
            try (OutputStream os = new FileOutputStream(pf)) {
                os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
            }
            ChatService.start(this, reqId, base + "/v1/chat/completions", apiKey(), isHermes(), pf.getAbsolutePath());
        } catch (Exception e) {
            ChatService.writeError(this, reqId, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            handleResult(reqId);
        }
    }

    // ------------------------------------------- Request latar belakang

    private void checkPending() {
        String id = prefs.getString("pending_req", "");
        if (id.isEmpty()) return;

        if (ChatService.resultFile(this, id).exists()) {
            handleResult(id);
            return;
        }
        if (ChatService.ACTIVE.contains(id)) {
            if (!busy) setBusy(true);
            if (!loadingShown) addLoadingBubble();
            scrollToBottom();
            return;
        }
        ChatService.writeError(this, id, "Proses terputus karena aplikasi dihentikan oleh sistem.");
        handleResult(id);
    }

    private String readFileText(File f) throws Exception {
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        }
    }

    /** Tampilkan hasil request (sukses/gagal). Aman dipanggil berulang: hanya diproses sekali. */
    private void handleResult(String id) {
        if (!id.equals(prefs.getString("pending_req", ""))) return;
        File rf = ChatService.resultFile(this, id);
        if (!rf.exists()) return;

        JSONObject r;
        try {
            r = new JSONObject(readFileText(rf));
        } catch (Exception e) {
            r = new JSONObject();
            try { r.put("ok", false).put("error", "Hasil tidak bisa dibaca"); } catch (Exception ignored) { }
        }
        rf.delete();
        ChatService.payloadFile(this, id).delete();

        String savedText = prefs.getString("pending_text", "");
        prefs.edit().remove("pending_req").remove("pending_text").apply();

        if (loadingShown && messages.getChildCount() > 0) {
            messages.removeViewAt(messages.getChildCount() - 1);
        }
        loadingShown = false;

        if (!r.optBoolean("ok")) {
            String err = r.optString("error", "Tidak diketahui");
            int last = conversation.length() - 1;
            if (last >= 0 && conversation.optJSONObject(last) != null
                    && "user".equals(conversation.optJSONObject(last).optString("role"))) {
                conversation.remove(last);
                richContent.remove(last);
                if (messages.getChildCount() > 0) messages.removeViewAt(messages.getChildCount() - 1);
            }
            String failMsg = "Gagal menghubungi " + backendName() + ".\n\n" + readableError(new Exception(err))
                    + (isHermes()
                    ? "\n\nPeriksa URL Worker, kunci (PROXY_KEY), dan apakah InstaBox/Hermes aktif."
                    : "\n\nPeriksa apakah 9Router aktif, URL/port benar, dan API key sesuai.")
                    + (pendingAtts.isEmpty() ? "" : "\n\nCatatan: pastikan model yang dipilih mendukung gambar/vision.");
            addBubble(failMsg + "\n\nPesanmu dikembalikan ke kolom ketik. Kirim ulang bila sudah siap.", false);
            input.setText(savedText);
            input.setSelection(savedText.length());
            pending.addAll(pendingAtts);
            pendingAtts = new ArrayList<>();
            renderAttachBar();
            setBusy(false);
            status.setText("Koneksi perlu diperiksa");
            saveHistory();
            updateHero();
            return;
        }

        String text = r.optString("text", "");
        if (text.trim().isEmpty()) text = "(Model mengirim jawaban kosong.)";
        addBubble(text, false);

        List<String[]> files = new ArrayList<>();
        JSONArray fs = r.optJSONArray("files");
        if (fs != null) {
            for (int k = 0; k < fs.length(); k++) {
                JSONObject f = fs.optJSONObject(k);
                if (f == null) continue;
                String data = f.optString("data", "");
                if (data.isEmpty()) continue;
                files.add(new String[]{
                        f.optString("name", "file"),
                        f.optString("mime", "application/octet-stream"),
                        data});
            }
        }
        if (!files.isEmpty()) addFileChips(files);

        try {
            JSONObject assistantMessage = new JSONObject();
            assistantMessage.put("role", "assistant");
            assistantMessage.put("content", text);
            if (!files.isEmpty()) {
                JSONArray meta = persistFiles(files);
                if (meta.length() > 0) assistantMessage.put("files", meta);
            }
            conversation.put(assistantMessage);
        } catch (Exception ignored) { }

        pendingAtts = new ArrayList<>();
        setBusy(false);
        status.setText("ENC AI • Terhubung ke " + backendName());
        saveHistory();
        updateHero();
    }

    // --------------------------------------------------------- Jaringan

    private void fetchModels(boolean showToast) {
        status.setText("Mengambil daftar model…");
        executor.execute(() -> {
            List<String> found = new ArrayList<>();
            String error = null;
            HttpURLConnection c = null;
            try {
                if (getServerUrl().isEmpty()) throw new Exception("URL " + backendName() + " belum diisi.");
                URL url = new URL(getServerUrl() + "/v1/models");
                c = DohNet.open(url.toString());
                c.setRequestMethod("GET");
                c.setConnectTimeout(10000);
                c.setReadTimeout(15000);
                String key = apiKey();
                if (!key.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + key);
                int code = c.getResponseCode();
                InputStream stream = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
                String response = readAll(stream);
                if (code < 200 || code >= 300) throw new Exception("HTTP " + code + ": " + response);
                JSONObject json = new JSONObject(response);
                JSONArray data = json.optJSONArray("data");
                if (data != null) {
                    for (int i = 0; i < data.length(); i++) {
                        String id = data.getJSONObject(i).optString("id", "");
                        if (!id.isEmpty()) found.add(id);
                    }
                }
            } catch (Exception e) {
                error = readableError(e);
            } finally {
                if (c != null) c.disconnect();
            }

            final List<String> result = found;
            final String err = error;
            mainHandler.post(() -> {
                liveIds.clear();
                if (!result.isEmpty()) {
                    liveIds.addAll(result);
                    setModels(result.toArray(new String[0]));
                    status.setText("Daftar model dimuat: " + result.size());
                    if (showToast) toast("Daftar model berhasil dimuat");
                } else {
                    setModels(new String[]{prefs.getString(lastModelKey(), defaultModel()), defaultModel()});
                    status.setText("Model belum dimuat • periksa koneksi atau pilih model manual");
                    if (showToast) toast("Tidak bisa mengambil model: " + (err == null ? "daftar kosong" : err));
                }
            });
        });
    }

    // ----------------------------------------------------------- Model

    private void setModels(String[] models) {
        String previous = prefs.getString(lastModelKey(), defaultModel());
        allModelIds.clear();
        for (String m : models) {
            if (m == null) continue;
            String t = m.trim();
            if (!t.isEmpty() && !allModelIds.contains(t)) allModelIds.add(t);
        }
        if (!isHermes()) {
            for (String b : BUILTIN_MODELS) {
                if (!allModelIds.contains(b)) allModelIds.add(b);
            }
        }
        if (allModelIds.isEmpty()) allModelIds.add(defaultModel());

        providerKeys.clear();
        for (String id : allModelIds) {
            String key = providerKey(id);
            if (!providerKeys.contains(key)) providerKeys.add(key);
        }
        Collections.sort(providerKeys);

        currentModel = allModelIds.contains(previous) ? previous : allModelIds.get(0);
        currentProviderKey = providerKey(currentModel);
        updateModelPill();
        if (currentTab == TAB_MODEL) renderModelPage();
    }

    private void updateModelPill() {
        if (modelPillText != null) modelPillText.setText(shortModel(currentModel));
    }

    private String shortModel(String id) {
        if (id == null) return "";
        int i = id.lastIndexOf('/');
        return i >= 0 && i < id.length() - 1 ? id.substring(i + 1) : id;
    }

    private String providerKey(String modelId) {
        String id = modelId == null ? "" : modelId.trim().toLowerCase(Locale.ROOT);
        if (id.contains("/")) return id.substring(0, id.indexOf('/'));
        if (id.startsWith("hermes")) return "hermes";
        if (id.startsWith("gpt-") || id.startsWith("chatgpt")) return "openai";
        if (id.startsWith("claude")) return "anthropic";
        if (id.startsWith("gemini")) return "gemini";
        if (id.startsWith("deepseek")) return "deepseek";
        if (id.startsWith("grok")) return "xai";
        if (id.startsWith("llama")) return "meta";
        return "other";
    }

    private String providerName(String key) {
        if ("hermes".equals(key)) return "Hermes";
        if ("openai".equals(key)) return "ChatGPT / OpenAI";
        if ("gemini".equals(key) || "google".equals(key)) return "Google Gemini";
        if ("anthropic".equals(key)) return "Claude / Anthropic";
        if ("deepseek".equals(key)) return "DeepSeek";
        if ("xai".equals(key)) return "Grok / xAI";
        if ("meta".equals(key)) return "Meta Llama";
        if ("mistral".equals(key)) return "Mistral AI";
        if ("qwen".equals(key) || "alibaba".equals(key)) return "Qwen / Alibaba";
        if ("cc".equals(key)) return "Claude Code";
        if ("cx".equals(key)) return "Codex";
        if ("kr".equals(key)) return "Kiro";
        if ("gh".equals(key)) return "GitHub Copilot";
        if ("groq".equals(key)) return "Groq";
        if ("other".equals(key)) return "Lainnya";
        if (key == null || key.length() == 0) return "Lainnya";
        return Character.toUpperCase(key.charAt(0)) + key.substring(1);
    }

    private String selectedModel() {
        String model = currentModel == null || currentModel.trim().isEmpty() ? defaultModel() : currentModel.trim();
        prefs.edit().putString(lastModelKey(), model).apply();
        return model;
    }

    /** Halaman "Pilih Model AI": kartu model terpilih, pencarian, chip provider, daftar, tombol terapkan. */
    private View buildModelPage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(10), dp(16), dp(10));

        page.addView(pageHeader("Pilih Model AI"), new LinearLayout.LayoutParams(-1, dp(46)));

        LinearLayout feat = new LinearLayout(this);
        feat.setOrientation(LinearLayout.VERTICAL);
        feat.setPadding(dp(18), dp(16), dp(18), dp(16));
        GradientDrawable fg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{PURPLE_DEEP, BLUE});
        fg.setCornerRadius(dp(22));
        feat.setBackground(fg);
        feat.addView(label("Model dipilih", Color.argb(205, 255, 255, 255), 12, false));
        modelFeaturedName = label("", Color.WHITE, 22, true);
        modelFeaturedName.setSingleLine(true);
        modelFeaturedName.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams fnp = new LinearLayout.LayoutParams(-2, -2);
        fnp.topMargin = dp(4);
        feat.addView(modelFeaturedName, fnp);
        modelFeaturedSub = label("", Color.argb(220, 255, 255, 255), 13, false);
        feat.addView(modelFeaturedSub);
        LinearLayout.LayoutParams featp = new LinearLayout.LayoutParams(-1, -2);
        featp.topMargin = dp(12);
        page.addView(feat, featp);

        modelSearch = styledInput("Cari model…", "");
        modelSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) {
                modelState[3] = e.toString().trim();
                modelState[1] = modelState[3].toLowerCase(Locale.ROOT);
                renderModelPage();
            }
        });
        LinearLayout.LayoutParams srp = new LinearLayout.LayoutParams(-1, -2);
        srp.topMargin = dp(12);
        page.addView(modelSearch, srp);

        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        modelChipRow = new LinearLayout(this);
        modelChipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipScroll.addView(modelChipRow);
        LinearLayout.LayoutParams chp = new LinearLayout.LayoutParams(-1, -2);
        chp.topMargin = dp(12);
        page.addView(chipScroll, chp);

        modelScroll = new ScrollView(this);
        modelScroll.setVerticalScrollBarEnabled(false);
        modelList = new LinearLayout(this);
        modelList.setOrientation(LinearLayout.VERTICAL);
        modelScroll.addView(modelList);
        LinearLayout.LayoutParams lsp = new LinearLayout.LayoutParams(-1, 0, 1);
        lsp.topMargin = dp(10);
        page.addView(modelScroll, lsp);

        modelApplyBtn = label("Gunakan Model Ini", Color.WHITE, 15, true);
        modelApplyBtn.setGravity(Gravity.CENTER);
        modelApplyBtn.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable ab = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{PURPLE, PURPLE_DEEP});
        ab.setCornerRadius(dp(26));
        modelApplyBtn.setBackground(ab);
        pressable(modelApplyBtn);
        modelApplyBtn.setOnClickListener(v -> applyPendingModel());
        LinearLayout.LayoutParams abp = new LinearLayout.LayoutParams(-1, -2);
        abp.topMargin = dp(10);
        page.addView(modelApplyBtn, abp);
        return page;
    }

    private void applyPendingModel() {
        String id = modelState[2] == null ? "" : modelState[2].trim();
        if (id.isEmpty()) return;
        if (!allModelIds.contains(id)) allModelIds.add(id);
        currentModel = id;
        currentProviderKey = providerKey(id);
        if (!providerKeys.contains(currentProviderKey)) providerKeys.add(currentProviderKey);
        prefs.edit().putString(lastModelKey(), id).apply();
        updateModelPill();
        toast("Model aktif: " + shortModel(id));
        showTab(TAB_CHAT);
    }

    private void renderModelPage() {
        if (modelList == null || modelChipRow == null) return;
        String cur = modelState[2] == null || modelState[2].isEmpty() ? currentModel : modelState[2];
        modelFeaturedName.setText(shortModel(cur));
        modelFeaturedSub.setText(providerName(providerKey(cur))
                + (cur.equals(currentModel) ? " • sedang dipakai" : " • belum diterapkan"));
        modelApplyBtn.setAlpha(cur.equals(currentModel) ? 0.6f : 1f);

        // Chip provider
        modelChipRow.removeAllViews();
        List<String> keys = new ArrayList<>();
        keys.add("*");
        keys.addAll(providerKeys);
        for (final String key : keys) {
            boolean selected = key.equals(modelState[0]) && modelState[1].isEmpty();
            TextView chip = label("*".equals(key) ? "Semua" : providerName(key), selected ? Color.WHITE : MUTED, 13, selected);
            chip.setPadding(dp(14), dp(8), dp(14), dp(8));
            if (selected) {
                GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{PURPLE, PURPLE_DEEP});
                g.setCornerRadius(dp(18));
                chip.setBackground(g);
            } else {
                chip.setBackground(rounded(CARD, 18, CARD_STROKE));
            }
            pressable(chip);
            chip.setOnClickListener(v -> {
                modelState[0] = key;
                renderModelPage();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(8);
            modelChipRow.addView(chip, lp);
        }

        // Daftar model
        modelList.removeAllViews();
        final String q = modelState[1];
        final String raw = modelState[3];
        List<String> shown = new ArrayList<>();
        for (String id : allModelIds) {
            boolean matchProvider = !q.isEmpty() || "*".equals(modelState[0]) || providerKey(id).equals(modelState[0]);
            boolean matchQuery = q.isEmpty() || id.toLowerCase(Locale.ROOT).contains(q);
            if (matchProvider && matchQuery) shown.add(id);
        }
        if (!raw.isEmpty() && !allModelIds.contains(raw)) {
            TextView custom = label("Pakai \"" + raw + "\" sebagai ID model", PURPLE_LIGHT, 13, true);
            custom.setPadding(dp(14), dp(12), dp(14), dp(12));
            custom.setBackground(rounded(CARD, 14, PURPLE));
            pressable(custom);
            custom.setOnClickListener(v -> {
                modelState[2] = raw;
                applyPendingModel();
            });
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
            cl.setMargins(0, dp(2), 0, dp(8));
            modelList.addView(custom, cl);
        }
        if (shown.isEmpty()) {
            TextView empty = label("Tidak ada model yang cocok.", MUTED, 13, false);
            empty.setPadding(dp(4), dp(16), dp(4), dp(16));
            modelList.addView(empty);
            return;
        }
        int limit = Math.min(shown.size(), 150);
        for (int i = 0; i < limit; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = dp(8);
            modelList.addView(modelRow(shown.get(i)), lp);
        }
        if (shown.size() > limit) {
            TextView more = label("Menampilkan " + limit + " dari " + shown.size() + " model. Gunakan pencarian untuk mempersempit.", MUTED, 11, false);
            more.setPadding(dp(4), dp(6), dp(4), dp(10));
            modelList.addView(more);
        }
    }

    private View modelRow(final String id) {
        boolean selected = id.equals(modelState[2]);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(12), dp(14), dp(12));
        row.setBackground(rounded(selected ? ACCENT_SOFT : CARD, 16, selected ? PURPLE : CARD_STROKE));
        pressable(row);

        String pn = providerName(providerKey(id));
        TextView badge = label(pn.substring(0, 1).toUpperCase(Locale.ROOT), PURPLE_LIGHT, 16, true);
        badge.setGravity(Gravity.CENTER);
        GradientDrawable bd = new GradientDrawable();
        bd.setShape(GradientDrawable.OVAL);
        bd.setColor(selected ? CARD : ACCENT_SOFT);
        badge.setBackground(bd);
        row.addView(badge, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView name = label(shortModel(id), TEXT, 14, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(name);
        TextView sub = label(pn + (liveIds.contains(id) ? "" : " · bawaan") + (id.equals(currentModel) ? " · aktif" : ""), MUTED, 11, false);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(sub);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1);
        cl.leftMargin = dp(12);
        cl.rightMargin = dp(8);
        row.addView(col, cl);

        if (selected) {
            FrameLayout check = new FrameLayout(this);
            GradientDrawable cd = new GradientDrawable();
            cd.setShape(GradientDrawable.OVAL);
            cd.setColor(PURPLE);
            check.setBackground(cd);
            check.addView(new IconView(this, IC_CHECK, Color.WHITE), new FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER));
            row.addView(check, new LinearLayout.LayoutParams(dp(24), dp(24)));
        }

        row.setOnClickListener(v -> {
            final int y = modelScroll.getScrollY();
            modelState[2] = id;
            renderModelPage();
            modelScroll.post(() -> modelScroll.scrollTo(0, y));
        });
        return row;
    }

    // ------------------------------------------------------- Dialog koneksi

    private EditText styledInput(String hint, String text) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setHint(hint);
        e.setText(text);
        e.setTypeface(tfRegular);
        e.setTextColor(TEXT);
        e.setHintTextColor(MUTED);
        e.setTextSize(14);
        e.setPadding(dp(14), dp(10), dp(14), dp(10));
        e.setBackground(rounded(CARD, 14, CARD_STROKE));
        return e;
    }

    private TextView segmentButton(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(14);
        t.setTypeface(tfBold);
        pressable(t);
        return t;
    }

    private void styleSegment(TextView t, boolean active) {
        t.setTextColor(active ? Color.WHITE : MUTED);
        if (active) {
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{PURPLE, BLUE});
            g.setCornerRadius(dp(14));
            t.setBackground(g);
        } else {
            t.setBackground(rounded(CARD, 14, CARD_STROKE));
        }
    }

    private void addField(LinearLayout parent, String caption, EditText field, int topDp) {
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2);
        l.topMargin = dp(topDp);
        parent.addView(label(caption, PURPLE_LIGHT, 12, true), l);
        LinearLayout.LayoutParams f = new LinearLayout.LayoutParams(-1, -2);
        f.topMargin = dp(6);
        parent.addView(field, f);
    }

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(20), dp(22), dp(4));

        box.addView(label("Pengaturan koneksi", TEXT, 18, true));

        // Pemilih backend aktif: 9Router atau Hermes
        final String[] sel = {currentBackend()};
        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams segp = new LinearLayout.LayoutParams(-1, dp(44));
        segp.topMargin = dp(16);
        box.addView(seg, segp);
        final TextView tabRouter = segmentButton("9Router");
        final TextView tabHermes = segmentButton("Hermes");
        LinearLayout.LayoutParams t1 = new LinearLayout.LayoutParams(0, -1, 1);
        t1.rightMargin = dp(4);
        LinearLayout.LayoutParams t2 = new LinearLayout.LayoutParams(0, -1, 1);
        t2.leftMargin = dp(4);
        seg.addView(tabRouter, t1);
        seg.addView(tabHermes, t2);

        // Bagian 9Router
        final LinearLayout routerBox = new LinearLayout(this);
        routerBox.setOrientation(LinearLayout.VERTICAL);
        EditText server = styledInput("http://127.0.0.1:20128", prefs.getString("server_url", DEFAULT_URL));
        addField(routerBox, "Server URL 9Router", server, 16);
        EditText key = styledInput("API key 9Router (jika diperlukan)", prefs.getString("api_key", ""));
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setTypeface(tfRegular);
        addField(routerBox, "API Key 9Router", key, 14);
        TextView routerNote = label("Server lokal hanya dapat diakses saat 9Router aktif di HP ini. Jangan bagikan API key.", MUTED, 12, false);
        LinearLayout.LayoutParams rn = new LinearLayout.LayoutParams(-1, -2);
        rn.topMargin = dp(12);
        routerBox.addView(routerNote, rn);
        box.addView(routerBox, new LinearLayout.LayoutParams(-1, -2));

        // Bagian Hermes
        final LinearLayout hermesBox = new LinearLayout(this);
        hermesBox.setOrientation(LinearLayout.VERTICAL);
        EditText hUrl = styledInput("https://nama-worker.akunmu.workers.dev", prefs.getString("hermes_url", ""));
        addField(hermesBox, "Alamat Worker Hermes", hUrl, 16);
        EditText hKey = styledInput("PROXY_KEY dari Worker", prefs.getString("hermes_key", ""));
        hKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        hKey.setTypeface(tfRegular);
        addField(hermesBox, "Kunci Worker (PROXY_KEY)", hKey, 14);
        TextView hermesNote = label("Hermes dijalankan lewat Worker; InstaBox dibangunkan otomatis bila tidur, jadi balasan pertama bisa lebih lama. Jangan bagikan kunci.", MUTED, 12, false);
        LinearLayout.LayoutParams hn = new LinearLayout.LayoutParams(-1, -2);
        hn.topMargin = dp(12);
        hermesBox.addView(hermesNote, hn);
        box.addView(hermesBox, new LinearLayout.LayoutParams(-1, -2));

        EditText manualModel = styledInput("Isi hanya jika model tidak muncul di daftar", selectedModel());
        addField(box, "Model cadangan (opsional, untuk backend aktif)", manualModel, 14);

        final Runnable refresh = () -> {
            boolean h = BACKEND_HERMES.equals(sel[0]);
            routerBox.setVisibility(h ? View.GONE : View.VISIBLE);
            hermesBox.setVisibility(h ? View.VISIBLE : View.GONE);
            styleSegment(tabRouter, !h);
            styleSegment(tabHermes, h);
        };
        tabRouter.setOnClickListener(v -> { sel[0] = BACKEND_9ROUTER; refresh.run(); });
        tabHermes.setOnClickListener(v -> { sel[0] = BACKEND_HERMES; refresh.run(); });
        refresh.run();

        ScrollView sc = new ScrollView(this);
        sc.addView(box);
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setView(sc)
                .setNegativeButton("Batal", null)
                .setNeutralButton("Muat Model", (dialog, which) -> {
                    saveSettings(sel[0], server.getText().toString(), key.getText().toString(),
                            hUrl.getText().toString(), hKey.getText().toString(), manualModel.getText().toString());
                    fetchModels(true);
                })
                .setPositiveButton("Simpan", (dialog, which) -> {
                    saveSettings(sel[0], server.getText().toString(), key.getText().toString(),
                            hUrl.getText().toString(), hKey.getText().toString(), manualModel.getText().toString());
                    fetchModels(false);
                    toast("Pengaturan disimpan • " + backendName());
                    if (currentTab == TAB_SETTINGS) renderSettings();
                })
                .create();
        showStyled(dlg);
    }

    /** Rapikan URL: buang spasi, slash akhir, dan akhiran /v1 (app menambahkannya sendiri). */
    private String cleanUrl(String raw, boolean addHttps) {
        String u = raw == null ? "" : raw.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.endsWith("/v1")) u = u.substring(0, u.length() - 3);
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (addHttps && !u.isEmpty() && !u.startsWith("http://") && !u.startsWith("https://")) u = "https://" + u;
        return u;
    }

    private void saveSettings(String backend, String server, String key, String hUrl, String hKey, String model) {
        boolean changed = !backend.equals(currentBackend());
        String cleanServer = cleanUrl(server, false);
        if (cleanServer.isEmpty()) cleanServer = DEFAULT_URL;
        prefs.edit()
                .putString("backend", backend)
                .putString("server_url", cleanServer)
                .putString("api_key", key == null ? "" : key.trim())
                .putString("hermes_url", cleanUrl(hUrl, true))
                .putString("hermes_key", hKey == null ? "" : hKey.trim())
                .apply();
        String cleanModel = model == null ? "" : model.trim();
        if (!changed) {
            prefs.edit().putString(lastModelKey(), cleanModel.isEmpty() ? defaultModel() : cleanModel).apply();
        }
        status.setText("ENC AI • Siap terhubung ke " + backendName());
        setModels(new String[]{prefs.getString(lastModelKey(), defaultModel())});
    }

    // ------------------------------------------------------- Backend aktif

    private String currentBackend() {
        return BACKEND_HERMES.equals(prefs.getString("backend", BACKEND_9ROUTER)) ? BACKEND_HERMES : BACKEND_9ROUTER;
    }

    private boolean isHermes() {
        return BACKEND_HERMES.equals(currentBackend());
    }

    private String backendName() {
        return isHermes() ? "Hermes" : "9Router";
    }

    private String lastModelKey() {
        return isHermes() ? "last_model_hermes" : "last_model";
    }

    private String defaultModel() {
        return isHermes() ? HERMES_MODEL : DEFAULT_MODEL;
    }

    private String apiKey() {
        return prefs.getString(isHermes() ? "hermes_key" : "api_key", "").trim();
    }

    private String getServerUrl() {
        if (isHermes()) {
            String h = prefs.getString("hermes_url", "").trim();
            while (h.endsWith("/")) h = h.substring(0, h.length() - 1);
            return h;
        }
        String url = prefs.getString("server_url", DEFAULT_URL).trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        return url.isEmpty() ? DEFAULT_URL : url;
    }

    private void setBusy(boolean value) {
        busy = value;
        sendButton.setEnabled(!value);
        sendButton.setAlpha(value ? 0.5f : 1f);
    }

    private String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) out.append(line);
        }
        return out.toString();
    }

    private String readableError(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.trim().isEmpty()) msg = e.getClass().getSimpleName();
        if (msg.contains("ECONNREFUSED") || msg.contains("Connection refused")) {
            return "Koneksi ditolak. Pastikan " + backendName() + " aktif dan alamat/port server benar.";
        }
        if (msg.contains("Cleartext HTTP traffic not permitted")) {
            return "HTTP lokal diblokir. Periksa usesCleartextTraffic pada AndroidManifest.xml.";
        }
        return msg;
    }

    // ---------------------------------------------------------- Riwayat

    private void saveHistory() {
        // Simpan riwayat percakapan: teks + metadata file hasil Hermes (isi file ada di filesDir/generated).
        prefs.edit().putString("conversation_json", conversation.toString()).apply();
    }

    private void loadHistory() {
        String saved = prefs.getString("conversation_json", "");
        if (!saved.isEmpty()) {
            try {
                JSONArray arr = new JSONArray(saved);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject item = arr.getJSONObject(i);
                    String role = item.optString("role", "");
                    String content = item.optString("content", "");
                    if ("user".equals(role)) {
                        addBubble(content, true);
                    } else if ("assistant".equals(role)) {
                        addBubble(content, false);
                        JSONArray fm = item.optJSONArray("files");
                        if (fm != null) {
                            List<String[]> restored = restoreFiles(fm);
                            if (!restored.isEmpty()) addFileChips(restored);
                        }
                    }
                    conversation.put(item);
                }
            } catch (Exception e) {
                prefs.edit().remove("conversation_json").apply();
                while (conversation.length() > 0) conversation.remove(0);
                messages.removeAllViews();
            }
        }
        updateHero();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        for (ObjectAnimator a : animators) a.cancel();
        executor.shutdownNow();
    }
}
