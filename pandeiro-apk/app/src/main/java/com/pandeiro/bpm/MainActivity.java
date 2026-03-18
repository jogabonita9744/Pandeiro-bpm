package com.pandeiro.bpm;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.SeekBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.Collections;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST = 1;
    private static final int SAMPLE_RATE = 44100;
    private static final int FFT_SIZE = 1024;
    private static final int HOP_SIZE = FFT_SIZE / 2;
    private static final int PEAK_WIN = 5;
    private static final int MAX_INTERVALS = 2;

    private AudioRecord audioRecord;
    private Thread recordingThread;
    private volatile boolean isRecording = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Detection state
    private float[] prevSpectrum;
    private final float[] fluxBuf = new float[PEAK_WIN];
    private int fluxCount = 0;
    private float noiseFloor = 0.01f;
    private float envelope = 0f;
    private long lastBeatMs = 0;
    private long lastIntervalBeatMs = 0;
    private final ArrayList<Long> intervals = new ArrayList<>();

    // Settings
    private int sensitivity = 3;
    private int division = 1;
    private String lang = "fr";

    // UI
    private TextView bpmText, tempoText, rawBpmText, statusText, sensVal;
    private TextView titleText, labelDivision, labelSens, bpmLabel;
    private Button startStopBtn, resetBtn;
    private Button btnLangFr, btnLangPt, btnLangEn;
    private Button[] divBtns = new Button[6];
    private android.view.View pandeiroPulse;
    private SeekBar sensBar;

    // ── Translations ────────────────────────────────────────────
    private static final String[][] T = {
        // fr, pt, en
        {"PANDEIRO BPM",          "PANDEIRO BPM",         "PANDEIRO BPM"},       // 0 title
        {"DIVISION",              "DIVISAO",              "DIVISION"},            // 1
        {"DEMARRER",              "INICIAR",              "START"},               // 2
        {"ARRETER",               "PARAR",                "STOP"},                // 3
        {"RESET",                 "RESETAR",              "RESET"},               // 4
        {"SENSIBILITE",           "SENSIBILIDADE",        "SENSITIVITY"},         // 5
        {"En attente",            "Aguardando",           "Waiting"},             // 6
        {"Ecoute...",             "Ouvindo...",           "Listening..."},        // 7
        {"Coup!",                 "Toque!",               "Beat!"},               // 8
        {"Permission refusee",    "Permissao negada",     "Permission denied"},   // 9
        {"Erreur micro",          "Erro micro",           "Mic error"},           // 10
        {"brut",                  "bruto",                "raw"},                 // 11 (raw bpm label)
    };

    private static final String[][] TEMPO = {
        {"Largo","Larghetto","Adagio","Andante","Moderato","Allegro","Vivace","Presto","Prestissimo"},
        {"Largo","Larghetto","Adagio","Andante","Moderato","Allegro","Vivace","Presto","Prestissimo"},
        {"Largo","Larghetto","Adagio","Andante","Moderato","Allegro","Vivace","Presto","Prestissimo"},
    };

    private int langIdx() {
        if ("pt".equals(lang)) return 1;
        if ("en".equals(lang)) return 2;
        return 0;
    }

    private String t(int key) { return T[key][langIdx()]; }

    private String tempo(int bpm) {
        String[] names = TEMPO[langIdx()];
        if (bpm < 60)  return names[0];
        if (bpm < 66)  return names[1];
        if (bpm < 76)  return names[2];
        if (bpm < 108) return names[3];
        if (bpm < 120) return names[4];
        if (bpm < 156) return names[5];
        if (bpm < 176) return names[6];
        if (bpm < 200) return names[7];
        return names[8];
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        bpmText       = findViewById(R.id.bpmText);
        bpmLabel      = findViewById(R.id.bpmLabel);
        tempoText     = findViewById(R.id.tempoText);
        rawBpmText    = findViewById(R.id.rawBpmText);
        statusText    = findViewById(R.id.statusText);
        titleText     = findViewById(R.id.titleText);
        labelDivision = findViewById(R.id.labelDivision);
        labelSens     = findViewById(R.id.labelSens);
        startStopBtn  = findViewById(R.id.startStopBtn);
        resetBtn      = findViewById(R.id.resetBtn);
        pandeiroPulse = findViewById(R.id.pandeiroPulse);
        sensBar       = findViewById(R.id.sensBar);
        sensVal       = findViewById(R.id.sensVal);
        btnLangFr     = findViewById(R.id.btnLangFr);
        btnLangPt     = findViewById(R.id.btnLangPt);
        btnLangEn     = findViewById(R.id.btnLangEn);
        divBtns[0]    = findViewById(R.id.div1);
        divBtns[1]    = findViewById(R.id.div2);
        divBtns[2]    = findViewById(R.id.div3);
        divBtns[3]    = findViewById(R.id.div4);
        divBtns[4]    = findViewById(R.id.div5);
        divBtns[5]    = findViewById(R.id.div6);

        sensBar.setMax(4);
        sensBar.setProgress(2);
        sensBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                sensitivity = p + 1;
                sensVal.setText(String.valueOf(sensitivity));
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });

        startStopBtn.setOnClickListener(v -> {
            if (isRecording) stopRecording(); else checkPermissionAndStart();
        });
        resetBtn.setOnClickListener(v -> resetBPM());

        // Language buttons
        btnLangFr.setOnClickListener(v -> setLang("fr"));
        btnLangPt.setOnClickListener(v -> setLang("pt"));
        btnLangEn.setOnClickListener(v -> setLang("en"));

        // Division buttons
        for (int i = 0; i < 6; i++) {
            final int div = i + 1;
            divBtns[i].setOnClickListener(v -> setDivision(div));
        }

        updateUI();
    }

    private void setLang(String l) {
        lang = l;
        btnLangFr.setBackgroundColor("fr".equals(l) ? 0xFF7aad6a : 0xFF1a3a12);
        btnLangPt.setBackgroundColor("pt".equals(l) ? 0xFF7aad6a : 0xFF1a3a12);
        btnLangEn.setBackgroundColor("en".equals(l) ? 0xFF7aad6a : 0xFF1a3a12);
        btnLangFr.setTextColor("fr".equals(l) ? 0xFF1a2e1a : 0xFF7aad6a);
        btnLangPt.setTextColor("pt".equals(l) ? 0xFF1a2e1a : 0xFF7aad6a);
        btnLangEn.setTextColor("en".equals(l) ? 0xFF1a2e1a : 0xFF7aad6a);
        updateUI();
    }

    private void setDivision(int d) {
        division = d;
        for (int i = 0; i < 6; i++) {
            boolean sel = (i + 1) == d;
            divBtns[i].setBackgroundColor(sel ? 0xFF7aad6a : 0xFF1a3a12);
            divBtns[i].setTextColor(sel ? 0xFF1a2e1a : 0xFF7aad6a);
        }
        // Refresh displayed BPM if we have a raw value
        refreshBpmDisplay();
    }

    private void updateUI() {
        titleText.setText(t(0));
        labelDivision.setText(t(1));
        labelSens.setText(t(5));
        statusText.setText(isRecording ? t(7) : t(6));
        startStopBtn.setText(isRecording ? t(3) : t(2));
        resetBtn.setText(t(4));
    }

    // Raw detected BPM (before division)
    private int lastRawBpm = 0;

    private void refreshBpmDisplay() {
        if (lastRawBpm <= 0) return;
        int displayed = Math.max(1, Math.round((float) lastRawBpm / division));
        mainHandler.post(() -> {
            bpmText.setText(String.valueOf(displayed));
            tempoText.setText(tempo(displayed));
            if (division > 1) {
                rawBpmText.setText(lastRawBpm + " " + t(11) + " / " + division);
            } else {
                rawBpmText.setText("");
            }
        });
    }

    // ── Permissions ─────────────────────────────────────────────
    private void checkPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            startRecording();
        } else {
            ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_REQUEST);
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == PERMISSION_REQUEST && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
            startRecording();
        } else {
            setStatus(t(9), false);
        }
    }

    // ── Recording ───────────────────────────────────────────────
    private void startRecording() {
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuf * 2, FFT_SIZE * 4));

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            setStatus(t(10), false); return;
        }

        isRecording = true;
        audioRecord.startRecording();
        startStopBtn.setText(t(3));
        startStopBtn.setBackgroundColor(0xFF3a6b2a);
        setStatus(t(7), true);

        prevSpectrum = null;
        fluxCount = 0;
        for (int i = 0; i < PEAK_WIN; i++) fluxBuf[i] = 0f;
        noiseFloor = 0.01f;
        envelope = 0f;
        lastBeatMs = 0;
        lastIntervalBeatMs = 0;
        intervals.clear();

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

                float[] re = new float[FFT_SIZE];
                float[] im = new float[FFT_SIZE];
                for (int i = 0; i < FFT_SIZE; i++) re[i] = frame[i] * hann[i];
                fft(re, im);

                float[] mag = new float[FFT_SIZE / 2];
                for (int i = 0; i < FFT_SIZE / 2; i++)
                    mag[i] = (float) Math.sqrt(re[i]*re[i] + im[i]*im[i]);

                // Weighted flux: platinelas zone gets highest weight
                float flux = 0;
                if (prevSpectrum != null) {
                    for (int i = bA; i <= bEnd; i++) {
                        float d = mag[i] - prevSpectrum[i];
                        if (d > 0) {
                            float w = (i < bB) ? 1.5f : (i < bC) ? 3.5f : 1.0f;
                            flux += d * w;
                        }
                    }
                }
                prevSpectrum = mag.clone();

                // Envelope tracking
                envelope = (flux > envelope)
                    ? envelope * 0.3f + flux * 0.7f
                    : envelope * 0.994f + flux * 0.006f;

                // Noise floor — only update during quiet frames
                if (flux < envelope * 0.4f)
                    noiseFloor = noiseFloor * 0.99f + flux * 0.01f;
                noiseFloor = Math.max(noiseFloor, 0.01f);

                // Store flux in circular peak window
                fluxBuf[fluxCount % PEAK_WIN] = flux;
                fluxCount++;
                if (fluxCount < PEAK_WIN) continue;

                // Check local maximum at center of window
                int ci = ((fluxCount - 1) - PEAK_WIN / 2) % PEAK_WIN;
                if (ci < 0) ci += PEAK_WIN;
                float center = fluxBuf[ci];
                boolean isMax = true;
                for (int i = 0; i < PEAK_WIN; i++) {
                    if (i != ci && fluxBuf[i] >= center) { isMax = false; break; }
                }
                if (!isMax) continue;

                // Dynamic threshold
                float ratio = 0.60f - sensitivity * 0.09f; // 1→0.51, 5→0.15
                float thresh = noiseFloor + ratio * Math.max(0, envelope - noiseFloor);
                if (center < thresh) continue;
                if (center < noiseFloor * 3f) continue;

                long now = System.currentTimeMillis();
                if ((now - lastBeatMs) < 140) continue;
                lastBeatMs = now;

                // Record interval
                if (lastIntervalBeatMs > 0) {
                    long iv = now - lastIntervalBeatMs;
                    if (iv >= 140 && iv <= 2000) {
                        intervals.add(iv);
                        if (intervals.size() > MAX_INTERVALS) intervals.remove(0);
                    }
                }
                lastIntervalBeatMs = now;

                // Real-time BPM: median of last MAX_INTERVALS intervals
                Integer rawBpm = null;
                if (intervals.size() >= 2) {
                    ArrayList<Long> sorted = new ArrayList<>(intervals);
                    Collections.sort(sorted);
                    long med = sorted.get(sorted.size() / 2);
                    int raw = (int) Math.round(60000.0 / med);
                    if (raw >= 30 && raw <= 400) rawBpm = raw;
                }

                if (rawBpm != null) lastRawBpm = rawBpm;
                final int displayBpm = rawBpm != null
                    ? Math.max(1, Math.round((float) rawBpm / division))
                    : -1;
                final int rawForLabel = rawBpm != null ? rawBpm : -1;

                mainHandler.post(() -> {
                    pandeiroPulse.animate().scaleX(1.1f).scaleY(1.1f).setDuration(50)
                        .withEndAction(() -> pandeiroPulse.animate()
                            .scaleX(1f).scaleY(1f).setDuration(110).start()).start();
                    if (displayBpm > 0) {
                        bpmText.setText(String.valueOf(displayBpm));
                        tempoText.setText(tempo(displayBpm));
                        if (division > 1 && rawForLabel > 0) {
                            rawBpmText.setText(rawForLabel + " " + t(11) + " / " + division);
                        } else {
                            rawBpmText.setText("");
                        }
                    }
                    setStatus(t(8), true);
                });
            }
        });
        recordingThread.start();
    }

    private void stopRecording() {
        isRecording = false;
        if (audioRecord != null) { audioRecord.stop(); audioRecord.release(); audioRecord = null; }
        startStopBtn.setText(t(2));
        startStopBtn.setBackgroundColor(0xFF7aad6a);
        setStatus(t(6), false);
    }

    private void resetBPM() {
        intervals.clear();
        lastIntervalBeatMs = 0;
        lastRawBpm = 0;
        noiseFloor = 0.01f; envelope = 0f; prevSpectrum = null;
        bpmText.setText("--");
        tempoText.setText("");
        rawBpmText.setText("");
        setStatus(isRecording ? t(7) : t(6), isRecording);
    }

    private void setStatus(final String msg, final boolean active) {
        mainHandler.post(() -> {
            statusText.setText(msg);
            statusText.setTextColor(active ? 0xFFa8d878 : 0xFF4a7a3a);
        });
    }

    // ── FFT ─────────────────────────────────────────────────────
    private float[] makeHann(int n) {
        float[] w = new float[n];
        for (int i = 0; i < n; i++)
            w[i] = (float)(0.5 * (1 - Math.cos(2 * Math.PI * i / (n - 1))));
        return w;
    }

    private void fft(float[] re, float[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                float t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            float wRe = (float)Math.cos(ang), wIm = (float)Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                float cRe = 1, cIm = 0;
                for (int j = 0; j < len/2; j++) {
                    float uR=re[i+j], uI=im[i+j];
                    float vR=re[i+j+len/2]*cRe - im[i+j+len/2]*cIm;
                    float vI=re[i+j+len/2]*cIm + im[i+j+len/2]*cRe;
                    re[i+j]=uR+vR; im[i+j]=uI+vI;
                    re[i+j+len/2]=uR-vR; im[i+j+len/2]=uI-vI;
                    float nR=cRe*wRe-cIm*wIm; cIm=cRe*wIm+cIm*wRe; cRe=nR;
                }
            }
        }
    }

    @Override
    protected void onDestroy() { super.onDestroy(); stopRecording(); }
}
