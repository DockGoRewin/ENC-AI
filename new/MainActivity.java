package com.dockgo.encai;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Outline;
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
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.BulletSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.QuoteSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;
import android.util.Base64;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
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

    // Batas lampiran
    private static final int REQ_PICK_FILES = 1001;
    private static final int REQ_CAMERA = 1002;
    private static final long MAX_FILE_BYTES = 20L * 1024 * 1024; // 20 MB per file
    private static final int MAX_ATTACHMENTS = 6;
    private static final int MAX_IMAGE_DIM = 1280;                // sisi terpanjang gambar yang dikirim
    private static final int MAX_PDF_PAGES = 4;                   // halaman PDF yang dirender jadi gambar
    private static final int MAX_TEXT_CHARS = 100000;             // batas karakter file teks
    private static final Set<String> TEXT_EXT = new HashSet<>(Arrays.asList(
            "txt", "md", "csv", "tsv", "json", "xml", "html", "htm", "css", "js", "ts", "java", "kt",
            "py", "c", "cpp", "h", "hpp", "cs", "sh", "yml", "yaml", "log", "sql", "ini", "toml",
            "gradle", "properties", "php", "rb", "go", "rs", "swift", "dart", "tex", "srt"));

    // Katalog bawaan: tampil di pemilih model walau /v1/models gagal dimuat.
    // Awalan (gemini/, openai/, cc/, ...) harus sama dengan yang dipakai 9Router kamu.
    private static final String[] BUILTIN_MODELS = {
            // Google Gemini (API key)
            "gemini/gemini-4-argon", "gemini/gemini-3.8-flash", "gemini/gemini-3.7-flash", "gemini/gemini-3.6-flash",
            "gemini/gemini-3.5-flash", "gemini/gemini-3.5-flash-lite", "gemini/gemini-3.1-pro-preview",
            "gemini/gemini-3.1-flash-lite", "gemini/gemini-3-flash-preview",
            "gemini/gemini-2.5-flash", "gemini/gemini-2.5-pro", "gemini/gemini-2.5-flash-lite",
            // Google Gemma (model terbuka lewat Gemini API)
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

    // Palet warna (dark navy + ungu/biru, mengikuti referensi)
    private final int BG_TOP = Color.rgb(5, 6, 14);
    private final int BG_BOTTOM = Color.rgb(12, 16, 38);
    private final int CARD = Color.rgb(20, 24, 42);
    private final int CARD_STROKE = Color.rgb(38, 44, 74);
    private final int SHEET = Color.rgb(14, 17, 32);
    private final int PURPLE = Color.rgb(139, 92, 246);
    private final int PURPLE_DEEP = Color.rgb(109, 67, 232);
    private final int PURPLE_LIGHT = Color.rgb(196, 181, 253);
    private final int BLUE = Color.rgb(59, 130, 246);
    private final int TEXT = Color.rgb(245, 247, 250);
    private final int MUTED = Color.rgb(148, 156, 180);
    private final int GREEN = Color.rgb(52, 211, 153);

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

    private LinearLayout root, messages, attachBar;
    private HorizontalScrollView attachScroll;
    private ScrollView chatScroll, heroScroll;
    private EditText input;
    private TextView status, sendButton, modelPillText;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        getWindow().setStatusBarColor(BG_TOP);
        getWindow().setNavigationBarColor(BG_BOTTOM);
        getWindow().getDecorView().setSystemUiVisibility(0);
        buildUi();
        loadHistory();
        fetchModels(false);
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(gradient(BG_TOP, BG_BOTTOM, GradientDrawable.Orientation.TOP_BOTTOM, 0));
        root.setPadding(dp(16), dp(10), dp(16), dp(12));
        // Hindari tertutup status bar, tombol navigasi, dan keyboard
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(dp(16) + insets.getSystemWindowInsetLeft(),
                    dp(10) + insets.getSystemWindowInsetTop(),
                    dp(16) + insets.getSystemWindowInsetRight(),
                    dp(12) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(root);
        root.requestApplyInsets();

        // Bar atas: [+ chat baru]   [● model ▾]   [⚙]
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(top, new LinearLayout.LayoutParams(-1, dp(52)));

        TextView newChat = circleButton("+");
        newChat.setTextSize(24);
        newChat.setOnClickListener(v -> newChat());
        top.addView(newChat, new LinearLayout.LayoutParams(dp(42), dp(42)));

        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));

        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(14), 0, dp(12), 0);
        pill.setBackground(rounded(CARD, 21, CARD_STROKE));
        pressable(pill);
        pill.setOnClickListener(v -> showModelPicker());

        View dot = new View(this);
        GradientDrawable dd = new GradientDrawable();
        dd.setShape(GradientDrawable.OVAL);
        dd.setColor(GREEN);
        dot.setBackground(dd);
        pill.addView(dot, new LinearLayout.LayoutParams(dp(8), dp(8)));

        modelPillText = label("", TEXT, 13, true);
        modelPillText.setSingleLine(true);
        modelPillText.setEllipsize(TextUtils.TruncateAt.END);
        modelPillText.setMaxWidth(dp(170));
        LinearLayout.LayoutParams mtp = new LinearLayout.LayoutParams(-2, -2);
        mtp.leftMargin = dp(8);
        pill.addView(modelPillText, mtp);

        TextView caret = label("▾", MUTED, 13, false);
        LinearLayout.LayoutParams cap = new LinearLayout.LayoutParams(-2, -2);
        cap.leftMargin = dp(6);
        pill.addView(caret, cap);
        top.addView(pill, new LinearLayout.LayoutParams(-2, dp(42)));

        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));

        TextView settings = circleButton("⚙");
        settings.setOnClickListener(v -> showSettings());
        top.addView(settings, new LinearLayout.LayoutParams(dp(42), dp(42)));

        // Area tengah: hero (kosong) atau daftar chat
        FrameLayout chatArea = new FrameLayout(this);
        root.addView(chatArea, new LinearLayout.LayoutParams(-1, 0, 1));

        chatArea.addView(buildHero(), new FrameLayout.LayoutParams(-1, -1));

        chatScroll = new ScrollView(this);
        chatScroll.setFillViewport(false);
        chatScroll.setClipToPadding(false);
        chatScroll.setVerticalScrollBarEnabled(false);
        chatArea.addView(chatScroll, new FrameLayout.LayoutParams(-1, -1));

        messages = new LinearLayout(this);
        messages.setOrientation(LinearLayout.VERTICAL);
        messages.setPadding(0, dp(6), 0, dp(12));
        chatScroll.addView(messages);

        // Bar lampiran (muncul di atas composer bila ada file terpilih)
        attachScroll = new HorizontalScrollView(this);
        attachScroll.setHorizontalScrollBarEnabled(false);
        attachScroll.setVisibility(View.GONE);
        attachBar = new LinearLayout(this);
        attachBar.setOrientation(LinearLayout.HORIZONTAL);
        attachScroll.addView(attachBar);
        LinearLayout.LayoutParams asp = new LinearLayout.LayoutParams(-1, -2);
        asp.topMargin = dp(8);
        root.addView(attachScroll, asp);

        // Composer berbentuk pil
        LinearLayout composer = new LinearLayout(this);
        composer.setOrientation(LinearLayout.HORIZONTAL);
        composer.setGravity(Gravity.BOTTOM);
        composer.setPadding(dp(6), dp(6), dp(6), dp(6));
        composer.setBackground(rounded(CARD, 28, CARD_STROKE));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.topMargin = dp(8);
        root.addView(composer, cp);

        // Tombol lampiran
        TextView attach = circleButton("📎");
        attach.setTextSize(17);
        attach.setOnClickListener(v -> showAttachMenu());
        LinearLayout.LayoutParams atp = new LinearLayout.LayoutParams(dp(44), dp(44));
        composer.addView(attach, atp);

        input = new EditText(this);
        input.setTextColor(TEXT);
        input.setHintTextColor(MUTED);
        input.setHint("Tanya ENC AI apa saja...");
        input.setTextSize(15);
        input.setPadding(dp(12), dp(10), dp(8), dp(10));
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setMinHeight(dp(44));
        input.setMaxLines(5);
        input.setSingleLine(false);
        composer.addView(input, new LinearLayout.LayoutParams(0, -2, 1));

        sendButton = new TextView(this);
        sendButton.setText("↑");
        sendButton.setTextColor(Color.WHITE);
        sendButton.setTextSize(22);
        sendButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        sendButton.setGravity(Gravity.CENTER);
        GradientDrawable sd = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{PURPLE, BLUE});
        sd.setShape(GradientDrawable.OVAL);
        sendButton.setBackground(sd);
        pressable(sendButton);
        sendButton.setOnClickListener(v -> sendMessage());
        LinearLayout.LayoutParams sendp = new LinearLayout.LayoutParams(dp(44), dp(44));
        sendp.leftMargin = dp(4);
        composer.addView(sendButton, sendp);

        status = label("ENC AI • Siap terhubung ke " + backendName(), MUTED, 10, false);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(8), 0, 0);
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));

        setModels(new String[]{prefs.getString(lastModelKey(), defaultModel()), defaultModel()});
    }

    private View buildHero() {
        heroScroll = new ScrollView(this);
        heroScroll.setVerticalScrollBarEnabled(false);
        heroScroll.setFillViewport(true);

        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER);
        hero.setPadding(0, dp(8), 0, dp(8));
        heroScroll.addView(hero, new FrameLayout.LayoutParams(-1, -2));

        hero.addView(orb(150, true), new LinearLayout.LayoutParams(dp(150), dp(150)));

        TextView hi = label("Halo 👋", PURPLE_LIGHT, 14, true);
        hi.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hip = new LinearLayout.LayoutParams(-1, -2);
        hip.topMargin = dp(14);
        hero.addView(hi, hip);

        TextView heading = label("Ada yang bisa kubantu?", TEXT, 26, true);
        heading.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-1, -2);
        hp.topMargin = dp(4);
        hero.addView(heading, hp);

        TextView subtitle = label("Pilih model di atas, lalu tanya apa saja, cari ide, atau lampirkan gambar dan file.", MUTED, 13, false);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setPadding(dp(18), 0, dp(18), 0);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
        sp.topMargin = dp(8);
        hero.addView(subtitle, sp);

        String[][] items = {
                {"💡", "Ide kreatif", "Brainstorm apa saja", "Beri aku ide kreatif tentang "},
                {"⌨️", "Bantu kode", "Tulis atau perbaiki kode", "Bantu aku menulis kode untuk "},
                {"🌐", "Terjemahkan", "Ke bahasa apa pun", "Terjemahkan teks berikut ke bahasa Inggris:\n\n"},
                {"📝", "Ringkas", "Padatkan teks panjang", "Ringkas teks berikut:\n\n"}
        };
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(-1, -2);
        gp.topMargin = dp(22);
        hero.addView(grid, gp);
        for (int r = 0; r < 2; r++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            grid.addView(row, new LinearLayout.LayoutParams(-1, -2));
            for (int c = 0; c < 2; c++) {
                final String[] it = items[r * 2 + c];
                LinearLayout card = new LinearLayout(this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(dp(14), dp(12), dp(14), dp(12));
                card.setBackground(rounded(CARD, 18, CARD_STROKE));
                pressable(card);
                card.setOnClickListener(v -> fillPrompt(it[3]));
                card.addView(label(it[0], TEXT, 20, false));
                TextView t = label(it[1], TEXT, 14, true);
                LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
                tp.topMargin = dp(6);
                card.addView(t, tp);
                card.addView(label(it[2], MUTED, 11, false));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
                lp.setMargins(dp(5), dp(5), dp(5), dp(5));
                row.addView(card, lp);
            }
        }
        return heroScroll;
    }

    private void fillPrompt(String prompt) {
        input.setText(prompt);
        input.setSelection(prompt.length());
        input.requestFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
    }

    private void updateHero() {
        boolean empty = conversation.length() == 0 && messages.getChildCount() == 0;
        heroScroll.setVisibility(empty ? View.VISIBLE : View.GONE);
        chatScroll.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    /** Orb bercahaya (inti radial gradien + cincin tipis). */
    private FrameLayout orb(int sizeDp, boolean pulse) {
        FrameLayout f = new FrameLayout(this);

        View glow = new View(this);
        GradientDrawable gg = new GradientDrawable();
        gg.setShape(GradientDrawable.OVAL);
        gg.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        gg.setGradientRadius(dp(sizeDp) * 0.5f);
        gg.setColors(new int[]{Color.argb(110, 124, 92, 255), Color.argb(0, 124, 92, 255)});
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
                Color.rgb(196, 181, 253), Color.rgb(99, 102, 241),
                Color.rgb(59, 30, 140), Color.rgb(20, 16, 60)});
        cg.setStroke(dp(1), Color.argb(120, 196, 181, 253));
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

    private TextView circleButton(String glyph) {
        TextView t = new TextView(this);
        t.setText(glyph);
        t.setTextColor(TEXT);
        t.setTextSize(18);
        t.setGravity(Gravity.CENTER);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(CARD);
        d.setStroke(dp(1), CARD_STROKE);
        t.setBackground(d);
        pressable(t);
        return t;
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
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
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
                {"📷", "Ambil foto", "Buka kamera"},
                {"🖼️", "Gambar & file", "Galeri, PDF, DOCX, teks/kode"}
        };
        for (int i = 0; i < opts.length; i++) {
            final int which = i;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(12), dp(14), dp(12));
            row.setBackground(rounded(CARD, 16, CARD_STROKE));
            pressable(row);
            row.addView(label(opts[i][0], TEXT, 22, false));
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.addView(label(opts[i][1], TEXT, 15, true));
            col.addView(label(opts[i][2], MUTED, 12, false));
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
                TextView icon = label("📄", TEXT, 20, false);
                icon.setGravity(Gravity.CENTER);
                chip.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));
            }

            TextView name = label(a.name, TEXT, 12, false);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            name.setMaxWidth(dp(120));
            LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-2, -2);
            np.leftMargin = dp(8);
            chip.addView(name, np);

            TextView close = label("✕", MUTED, 14, true);
            close.setPadding(dp(10), dp(4), dp(2), dp(4));
            pressable(close);
            close.setOnClickListener(v -> {
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
        StringBuilder sb = new StringBuilder();
        boolean truncated = false;
        try (InputStream in = getContentResolver().openInputStream(uri);
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) != -1) {
                if (sb.length() + n > MAX_TEXT_CHARS) {
                    sb.append(buf, 0, MAX_TEXT_CHARS - sb.length());
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
                    if (t.length() > MAX_TEXT_CHARS) t = t.substring(0, MAX_TEXT_CHARS) + "\n[... dipotong ...]";
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
        for (Attachment a : atts) {
            if ("text".equals(a.kind)) {
                full.append("\n\n--- Isi file: ").append(a.name).append(" ---\n")
                        .append(a.text).append("\n--- Akhir file ---");
            } else if ("pdf".equals(a.kind)) {
                full.append("\n\n[").append(a.name).append(": ").append(a.note).append("]");
            } else {
                full.append("\n\n[Gambar terlampir: ").append(a.name).append("]");
            }
            if (!a.dataUrls.isEmpty()) hasImages = true;
        }
        if (!hasImages) return full.toString();

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
            // Hermes lambat memproses gambar: kirim gambar hanya pada giliran saat dilampirkan,
            // pesan lama cukup teksnya (penanda lampiran tetap ada).
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
    }

    /** Bubble pengguna dengan pratinjau lampiran. */
    private void addUserBubbleWithAttachments(String text, List<Attachment> atts) {
        int maxW = (int) (getResources().getDisplayMetrics().widthPixels * 0.76f);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{PURPLE_DEEP, Color.rgb(79, 70, 229)});
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
                    TextView cap = label("📄 " + a.name, Color.WHITE, 12, false);
                    cap.setSingleLine(true);
                    cap.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                    cap.setPadding(dp(4), 0, dp(4), dp(6));
                    box.addView(cap);
                }
            } else {
                TextView f = label("📄 " + a.name, Color.WHITE, 13, false);
                f.setSingleLine(true);
                f.setEllipsize(TextUtils.TruncateAt.MIDDLE);
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
                    new int[]{PURPLE_DEEP, Color.rgb(79, 70, 229)});
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
        row.addView(orb(32, false), ap);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        row.addView(column, new LinearLayout.LayoutParams(0, -2, 1));

        final StringBuilder plain = new StringBuilder();
        if (loading) {
            TextView body = label(text, MUTED, 15, false);
            body.setTypeface(Typeface.DEFAULT, Typeface.ITALIC);
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
            // Teks berformat + kartu kode (judul "Kode", tombol salin & perbesar)
            View bubble = buildAssistantContent(text, maxW, plain);
            boolean hasCode = text.contains("```");
            column.addView(bubble, new LinearLayout.LayoutParams(hasCode ? -1 : -2, -2));

            TextView copy = label("⧉  Salin", MUTED, 12, false);
            copy.setPadding(dp(6), dp(6), dp(10), dp(4));
            pressable(copy);
            copy.setOnClickListener(v -> copyText(plain.toString().trim(), "Jawaban disalin"));
            column.addView(copy, new LinearLayout.LayoutParams(-2, -2));
        }
        scrollToBottom();
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
        card.setBackground(rounded(Color.rgb(10, 12, 26), 12, CARD_STROKE));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), dp(2), dp(4), dp(2));
        GradientDrawable hbg = new GradientDrawable();
        hbg.setColor(Color.rgb(24, 28, 50));
        setCorners(hbg, 12, 12, 0, 0);
        header.setBackground(hbg);

        TextView name = label(title, MUTED, 12, true);
        header.addView(name, new LinearLayout.LayoutParams(0, -2, 1));

        TextView copy = label("⧉", TEXT, 17, false);
        copy.setGravity(Gravity.CENTER);
        copy.setPadding(dp(10), dp(8), dp(10), dp(8));
        pressable(copy);
        copy.setOnClickListener(v -> copyText(code, "Kode disalin"));
        header.addView(copy, new LinearLayout.LayoutParams(-2, -2));

        TextView expand = label("⤢", TEXT, 17, false);
        expand.setGravity(Gravity.CENTER);
        expand.setPadding(dp(10), dp(8), dp(10), dp(8));
        pressable(expand);
        expand.setOnClickListener(v -> showCodeFullscreen(title, code));
        header.addView(expand, new LinearLayout.LayoutParams(-2, -2));
        card.addView(header, new LinearLayout.LayoutParams(-1, -2));

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        TextView tv = new TextView(this);
        tv.setText(code);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(12.5f);
        tv.setTextColor(Color.rgb(190, 242, 220));
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(12), dp(10), dp(12), dp(10));
        int lines = 1;
        for (int i = 0; i < code.length(); i++) if (code.charAt(i) == '\n') lines++;
        final int previewLines = 14;
        if (lines > previewLines) tv.setMaxLines(previewLines);
        hs.addView(tv);
        card.addView(hs, new LinearLayout.LayoutParams(-1, -2));

        if (lines > previewLines) {
            TextView more = label("… " + (lines - previewLines) + " baris lagi • ketuk ⤢ untuk membuka semua", MUTED, 11, false);
            more.setPadding(dp(12), 0, dp(12), dp(8));
            card.addView(more);
        }
        return card;
    }

    /** Tampilan penuh layar untuk satu blok kode, dengan tombol salin dan tutup. */
    private void showCodeFullscreen(String title, final String code) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(SHEET);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(10), dp(8), dp(10));
        bar.setBackgroundColor(Color.rgb(24, 28, 50));
        bar.addView(label(title, TEXT, 15, true), new LinearLayout.LayoutParams(0, -2, 1));

        final AlertDialog[] holder = new AlertDialog[1];
        TextView copy = label("⧉", TEXT, 20, false);
        copy.setGravity(Gravity.CENTER);
        copy.setPadding(dp(12), dp(8), dp(12), dp(8));
        pressable(copy);
        copy.setOnClickListener(v -> copyText(code, "Kode disalin"));
        bar.addView(copy, new LinearLayout.LayoutParams(-2, -2));

        TextView close = label("✕", TEXT, 20, false);
        close.setGravity(Gravity.CENTER);
        close.setPadding(dp(12), dp(8), dp(12), dp(8));
        pressable(close);
        close.setOnClickListener(v -> {
            if (holder[0] != null) holder[0].dismiss();
        });
        bar.addView(close, new LinearLayout.LayoutParams(-2, -2));
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));

        ScrollView vs = new ScrollView(this);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        TextView tv = new TextView(this);
        tv.setText(code);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(13f);
        tv.setTextColor(Color.rgb(190, 242, 220));
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(16), dp(14), dp(16), dp(24));
        hs.addView(tv);
        vs.addView(hs);
        root.addView(vs, new LinearLayout.LayoutParams(-1, 0, 1));

        AlertDialog dlg = new AlertDialog.Builder(this).setView(root).create();
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
                if (t.indexOf('-') >= 0 && MD_TABLE_SEP.matcher(t).matches()) continue; // baris pemisah
                StringBuilder row = new StringBuilder();
                for (String cell : t.split("\\|")) {
                    String c = cell.trim();
                    if (c.isEmpty()) continue;
                    if (row.length() > 0) row.append("  │  ");
                    row.append(c);
                }
                int s = out.length();
                appendInline(out, row.toString());
                if (!prevTable) out.setSpan(new StyleSpan(Typeface.BOLD), s, out.length(), SPAN);
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
                out.setSpan(new ForegroundColorSpan(Color.rgb(70, 78, 120)), s, out.length() - 1, SPAN);
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
                out.setSpan(new StyleSpan(Typeface.BOLD), s, e, SPAN);
                if (level <= 2) out.setSpan(new ForegroundColorSpan(PURPLE_LIGHT), s, e, SPAN);
                out.append('\n');
                continue;
            }

            // Kutipan >
            if (t.startsWith(">")) {
                int s = out.length();
                appendInline(out, t.replaceFirst("^>+\\s?", ""));
                int e = out.length();
                out.setSpan(new ForegroundColorSpan(Color.rgb(200, 205, 225)), s, e, SPAN);
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
                out.setSpan(new StyleSpan(Typeface.BOLD), s, pe, SPAN);
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
        out.setSpan(new TypefaceSpan("monospace"), start, end, SPAN);
        out.setSpan(new BackgroundColorSpan(Color.rgb(10, 12, 26)), start, end, SPAN);
        out.setSpan(new ForegroundColorSpan(Color.rgb(190, 242, 220)), start, end, SPAN);
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
                    out.setSpan(new TypefaceSpan("monospace"), st, out.length(), SPAN);
                    out.setSpan(new BackgroundColorSpan(Color.rgb(32, 38, 66)), st, out.length(), SPAN);
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
                    out.setSpan(new StyleSpan(Typeface.BOLD), st, out.length(), SPAN);
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
                    out.setSpan(new ForegroundColorSpan(Color.rgb(125, 170, 255)), st, le, SPAN);
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
        saveHistory();
        updateHero();
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

        final JSONArray payload = payloadMessages;
        executor.execute(() -> {
            String answer = null;
            String failure = null;
            try {
                answer = requestChat(model, payload);
            } catch (Exception e) {
                failure = "Gagal menghubungi " + backendName() + ".\n\n" + readableError(e)
                        + (isHermes()
                        ? "\n\nPeriksa URL Worker, kunci (PROXY_KEY), dan apakah InstaBox/Hermes aktif."
                        : "\n\nPeriksa apakah 9Router aktif, URL/port benar, dan API key sesuai.")
                        + (atts.isEmpty() ? "" : "\n\nCatatan: pastikan model yang dipilih mendukung gambar/vision.");
            }
            final String result = answer;
            final String failMsg = failure;
            mainHandler.post(() -> {
                if (messages.getChildCount() > 0) messages.removeViewAt(messages.getChildCount() - 1); // gelembung loading
                if (failMsg != null) {
                    // Batalkan giliran yang gagal supaya tidak ikut terkirim lagi (termasuk gambarnya).
                    int last = conversation.length() - 1;
                    if (last >= 0) {
                        conversation.remove(last);
                        richContent.remove(last);
                    }
                    if (messages.getChildCount() > 0) messages.removeViewAt(messages.getChildCount() - 1); // gelembung pesan pengguna
                    addBubble(failMsg + "\n\nPesanmu dikembalikan ke kolom ketik. Kirim ulang bila sudah siap.", false);
                    input.setText(text);
                    input.setSelection(text.length());
                    pending.addAll(atts);
                    renderAttachBar();
                    setBusy(false);
                    status.setText("Koneksi perlu diperiksa");
                    saveHistory();
                    updateHero();
                    return;
                }
                addBubble(result, false);
                try {
                    JSONObject assistantMessage = new JSONObject();
                    assistantMessage.put("role", "assistant");
                    assistantMessage.put("content", result);
                    conversation.put(assistantMessage);
                } catch (Exception ignored) {}
                setBusy(false);
                status.setText("ENC AI • Terhubung ke " + backendName());
                saveHistory();
            });
        });
    }

    // --------------------------------------------------------- Jaringan

    private String requestChat(String model, JSONArray messagesArray) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("model", model);
        payload.put("messages", messagesArray);
        payload.put("stream", isHermes());

        String base = getServerUrl();
        if (base.isEmpty()) throw new Exception("URL " + backendName() + " belum diisi. Buka pengaturan ⚙ lalu isi Server URL.");
        URL url = new URL(base + "/v1/chat/completions");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(15000);
        // Hermes lewat SSE: Worker mengirim keep-alive tiap 10 dtk, jadi batas ini hanya memutus
        // koneksi yang benar-benar mati (bukan jawaban yang lama). 9Router: 20 menit.
        c.setReadTimeout(isHermes() ? 90000 : 1200000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        String key = apiKey();
        if (!key.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + key);

        byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(body.length);
        try (OutputStream os = c.getOutputStream()) {
            os.write(body);
        }

        int code = c.getResponseCode();

        // Hermes: baca aliran SSE (keep-alive menjaga koneksi tetap hidup selama jawaban diproses).
        if (code >= 200 && code < 300 && isHermes()) {
            StringBuilder acc = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.startsWith("data:")) continue; // lewati komentar ": keep-alive"
                    String d = line.substring(5).trim();
                    if (d.isEmpty()) continue;
                    if (d.equals("[DONE]")) break;
                    try {
                        JSONArray ch = new JSONObject(d).optJSONArray("choices");
                        if (ch != null && ch.length() > 0) {
                            JSONObject delta = ch.getJSONObject(0).optJSONObject("delta");
                            if (delta != null && !delta.isNull("content")) acc.append(delta.optString("content"));
                        }
                    } catch (Exception ignored) {}
                }
            } finally {
                c.disconnect();
            }
            String streamed = acc.toString();
            if (streamed.startsWith("[Error:")) throw new Exception(streamed);
            if (streamed.trim().isEmpty()) return "(Model mengirim jawaban kosong.)";
            return streamed;
        }

        InputStream stream = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        String response = readAll(stream);
        c.disconnect();

        if (code < 200 || code >= 300) {
            throw new Exception("HTTP " + code + ": " + response);
        }

        JSONObject json = new JSONObject(response);
        JSONArray choices = json.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new Exception("Respons API tidak berisi choices: " + response);
        }
        JSONObject message = choices.getJSONObject(0).optJSONObject("message");
        if (message == null) throw new Exception("Respons API tidak berisi message.");
        Object content = message.opt("content");
        if (content instanceof String) return (String) content;
        if (content != null) return content.toString();
        return "(Model mengirim jawaban kosong.)";
    }

    private void fetchModels(boolean showToast) {
        status.setText("Mengambil daftar model…");
        executor.execute(() -> {
            List<String> found = new ArrayList<>();
            String error = null;
            HttpURLConnection c = null;
            try {
                if (getServerUrl().isEmpty()) throw new Exception("URL " + backendName() + " belum diisi.");
                URL url = new URL(getServerUrl() + "/v1/models");
                c = (HttpURLConnection) url.openConnection();
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

    /** Bottom sheet "Pilih Model": chip provider + kartu model 2 kolom + pencarian. */
    private void showModelPicker() {
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(16), dp(10), dp(16), dp(16));
        GradientDrawable sbg = new GradientDrawable();
        sbg.setColor(SHEET);
        sbg.setStroke(dp(1), CARD_STROKE);
        setCorners(sbg, 26, 26, 0, 0);
        sheet.setBackground(sbg);

        View handle = new View(this);
        handle.setBackground(rounded(Color.rgb(60, 66, 96), 3, 0));
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(dp(40), dp(5));
        hl.gravity = Gravity.CENTER_HORIZONTAL;
        sheet.addView(handle, hl);

        TextView title = label("Pilih Model & Provider", TEXT, 18, true);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.topMargin = dp(14);
        sheet.addView(title, tl);
        sheet.addView(label("Aktif: " + shortModel(currentModel), MUTED, 12, false));

        EditText search = styledInput("Cari model…", "");
        LinearLayout.LayoutParams srp = new LinearLayout.LayoutParams(-1, -2);
        srp.topMargin = dp(12);
        sheet.addView(search, srp);

        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipScroll.addView(chipRow);
        LinearLayout.LayoutParams chp = new LinearLayout.LayoutParams(-1, -2);
        chp.topMargin = dp(12);
        sheet.addView(chipScroll, chp);

        ScrollView gridScroll = new ScrollView(this);
        gridScroll.setVerticalScrollBarEnabled(false);
        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        gridScroll.addView(grid);
        int h = (int) (getResources().getDisplayMetrics().heightPixels * 0.42f);
        LinearLayout.LayoutParams grp = new LinearLayout.LayoutParams(-1, h);
        grp.topMargin = dp(10);
        sheet.addView(gridScroll, grp);

        final String[] state = {currentProviderKey, ""};
        final AlertDialog[] holder = new AlertDialog[1];
        final Runnable[] refresh = new Runnable[1];
        refresh[0] = () -> {
            renderChips(chipRow, state, refresh[0]);
            renderGrid(grid, state, holder);
        };
        refresh[0].run();

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable e) {
                state[1] = e.toString().trim().toLowerCase(Locale.ROOT);
                refresh[0].run();
            }
        });

        AlertDialog dlg = new AlertDialog.Builder(this).setView(sheet).create();
        holder[0] = dlg;
        dlg.show();
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setGravity(Gravity.BOTTOM);
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    private void renderChips(LinearLayout chipRow, String[] state, Runnable refresh) {
        chipRow.removeAllViews();
        List<String> keys = new ArrayList<>();
        keys.add("*");
        keys.addAll(providerKeys);
        for (String key : keys) {
            boolean selected = key.equals(state[0]) && state[1].isEmpty();
            TextView chip = label("*".equals(key) ? "Semua" : providerName(key), selected ? Color.WHITE : MUTED, 13, selected);
            chip.setPadding(dp(14), dp(8), dp(14), dp(8));
            if (selected) {
                GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{PURPLE, BLUE});
                g.setCornerRadius(dp(18));
                chip.setBackground(g);
            } else {
                chip.setBackground(rounded(CARD, 18, CARD_STROKE));
            }
            pressable(chip);
            chip.setOnClickListener(v -> {
                state[0] = key;
                refresh.run();
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(8);
            chipRow.addView(chip, lp);
        }
    }

    private void renderGrid(LinearLayout grid, String[] state, AlertDialog[] holder) {
        grid.removeAllViews();
        String q = state[1];
        List<String> shown = new ArrayList<>();
        for (String id : allModelIds) {
            boolean matchProvider = !q.isEmpty() || "*".equals(state[0]) || providerKey(id).equals(state[0]);
            boolean matchQuery = q.isEmpty() || id.toLowerCase(Locale.ROOT).contains(q);
            if (matchProvider && matchQuery) shown.add(id);
        }
        if (!q.isEmpty() && !allModelIds.contains(q)) {
            TextView custom = label("+  Pakai \"" + q + "\" sebagai ID model", PURPLE_LIGHT, 13, true);
            custom.setPadding(dp(14), dp(12), dp(14), dp(12));
            custom.setBackground(rounded(CARD, 14, PURPLE));
            pressable(custom);
            custom.setOnClickListener(v -> {
                if (!allModelIds.contains(q)) allModelIds.add(q);
                currentModel = q;
                currentProviderKey = providerKey(q);
                if (!providerKeys.contains(currentProviderKey)) providerKeys.add(currentProviderKey);
                prefs.edit().putString(lastModelKey(), q).apply();
                updateModelPill();
                if (holder[0] != null) holder[0].dismiss();
            });
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
            cl.setMargins(dp(4), dp(4), dp(4), dp(8));
            grid.addView(custom, cl);
        }
        if (shown.isEmpty()) {
            TextView empty = label("Tidak ada model yang cocok.", MUTED, 13, false);
            empty.setPadding(dp(4), dp(16), dp(4), dp(16));
            grid.addView(empty);
            return;
        }
        int limit = Math.min(shown.size(), 200);
        for (int i = 0; i < limit; i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            grid.addView(row, new LinearLayout.LayoutParams(-1, -2));
            for (int j = 0; j < 2; j++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
                lp.setMargins(dp(4), dp(4), dp(4), dp(4));
                if (i + j < limit) {
                    row.addView(modelCard(shown.get(i + j), holder), lp);
                } else {
                    row.addView(new View(this), lp);
                }
            }
        }
        if (shown.size() > limit) {
            TextView more = label("Menampilkan " + limit + " dari " + shown.size() + " model. Gunakan pencarian untuk mempersempit.", MUTED, 11, false);
            more.setPadding(dp(4), dp(10), dp(4), dp(4));
            grid.addView(more);
        }
    }

    private View modelCard(String id, AlertDialog[] holder) {
        boolean selected = id.equals(currentModel);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(selected ? Color.rgb(30, 24, 64) : CARD);
        bg.setStroke(dp(selected ? 2 : 1), selected ? PURPLE : CARD_STROKE);
        card.setBackground(bg);
        pressable(card);

        TextView name = label(shortModel(id), TEXT, 14, true);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(name);

        TextView sub = label(providerName(providerKey(id)) + (liveIds.contains(id) ? "" : " · bawaan") + (selected ? "  ✓" : ""), selected ? PURPLE_LIGHT : MUTED, 11, false);
        sub.setSingleLine(true);
        sub.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, -2);
        sp.topMargin = dp(4);
        card.addView(sub, sp);

        card.setOnClickListener(v -> {
            currentModel = id;
            currentProviderKey = providerKey(id);
            prefs.edit().putString(lastModelKey(), id).apply();
            updateModelPill();
            if (holder[0] != null) holder[0].dismiss();
        });
        return card;
    }

    // ------------------------------------------------------- Pengaturan

    private EditText styledInput(String hint, String text) {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setHint(hint);
        e.setText(text);
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
        t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
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
                })
                .create();
        dlg.show();
        Window w = dlg.getWindow();
        if (w != null) w.setBackgroundDrawable(rounded(SHEET, 24, CARD_STROKE));
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(PURPLE_LIGHT);
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(PURPLE_LIGHT);
        dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(MUTED);
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
        // Model cadangan hanya berlaku bila backend tidak diganti pada penyimpanan ini.
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
        sendButton.setText(value ? "…" : "↑");
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
        // Simpan riwayat percakapan (teks saja; gambar/isi file tidak disimpan permanen).
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
                    if ("user".equals(role)) addBubble(content, true);
                    else if ("assistant".equals(role)) addBubble(content, false);
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
