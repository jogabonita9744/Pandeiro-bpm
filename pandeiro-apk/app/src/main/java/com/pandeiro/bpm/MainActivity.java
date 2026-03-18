package com.pandeiro.bpm;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import java.util.*;

public class MainActivity extends AppCompatActivity {

    // ── Constants ─────────────────────────────────────────────
    private static final int PERM_REQUEST = 1;
    private static final int SAMPLE_RATE  = 44100;
    private static final int FFT_SIZE     = 1024;
    private static final int HOP_SIZE     = FFT_SIZE / 2;
    private static final int PEAK_WIN     = 5;
    private static final int MAX_INTERVALS = 2;

    // ── Audio state ───────────────────────────────────────────
    private AudioRecord audioRecord;
    private Thread recordingThread;
    private volatile boolean isRecording = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private float[] prevSpectrum;
    private final float[] fluxBuf = new float[PEAK_WIN];
    private int fluxCount = 0;
    private float noiseFloor = 0.01f, envelope = 0f;
    private long lastBeatMs = 0, lastIntervalBeatMs = 0;
    private final ArrayList<Long> intervals = new ArrayList<>();

    // ── Settings ──────────────────────────────────────────────
    private int sensitivity = 3;
    private int division    = 1;
    private String lang     = "pt";
    private String palKey   = "vert";
    private String fontKey  = "mono";
    private int bgIndex     = 0;

    // ── Palette data ──────────────────────────────────────────
    private static final String[] PAL_KEYS = {"vert","bleu","blanc","noir","jaune","violet","rouge","rose"};
    private static final int[]  PAL_DARK  = {0xFF122e0a,0xFF0a1a2e,0xFF888888,0xFF111111,0xFF2e2200,0xFF1a0a2e,0xFF2e0a0a,0xFF2e0a1a};
    private static final int[]  PAL_MID   = {0xFF2d6b18,0xFF1a4a8a,0xFFcccccc,0xFF2a2a2a,0xFF7a5800,0xFF4a1a8a,0xFF8a1a1a,0xFF8a1a50};
    private static final int[]  PAL_MAIN  = {0xFF4a9a2a,0xFF2a6acd,0xFFeeeeee,0xFF555555,0xFFc8940a,0xFF7a3acd,0xFFcd2a2a,0xFFcd2a80};
    private static final int[]  PAL_LIGHT = {0xFF8fcc60,0xFF6aacf0,0xFFffffff,0xFF999999,0xFFf0c830,0xFFb07af0,0xFFf07070,0xFFf078b8};
    private static final int[]  PAL_BPM   = {0xFF0d2208,0xFF021030,0xFF222222,0xFF111111,0xFF1a1000,0xFF0d0220,0xFF1a0202,0xFF1a0210};
    private static final int[]  PAL_OVL   = {0x9E071204,0x9E04060a,0x8C141414,0x7F000000,0x9E0d0800,0x9E04020a,0x9E080202,0x9E080106};

    // ── Bg resources ─────────────────────────────────────────
    private static final int[] BG_RES = {
        R.drawable.bg_1, R.drawable.bg_2, R.drawable.bg_3,
        R.drawable.bg_4, R.drawable.bg_5, R.drawable.bg_6
    };

    // ── Font stacks (by key) ──────────────────────────────────
    // Android uses system fonts; we approximate with Serif/SansSerif/Monospace
    private static final String[] FONT_KEYS   = {"mono","rounded","serif","clean","slab","display"};
    private static final String[] FONT_FAMILIES= {"monospace","sans-serif-light","serif","sans-serif","serif-monospace","sans-serif-condensed"};

    // ── Translations ──────────────────────────────────────────
    private static final String[][] T = {
        // pt, fr, en
        {"PANDEIRO BPM",    "PANDEIRO BPM",    "PANDEIRO BPM"},    // 0
        {"DIVISAO",         "DIVISION",        "DIVISION"},         // 1
        {"SENSIBILIDADE",   "SENSIBILITE",     "SENSITIVITY"},      // 2
        {"INICIAR",         "DEMARRER",        "START"},            // 3
        {"ARRETER",         "ARRETER",         "STOP"},             // 4
        {"RESETAR",         "RESET",           "RESET"},            // 5
        {"Aguardando",      "En attente",      "Waiting"},          // 6
        {"Ouvindo...",      "Ecoute...",       "Listening..."},     // 7
        {"Toque!",          "Coup!",           "Beat!"},            // 8
        {"Mudar fundo",     "Changer le fond", "Change background"},// 9
        {"Mudar cor",       "Changer couleur", "Change color"},     // 10
        {"Mudar tipografia","Changer typo",    "Change font"},      // 11
        {"bruto",           "brut",            "raw"},              // 12
        {"Permissao negada","Permission refus","Permission denied"}, // 13
        {"Erro micro",      "Erreur micro",    "Mic error"},        // 14
    };

    private int li() {
        if ("fr".equals(lang)) return 1;
        if ("en".equals(lang)) return 2;
        return 0;
    }
    private String t(int k) { return T[k][li()]; }

    // ── UI refs ───────────────────────────────────────────────
    private TextView tvTitle, tvBpmNum, tvBpmLbl, tvTempo, tvRaw;
    private TextView tvDivLabel, tvSensLabel, tvStatus, tvSensVal;
    private Button btnPt, btnFr, btnEn;
    private Button btnStart, btnReset;
    private Button[] divBtns = new Button[6];
    private View pandOuter, overlay, rootBg;
    private SeekBar sensBar;

    // Settings bottom sheet (simulated as a dialog)
    private PopupWindow settingsPopup;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        // Load prefs
        SharedPreferences prefs = getPreferences(Context.MODE_PRIVATE);
        lang     = prefs.getString("lang",    "pt");
        palKey   = prefs.getString("pal",     "vert");
        fontKey  = prefs.getString("font",    "mono");
        bgIndex  = prefs.getInt   ("bg",      0);
        division = prefs.getInt   ("div",     1);
        sensitivity = prefs.getInt("sens",    3);

        bindViews();
        setupListeners();
        applyAll();
    }

    private void bindViews() {
        tvTitle     = findViewById(R.id.tvTitle);
        tvBpmNum    = findViewById(R.id.tvBpmNum);
        tvBpmLbl    = findViewById(R.id.tvBpmLbl);
        tvTempo     = findViewById(R.id.tvTempo);
        tvRaw       = findViewById(R.id.tvRaw);
        tvDivLabel  = findViewById(R.id.tvDivLabel);
        tvSensLabel = findViewById(R.id.tvSensLabel);
        tvStatus    = findViewById(R.id.tvStatus);
        tvSensVal   = findViewById(R.id.tvSensVal);
        btnPt       = findViewById(R.id.btnPt);
        btnFr       = findViewById(R.id.btnFr);
        btnEn       = findViewById(R.id.btnEn);
        btnStart    = findViewById(R.id.startStopBtn);
        btnReset    = findViewById(R.id.resetBtn);
        pandOuter   = findViewById(R.id.pandeiroPulse);
        overlay     = findViewById(R.id.overlay);
        rootBg      = findViewById(R.id.rootBg);
        sensBar     = findViewById(R.id.sensBar);

        for (int i = 0; i < 6; i++)
            divBtns[i] = findViewById(getResources().getIdentifier("div"+(i+1), "id", getPackageName()));
    }

    private void setupListeners() {
        btnPt.setOnClickListener(v -> { lang = "pt"; applyAll(); savePrefs(); });
        btnFr.setOnClickListener(v -> { lang = "fr"; applyAll(); savePrefs(); });
        btnEn.setOnClickListener(v -> { lang = "en"; applyAll(); savePrefs(); });

        for (int i = 0; i < 6; i++) {
            final int div = i + 1;
            divBtns[i].setOnClickListener(v -> { division = div; applyDivision(); savePrefs(); });
        }

        sensBar.setMax(4);
        sensBar.setProgress(sensitivity - 1);
        sensBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                sensitivity = p + 1;
                tvSensVal.setText(String.valueOf(sensitivity));
                savePrefs();
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });

        btnStart.setOnClickListener(v -> { if (isRecording) stopRec(); else checkPermAndStart(); });
        btnReset.setOnClickListener(v -> resetBpm());

        // 3-dot menu
        findViewById(R.id.menuBtn).setOnClickListener(v -> showSettingsMenu(v));
    }

    // ── Apply everything ──────────────────────────────────────
    private void applyAll() {
        applyLang();
        applyPalette();
        applyFont();
        applyBg();
        applyDivision();
    }

    private void applyLang() {
        tvTitle.setText(t(0));
        tvDivLabel.setText(t(1));
        tvSensLabel.setText(t(2));
        btnStart.setText(isRecording ? t(4) : t(3));
        btnReset.setText(t(5));
        tvStatus.setText(isRecording ? t(7) : t(6));
        tvRaw.setText("240 " + t(12) + " / " + division);

        int palIdx = palIdx();
        int main = PAL_MAIN[palIdx];
        int bpm  = PAL_BPM[palIdx];
        boolean isLight = "blanc".equals(palKey);
        int textOnMain = isLight ? Color.parseColor("#222222") : Color.WHITE;

        btnPt.setBackgroundColor("pt".equals(lang) ? main : Color.TRANSPARENT);
        btnFr.setBackgroundColor("fr".equals(lang) ? main : Color.TRANSPARENT);
        btnEn.setBackgroundColor("en".equals(lang) ? main : Color.TRANSPARENT);
        btnPt.setTextColor("pt".equals(lang) ? textOnMain : Color.parseColor("#555555"));
        btnFr.setTextColor("fr".equals(lang) ? textOnMain : Color.parseColor("#555555"));
        btnEn.setTextColor("en".equals(lang) ? textOnMain : Color.parseColor("#555555"));
    }

    private int palIdx() {
        for (int i = 0; i < PAL_KEYS.length; i++)
            if (PAL_KEYS[i].equals(palKey)) return i;
        return 0;
    }

    private void applyPalette() {
        int idx   = palIdx();
        int dark  = PAL_DARK[idx];
        int mid   = PAL_MID[idx];
        int main  = PAL_MAIN[idx];
        int light = PAL_LIGHT[idx];
        int bpm   = PAL_BPM[idx];
        int ovl   = PAL_OVL[idx];
        boolean isLight = "blanc".equals(palKey);
        int textOnMain = isLight ? Color.parseColor("#222222") : Color.WHITE;

        overlay.setBackgroundColor(ovl);
        tvTitle.setTextColor(main);
        tvBpmNum.setTextColor(bpm);
        tvBpmLbl.setTextColor(mid);
        tvTempo.setTextColor(light);
        tvRaw.setTextColor(main);
        tvDivLabel.setTextColor(main);
        tvSensLabel.setTextColor(main);
        tvSensVal.setTextColor(light);
        tvStatus.setTextColor(main);

        pandOuter.setBackgroundColor(dark);

        btnStart.setBackgroundColor(main);
        btnStart.setTextColor(textOnMain);
        btnReset.setTextColor(light);

        sensBar.setProgressTintList(android.content.res.ColorStateList.valueOf(main));
        sensBar.setThumbTintList(android.content.res.ColorStateList.valueOf(light));

        applyDivision();
        applyLang();
    }

    private void applyDivision() {
        int idx  = palIdx();
        int main = PAL_MAIN[idx];
        int light= PAL_LIGHT[idx];
        boolean isLight = "blanc".equals(palKey);
        int textOnMain = isLight ? Color.parseColor("#222222") : Color.WHITE;

        for (int i = 0; i < 6; i++) {
            boolean sel = (i + 1) == division;
            divBtns[i].setBackgroundColor(sel ? main : Color.TRANSPARENT);
            divBtns[i].setTextColor(sel ? textOnMain : light);
        }
        tvRaw.setText("240 " + t(12) + " / " + division);
    }

    private void applyFont() {
        int idx = 0;
        for (int i = 0; i < FONT_KEYS.length; i++)
            if (FONT_KEYS[i].equals(fontKey)) { idx = i; break; }
        String family = FONT_FAMILIES[idx];
        Typeface tf;
        switch (family) {
            case "serif":            tf = Typeface.SERIF; break;
            case "monospace":        tf = Typeface.MONOSPACE; break;
            default:                 tf = Typeface.SANS_SERIF; break;
        }
        tvBpmNum.setTypeface(tf, Typeface.BOLD);
        tvTitle.setTypeface(tf, Typeface.BOLD);
        tvDivLabel.setTypeface(tf, Typeface.BOLD);
        tvSensLabel.setTypeface(tf, Typeface.BOLD);
        btnStart.setTypeface(tf, Typeface.BOLD);
        btnReset.setTypeface(tf);
        tvStatus.setTypeface(tf);
    }

    private void applyBg() {
        if (bgIndex >= 0 && bgIndex < BG_RES.length)
            rootBg.setBackgroundResource(BG_RES[bgIndex]);
    }

    // ── Settings menu ─────────────────────────────────────────
    private void showSettingsMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, 1, 0, t(9));
        popup.getMenu().add(0, 2, 1, t(10));
        popup.getMenu().add(0, 3, 2, t(11));
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: showBgPicker(); break;
                case 2: showColorPicker(); break;
                case 3: showFontPicker(); break;
            }
            return true;
        });
        popup.show();
    }

    private void showBgPicker() {
        String[] labels = {"Fond 1","Fond 2","Fond 3","Fond 4","Fond 5","Fond 6"};
        new android.app.AlertDialog.Builder(this)
            .setTitle(t(9))
            .setSingleChoiceItems(labels, bgIndex, (d, w) -> { bgIndex = w; applyBg(); savePrefs(); })
            .setPositiveButton("OK", null)
            .show();
    }

    private void showColorPicker() {
        String[] labels;
        if ("fr".equals(lang))
            labels = new String[]{"Vert","Bleu","Blanc","Noir","Jaune","Violet","Rouge","Rose"};
        else if ("en".equals(lang))
            labels = new String[]{"Green","Blue","White","Black","Yellow","Purple","Red","Pink"};
        else
            labels = new String[]{"Verde","Azul","Branco","Preto","Amarelo","Violeta","Vermelho","Rosa"};

        int cur = 0;
        for (int i = 0; i < PAL_KEYS.length; i++)
            if (PAL_KEYS[i].equals(palKey)) { cur = i; break; }

        new android.app.AlertDialog.Builder(this)
            .setTitle(t(10))
            .setSingleChoiceItems(labels, cur, (d, w) -> { palKey = PAL_KEYS[w]; applyPalette(); savePrefs(); })
            .setPositiveButton("OK", null)
            .show();
    }

    private void showFontPicker() {
        String[] labels = {"Mono","Rounded","Serif","Clean","Slab","Display"};
        int cur = 0;
        for (int i = 0; i < FONT_KEYS.length; i++)
            if (FONT_KEYS[i].equals(fontKey)) { cur = i; break; }

        new android.app.AlertDialog.Builder(this)
            .setTitle(t(11))
            .setSingleChoiceItems(labels, cur, (d, w) -> { fontKey = FONT_KEYS[w]; applyFont(); savePrefs(); })
            .setPositiveButton("OK", null)
            .show();
    }

    // ── Prefs ─────────────────────────────────────────────────
    private void savePrefs() {
        getPreferences(Context.MODE_PRIVATE).edit()
            .putString("lang", lang).putString("pal", palKey)
            .putString("font", fontKey).putInt("bg", bgIndex)
            .putInt("div", division).putInt("sens", sensitivity)
            .apply();
    }

    // ── Audio ─────────────────────────────────────────────────
    private void checkPermAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) startRec();
        else ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.RECORD_AUDIO}, PERM_REQUEST);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req == PERM_REQUEST && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED)
            startRec();
        else setStatus(t(13), false);
    }

    private void startRec() {
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            Math.max(minBuf * 2, FFT_SIZE * 4));
        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            setStatus(t(14), false); return;
        }
        isRecording = true;
        audioRecord.startRecording();
        btnStart.setText(t(4));
        setStatus(t(7), true);
        prevSpectrum = null; fluxCount = 0;
        for (int i = 0; i < PEAK_WIN; i++) fluxBuf[i] = 0;
        noiseFloor = 0.01f; envelope = 0f;
        lastBeatMs = 0; lastIntervalBeatMs = 0; intervals.clear();

        final short[] buf = new short[HOP_SIZE];
        final float[] hann = makeHann(FFT_SIZE);
        final float[] overlap = new float[HOP_SIZE];
        final float binRes = SAMPLE_RATE / (float) FFT_SIZE;
        final int bA   = Math.max(1, (int)(300f  / binRes));
        final int bB   = (int)(1200f / binRes);
        final int bC   = (int)(5000f / binRes);
        final int bEnd = Math.min((int)(11000f / binRes), FFT_SIZE/2 - 1);

        recordingThread = new Thread(() -> {
            while (isRecording) {
                int read = audioRecord.read(buf, 0, HOP_SIZE);
                if (read < HOP_SIZE) continue;
                float[] frame = new float[FFT_SIZE];
                System.arraycopy(overlap, 0, frame, 0, HOP_SIZE);
                for (int i = 0; i < HOP_SIZE; i++) frame[HOP_SIZE + i] = buf[i] / 32768f;
                for (int i = 0; i < HOP_SIZE; i++) overlap[i] = buf[i] / 32768f;
                float[] re = new float[FFT_SIZE], im = new float[FFT_SIZE];
                for (int i = 0; i < FFT_SIZE; i++) re[i] = frame[i] * hann[i];
                fft(re, im);
                float[] mag = new float[FFT_SIZE/2];
                for (int i = 0; i < FFT_SIZE/2; i++)
                    mag[i] = (float)Math.sqrt(re[i]*re[i] + im[i]*im[i]);
                float flux = 0;
                if (prevSpectrum != null)
                    for (int i = bA; i <= bEnd; i++) {
                        float d = mag[i] - prevSpectrum[i];
                        if (d > 0) flux += d * (i < bB ? 1.5f : i < bC ? 3.5f : 1.0f);
                    }
                prevSpectrum = mag.clone();
                envelope = flux > envelope ? envelope*0.3f+flux*0.7f : envelope*0.994f+flux*0.006f;
                if (flux < envelope * 0.4f) noiseFloor = noiseFloor*0.99f + flux*0.01f;
                noiseFloor = Math.max(noiseFloor, 0.01f);
                fluxBuf[fluxCount % PEAK_WIN] = flux; fluxCount++;
                if (fluxCount < PEAK_WIN) continue;
                int ci = ((fluxCount-1) - PEAK_WIN/2) % PEAK_WIN;
                if (ci < 0) ci += PEAK_WIN;
                float center = fluxBuf[ci];
                boolean isMax = true;
                for (int i = 0; i < PEAK_WIN; i++) if (i!=ci && fluxBuf[i]>=center) { isMax=false; break; }
                if (!isMax) continue;
                float ratio = 0.60f - sensitivity * 0.09f;
                float thresh = noiseFloor + ratio * Math.max(0, envelope - noiseFloor);
                if (center < thresh || center < noiseFloor * 3f) continue;
                long now = System.currentTimeMillis();
                if (now - lastBeatMs < 140) continue;
                lastBeatMs = now;
                if (lastIntervalBeatMs > 0) {
                    long iv = now - lastIntervalBeatMs;
                    if (iv >= 140 && iv <= 2000) {
                        intervals.add(iv);
                        if (intervals.size() > MAX_INTERVALS) intervals.remove(0);
                    }
                }
                lastIntervalBeatMs = now;
                Integer rawBpm = null;
                if (intervals.size() >= 2) {
                    ArrayList<Long> sorted = new ArrayList<>(intervals);
                    Collections.sort(sorted);
                    long med = sorted.get(sorted.size()/2);
                    int raw = (int)Math.round(60000.0/med);
                    if (raw >= 30 && raw <= 400) rawBpm = raw;
                }
                final Integer rb = rawBpm;
                mainHandler.post(() -> {
                    pandOuter.animate().scaleX(1.1f).scaleY(1.1f).setDuration(50)
                        .withEndAction(()->pandOuter.animate().scaleX(1f).scaleY(1f).setDuration(110).start()).start();
                    if (rb != null) {
                        int displayed = Math.max(1, Math.round((float)rb / division));
                        tvBpmNum.setText(String.valueOf(displayed));
                        tvTempo.setText(tempoName(displayed));
                        tvRaw.setText(division > 1 ? rb + " " + t(12) + " / " + division : "");
                    }
                    setStatus(t(8), true);
                });
            }
        });
        recordingThread.start();
    }

    private void stopRec() {
        isRecording = false;
        if (audioRecord != null) { audioRecord.stop(); audioRecord.release(); audioRecord = null; }
        btnStart.setText(t(3));
        setStatus(t(6), false);
    }

    private void resetBpm() {
        intervals.clear(); lastIntervalBeatMs = 0;
        noiseFloor = 0.01f; envelope = 0f; prevSpectrum = null;
        tvBpmNum.setText("--"); tvTempo.setText("");
        tvRaw.setText("240 " + t(12) + " / " + division);
        setStatus(isRecording ? t(7) : t(6), isRecording);
    }

    private void setStatus(String msg, boolean active) {
        mainHandler.post(() -> {
            tvStatus.setText(msg);
            tvStatus.setTextColor(active ? PAL_LIGHT[palIdx()] : PAL_MAIN[palIdx()]);
        });
    }

    private String tempoName(int b) {
        if (b<60) return "Largo"; if (b<66) return "Larghetto"; if (b<76) return "Adagio";
        if (b<108) return "Andante"; if (b<120) return "Moderato"; if (b<156) return "Allegro";
        if (b<176) return "Vivace"; if (b<200) return "Presto"; return "Prestissimo";
    }

    private float[] makeHann(int n) {
        float[] w = new float[n];
        for (int i=0;i<n;i++) w[i]=(float)(0.5*(1-Math.cos(2*Math.PI*i/(n-1))));
        return w;
    }
    private void fft(float[] re, float[] im) {
        int n=re.length;
        for(int i=1,j=0;i<n;i++){int bit=n>>1;for(;(j&bit)!=0;bit>>=1)j^=bit;j^=bit;if(i<j){float t=re[i];re[i]=re[j];re[j]=t;t=im[i];im[i]=im[j];im[j]=t;}}
        for(int len=2;len<=n;len<<=1){double ang=-2*Math.PI/len;float wR=(float)Math.cos(ang),wI=(float)Math.sin(ang);for(int i=0;i<n;i+=len){float cR=1,cI=0;for(int j=0;j<len/2;j++){float uR=re[i+j],uI=im[i+j],vR=re[i+j+len/2]*cR-im[i+j+len/2]*cI,vI=re[i+j+len/2]*cI+im[i+j+len/2]*cR;re[i+j]=uR+vR;im[i+j]=uI+vI;re[i+j+len/2]=uR-vR;im[i+j+len/2]=uI-vI;float nR=cR*wR-cI*wI;cI=cR*wI+cI*wR;cR=nR;}}}
    }

    @Override protected void onDestroy() { super.onDestroy(); stopRec(); }
}
