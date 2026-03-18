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
    private static final int FFT_SIZE = 2048;

    private AudioRecord audioRecord;
    private Thread recordingThread;
    private volatile boolean isRecording = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private float[] prevSpectrum = null;
    private final ArrayList<Float> fluxHistory = new ArrayList<>();
    private long lastBeatTime = 0;
    private final ArrayList<Long> beatTimes = new ArrayList<>();
    private int sensitivity = 3;

    private TextView bpmText, tempoText, statusText;
    private Button startStopBtn;
    private android.view.View pandeiroPulse;
    private SeekBar sensBar;
    private TextView sensVal;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        bpmText      = findViewById(R.id.bpmText);
        tempoText    = findViewById(R.id.tempoText);
        statusText   = findViewById(R.id.statusText);
        startStopBtn = findViewById(R.id.startStopBtn);
        pandeiroPulse= findViewById(R.id.pandeiroPulse);
        sensBar      = findViewById(R.id.sensBar);
        sensVal      = findViewById(R.id.sensVal);

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
            if (isRecording) stopRecording();
            else checkPermissionAndStart();
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
            setStatus("Permission micro refusee", false);
        }
    }

    private void startRecording() {
        int bufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 4;

        audioRecord = new AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufSize
        );

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            setStatus("Erreur initialisation micro", false);
            return;
        }

        isRecording = true;
        audioRecord.startRecording();
        startStopBtn.setText("ARRETER");
        startStopBtn.setBackgroundColor(0xFFD63B2F);
        setStatus("Ecoute en cours...", true);

        prevSpectrum = null;
        fluxHistory.clear();
        lastBeatTime = 0;

        final int hopSize = FFT_SIZE / 2;
        final short[] buffer = new short[hopSize];
        final float[] hannWindow = makeHannWindow(FFT_SIZE);
        final float[] frame = new float[FFT_SIZE];
        final float[] overlap = new float[FFT_SIZE - hopSize];

        final int binLowEnd  = (int)(500f  * FFT_SIZE / SAMPLE_RATE);
        final int binMidEnd  = (int)(4000f * FFT_SIZE / SAMPLE_RATE);
        final int binHighEnd = Math.min((int)(12000f * FFT_SIZE / SAMPLE_RATE), FFT_SIZE / 2);

        recordingThread = new Thread(() -> {
            while (isRecording) {
                int read = audioRecord.read(buffer, 0, hopSize);
                if (read <= 0) continue;

                System.arraycopy(overlap, 0, frame, 0, FFT_SIZE - hopSize);
                for (int i = 0; i < hopSize; i++)
                    frame[FFT_SIZE - hopSize + i] = buffer[i] / 32768f;
                System.arraycopy(frame, hopSize, overlap, 0, FFT_SIZE - hopSize);

                float[] windowed = new float[FFT_SIZE];
                for (int i = 0; i < FFT_SIZE; i++)
                    windowed[i] = frame[i] * hannWindow[i];

                float[] re = windowed.clone();
                float[] im = new float[FFT_SIZE];
                fft(re, im);

                int halfN = FFT_SIZE / 2;
                float[] mag = new float[halfN];
                for (int i = 0; i < halfN; i++)
                    mag[i] = (float) Math.sqrt(re[i]*re[i] + im[i]*im[i]);

                float maxMag = 0;
                for (float m : mag) if (m > maxMag) maxMag = m;
                if (maxMag > 0)
                    for (int i = 0; i < halfN; i++) mag[i] /= maxMag;

                // Weighted spectral flux tuned for pandeiro:
                // Low  0-500Hz   w=1.0 : coup de peau
                // Mid  500-4kHz  w=2.0 : attaque platinelas
                // High 4k-12kHz  w=1.2 : shimmer cymbales
                float flux = 0;
                if (prevSpectrum != null) {
                    for (int i = 0; i < binHighEnd; i++) {
                        float d = mag[i] - prevSpectrum[i];
                        if (d > 0) {
                            float w;
                            if (i < binLowEnd)      w = 1.0f;
                            else if (i < binMidEnd) w = 2.0f;
                            else                    w = 1.2f;
                            flux += d * w;
                        }
                    }
                }
                prevSpectrum = mag.clone();

                fluxHistory.add(flux);
                if (fluxHistory.size() > 60) fluxHistory.remove(0);

                float mean = 0;
                for (float f : fluxHistory) mean += f;
                mean /= fluxHistory.size();

                float variance = 0;
                for (float f : fluxHistory) variance += (f - mean) * (f - mean);
                float stdev = (float) Math.sqrt(variance / fluxHistory.size());

                float mult = 3.0f - sensitivity * 0.4f;
                float thresh = mean + mult * stdev;

                long now = System.currentTimeMillis();
                long minGap = sensitivity >= 4 ? 150L : 260L;

                if (flux > thresh && flux > 0.1f && (now - lastBeatTime) > minGap) {
                    lastBeatTime = now;
                    onBeat(now);
                }
            }
        });
        recordingThread.start();
    }

    private void onBeat(long now) {
        beatTimes.add(now);
        if (beatTimes.size() > 24) beatTimes.remove(0);
        Integer bpm = calcBPM();
        mainHandler.post(() -> {
            pandeiroPulse.animate().scaleX(1.08f).scaleY(1.08f).setDuration(60)
                .withEndAction(() -> pandeiroPulse.animate().scaleX(1f).scaleY(1f).setDuration(100).start())
                .start();
            if (bpm != null) {
                bpmText.setText(String.valueOf(bpm));
                tempoText.setText(tempoName(bpm));
            }
            setStatus("Coup detecte", true);
        });
    }

    private Integer calcBPM() {
        if (beatTimes.size() < 2) return null;
        ArrayList<Long> intervals = new ArrayList<>();
        for (int i = 1; i < beatTimes.size(); i++)
            intervals.add(beatTimes.get(i) - beatTimes.get(i-1));
        Collections.sort(intervals);
        long med = intervals.get(intervals.size() / 2);
        ArrayList<Long> clean = new ArrayList<>();
        for (long iv : intervals)
            if (iv > med * 0.5 && iv < med * 2.5) clean.add(iv);
        if (clean.isEmpty()) return null;
        long sum = 0;
        for (long iv : clean) sum += iv;
        int bpm = (int) Math.round(60000.0 / (sum / clean.size()));
        return (bpm >= 20 && bpm <= 400) ? bpm : null;
    }

    private void stopRecording() {
        isRecording = false;
        if (audioRecord != null) {
            audioRecord.stop();
            audioRecord.release();
            audioRecord = null;
        }
        startStopBtn.setText("DEMARRER");
        startStopBtn.setBackgroundColor(0xFFF0B429);
        setStatus("Arrete", false);
    }

    private void resetBPM() {
        beatTimes.clear();
        fluxHistory.clear();
        prevSpectrum = null;
        bpmText.setText("--");
        tempoText.setText("");
        setStatus(isRecording ? "Ecoute en cours..." : "En attente", isRecording);
    }

    private void setStatus(String msg, boolean active) {
        mainHandler.post(() -> {
            statusText.setText(msg);
            statusText.setTextColor(active ? 0xFFD63B2F : 0xFF7A4E22);
        });
    }

    private String tempoName(int bpm) {
        if (bpm < 60)  return "Largo";
        if (bpm < 66)  return "Larghetto";
        if (bpm < 76)  return "Adagio";
        if (bpm < 108) return "Andante";
        if (bpm < 120) return "Moderato";
        if (bpm < 156) return "Allegro";
        if (bpm < 176) return "Vivace";
        if (bpm < 200) return "Presto";
        return "Prestissimo";
    }

    private float[] makeHannWindow(int size) {
        float[] w = new float[size];
        for (int i = 0; i < size; i++)
            w[i] = (float)(0.5 * (1 - Math.cos(2 * Math.PI * i / (size - 1))));
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
            float wRe = (float) Math.cos(ang);
            float wIm = (float) Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                float curRe = 1, curIm = 0;
                for (int j = 0; j < len / 2; j++) {
                    float uRe = re[i+j], uIm = im[i+j];
                    float vRe = re[i+j+len/2]*curRe - im[i+j+len/2]*curIm;
                    float vIm = re[i+j+len/2]*curIm + im[i+j+len/2]*curRe;
                    re[i+j] = uRe+vRe; im[i+j] = uIm+vIm;
                    re[i+j+len/2] = uRe-vRe; im[i+j+len/2] = uIm-vIm;
                    float newCurRe = curRe*wRe - curIm*wIm;
                    curIm = curRe*wIm + curIm*wRe;
                    curRe = newCurRe;
                }
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopRecording();
    }
}
