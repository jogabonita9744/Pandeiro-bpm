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
    private static final int MAX_INTERVALS = 4;

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

    private int sensitivity = 3;

    // UI
    private TextView bpmText, tempoText, statusText, sensVal;
    private Button startStopBtn;
    private android.view.View pandeiroPulse;
    private SeekBar sensBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        bpmText       = findViewById(R.id.bpmText);
        tempoText     = findViewById(R.id.tempoText);
        statusText    = findViewById(R.id.statusText);
        startStopBtn  = findViewById(R.id.startStopBtn);
        pandeiroPulse = findViewById(R.id.pandeiroPulse);
        sensBar       = findViewById(R.id.sensBar);
        sensVal       = findViewById(R.id.sensVal);

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
        findViewById(R.id.resetBtn).setOnClickListener(v -> resetBPM());
    }

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
            setStatus("Permission refusee", false);
        }
    }

    private void startRecording() {
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, Math.max(minBuf * 2, FFT_SIZE * 4));

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            setStatus("Erreur micro", false); return;
        }

        isRecording = true;
        audioRecord.startRecording();
        startStopBtn.setText("ARRETER");
        startStopBtn.setBackgroundColor(0xFFD63B2F);
        setStatus("Ecoute...", true);

        // Reset all state
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

        // Pandeiro frequency zones (at 44100Hz, FFT=1024):
        // bin = freq / (SAMPLE_RATE / FFT_SIZE) = freq / 43.07
        final int bA = (int)(300f  / (SAMPLE_RATE / (float)FFT_SIZE)); // ~7
        final int bB = (int)(1200f / (SAMPLE_RATE / (float)FFT_SIZE)); // ~28
        final int bC = (int)(5000f / (SAMPLE_RATE / (float)FFT_SIZE)); // ~116
        final int bEnd = Math.min((int)(11000f / (SAMPLE_RATE / (float)FFT_SIZE)), FFT_SIZE/2-1); // ~255

        recordingThread = new Thread(() -> {
            while (isRecording) {
                int read = audioRecord.read(buf, 0, HOP_SIZE);
                if (read < HOP_SIZE) continue;

                // Build frame: [overlap | new samples]
                float[] frame = new float[FFT_SIZE];
                System.arraycopy(overlap, 0, frame, 0, HOP_SIZE);
                for (int i = 0; i < HOP_SIZE; i++) frame[HOP_SIZE + i] = buf[i] / 32768f;
                // Update overlap
                for (int i = 0; i < HOP_SIZE; i++) overlap[i] = buf[i] / 32768f;

                // Windowed FFT
                float[] re = new float[FFT_SIZE];
                float[] im = new float[FFT_SIZE];
                for (int i = 0; i < FFT_SIZE; i++) re[i] = frame[i] * hann[i];
                fft(re, im);

                // Magnitude (positive freqs)
                float[] mag = new float[FFT_SIZE / 2];
                for (int i = 0; i < FFT_SIZE / 2; i++)
                    mag[i] = (float) Math.sqrt(re[i]*re[i] + im[i]*im[i]);

                // Weighted spectral flux — pandeiro tuning:
                // 300-1200Hz   w=1.5 (body of peau hit)
                // 1200-5000Hz  w=3.5 (platinelas sweet spot)
                // 5000-11000Hz w=1.0 (shimmer)
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

                // Envelope: fast attack, very slow decay
                envelope = (flux > envelope)
                    ? envelope * 0.3f + flux * 0.7f   // fast attack
                    : envelope * 0.994f + flux * 0.006f; // slow decay

                // Noise floor: tracks only quiet frames (rises slowly, never below 0.01)
                if (flux < envelope * 0.4f) {
                    noiseFloor = noiseFloor * 0.99f + flux * 0.01f;
                }
                noiseFloor = Math.max(noiseFloor, 0.01f);

                // Local peak picker: store in circular buffer
                fluxBuf[fluxCount % PEAK_WIN] = flux;
                fluxCount++;
                if (fluxCount < PEAK_WIN) continue;

                // Center of window must be local max
                int ci = ((fluxCount - 1) - PEAK_WIN/2) % PEAK_WIN;
                if (ci < 0) ci += PEAK_WIN;
                float center = fluxBuf[ci];
                boolean isMax = true;
                for (int i = 0; i < PEAK_WIN; i++) {
                    if (i != ci && fluxBuf[i] >= center) { isMax = false; break; }
                }
                if (!isMax) continue;

                // Dynamic threshold:
                // sensitivity 1 → ratio=0.55 (strict)
                // sensitivity 5 → ratio=0.15 (loose)
                float ratio = 0.60f - sensitivity * 0.09f;
                float thresh = noiseFloor + ratio * Math.max(0, envelope - noiseFloor);

                if (center < thresh) continue;
                if (center < noiseFloor * 3f) continue;

                long now = System.currentTimeMillis();
                if ((now - lastBeatMs) < 140) continue; // max ~428 BPM
                lastBeatMs = now;

                // Compute interval and real-time BPM
                if (lastIntervalBeatMs > 0) {
                    long iv = now - lastIntervalBeatMs;
                    if (iv >= 140 && iv <= 2000) {
                        intervals.add(iv);
                        if (intervals.size() > MAX_INTERVALS) intervals.remove(0);
                    }
                }
                lastIntervalBeatMs = now;

                // BPM from median of last MAX_INTERVALS intervals
                Integer bpm = null;
                if (intervals.size() >= 2) {
                    ArrayList<Long> sorted = new ArrayList<>(intervals);
                    Collections.sort(sorted);
                    long med = sorted.get(sorted.size() / 2);
                    int raw = (int) Math.round(60000.0 / med);
                    if (raw >= 30 && raw <= 400) bpm = raw;
                }

                final Integer finalBpm = bpm;
                mainHandler.post(() -> {
                    pandeiroPulse.animate().scaleX(1.1f).scaleY(1.1f).setDuration(50)
                        .withEndAction(() -> pandeiroPulse.animate()
                            .scaleX(1f).scaleY(1f).setDuration(110).start()).start();
                    if (finalBpm != null) {
                        bpmText.setText(String.valueOf(finalBpm));
                        tempoText.setText(tempoName(finalBpm));
                    }
                    setStatus("Coup!", true);
                });
            }
        });
        recordingThread.start();
    }

    private void stopRecording() {
        isRecording = false;
        if (audioRecord != null) { audioRecord.stop(); audioRecord.release(); audioRecord = null; }
        startStopBtn.setText("DEMARRER");
        startStopBtn.setBackgroundColor(0xFFF0B429);
        setStatus("Arrete", false);
    }

    private void resetBPM() {
        intervals.clear();
        lastIntervalBeatMs = 0;
        noiseFloor = 0.01f; envelope = 0f; prevSpectrum = null;
        bpmText.setText("--");
        tempoText.setText("");
        setStatus(isRecording ? "Ecoute..." : "En attente", isRecording);
    }

    private void setStatus(final String msg, final boolean active) {
        mainHandler.post(() -> {
            statusText.setText(msg);
            statusText.setTextColor(active ? 0xFFD63B2F : 0xFF7A4E22);
        });
    }

    private String tempoName(int b) {
        if (b < 60)  return "Largo";
        if (b < 66)  return "Larghetto";
        if (b < 76)  return "Adagio";
        if (b < 108) return "Andante";
        if (b < 120) return "Moderato";
        if (b < 156) return "Allegro";
        if (b < 176) return "Vivace";
        if (b < 200) return "Presto";
        return "Prestissimo";
    }

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
                    float uR = re[i+j], uI = im[i+j];
                    float vR = re[i+j+len/2]*cRe - im[i+j+len/2]*cIm;
                    float vI = re[i+j+len/2]*cIm + im[i+j+len/2]*cRe;
                    re[i+j]=uR+vR; im[i+j]=uI+vI;
                    re[i+j+len/2]=uR-vR; im[i+j+len/2]=uI-vI;
                    float nR = cRe*wRe - cIm*wIm;
                    cIm = cRe*wIm + cIm*wRe; cRe = nR;
                }
            }
        }
    }

    @Override
    protected void onDestroy() { super.onDestroy(); stopRecording(); }
}
