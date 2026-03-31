package com.osc;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.media.*;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;

public class MainActivity extends Activity {

    private static final int AUDIO_SR  = 44100;
    private static final int REQ_PERMS = 1;

    private OscilloscopeView mOscView;
    private VerticalSeekBar  mSeekGain;
    private TextView         mTvGain, mTvStatus;
    private Button           mSrcToggleBtn;
    private Spinner          mSpinner;
    private VuMeterView      mVu;
    private LinearLayout     mAudioSrcPanel;
    private CheckBox         mCbSoftClip, mCbTrigger;
    private boolean          mAudioSrcExpanded = false;

    private volatile float   mGain      = 1f;
    private volatile boolean mSoftClip  = true;
    private volatile boolean mAudRunning;
    private AudioRecord      mAudRec;
    private Thread           mAudThread;
    private final List<AudioSrcItem> mSrcList = new ArrayList<>();
    private boolean          mPermsOk;

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
            WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(buildLayout());
        checkPerms();
    }

    @Override protected void onDestroy() { super.onDestroy(); stopAudio(); }

    // =========================================================================
    // Layout
    // =========================================================================

    private View buildLayout() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        root.addView(outer, mp_mp());

        mOscView = new OscilloscopeView(this);
        outer.addView(mOscView, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ── Gain-слайдер (полная высота, левый край) ──────────────────────────
        mSeekGain = new VerticalSeekBar(this);
        mSeekGain.setMax(800);
        mSeekGain.setProgress(400);
        mSeekGain.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean u) {
                float db = -20f + p * 40f / 800f;
                mGain = (float) Math.pow(10.0, db / 20.0);
                if (mTvGain != null)
                    mTvGain.setText(String.format("%+.1f", db) + "dB");
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        FrameLayout.LayoutParams gainLP =
            new FrameLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.MATCH_PARENT);
        gainLP.gravity = Gravity.LEFT;
        root.addView(mSeekGain, gainLP);

        TextView tvGainLbl = smallLabel("GAIN");
        FrameLayout.LayoutParams lp1 =
            new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp1.gravity = Gravity.LEFT | Gravity.TOP;
        lp1.leftMargin = dp(6); lp1.topMargin = dp(4);
        root.addView(tvGainLbl, lp1);

        mTvGain = smallLabel("+0.0dB");
        FrameLayout.LayoutParams lp2 =
            new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp2.gravity = Gravity.LEFT | Gravity.BOTTOM;
        lp2.leftMargin = dp(6); lp2.bottomMargin = dp(4);
        root.addView(mTvGain, lp2);

        // ── Нижняя панель ─────────────────────────────────────────────────────
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xCC000000);
        panel.setPadding(dp(52), dp(2), dp(8), dp(3));
        outer.addView(panel, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT));

        mSrcToggleBtn = new Button(this);
        mSrcToggleBtn.setText("\u2699");
        mSrcToggleBtn.setAllCaps(false);
        mSrcToggleBtn.setTextSize(22);
        mSrcToggleBtn.setTextColor(0xFFBBBBBB);
        mSrcToggleBtn.setBackground(null);
        mSrcToggleBtn.setPadding(0, 0, dp(8), 0);
        mSrcToggleBtn.setOnClickListener(v -> {
            mAudioSrcExpanded = !mAudioSrcExpanded;
            mAudioSrcPanel.setVisibility(mAudioSrcExpanded ? View.VISIBLE : View.GONE);
            mSrcToggleBtn.setText(mAudioSrcExpanded ? "\u2699\u25b4" : "\u2699");
        });

        mTvStatus = new TextView(this);
        mTvStatus.setTextColor(0xFFAAAAAA);
        mTvStatus.setTextSize(11);
        mTvStatus.setText("Ready");
        mTvStatus.setSingleLine(false);
        mTvStatus.setMaxLines(2);
        mTvStatus.setLayoutParams(new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        panel.addView(hrow(mSrcToggleBtn, mTvStatus));

        // Схлопываемая панель настроек
        mAudioSrcPanel = new LinearLayout(this);
        mAudioSrcPanel.setOrientation(LinearLayout.VERTICAL);
        mAudioSrcPanel.setVisibility(View.GONE);
        mAudioSrcPanel.setPadding(dp(4), dp(2), dp(4), dp(2));

        mSpinner = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<>(
            this, android.R.layout.simple_spinner_item, new ArrayList<String>());
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mSpinner.setAdapter(ad);
        mSpinner.setLayoutParams(new LinearLayout.LayoutParams(
            dp(240), ViewGroup.LayoutParams.WRAP_CONTENT));
        mSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                stopAudio(); startAudio();
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        LinearLayout srcRow = new LinearLayout(this);
        srcRow.setOrientation(LinearLayout.HORIZONTAL);
        srcRow.setGravity(Gravity.CENTER_VERTICAL);
        srcRow.addView(smallLabel("Src: "));
        srcRow.addView(mSpinner);
        mAudioSrcPanel.addView(srcRow);

        mCbSoftClip = new CheckBox(this);
        mCbSoftClip.setText("Soft clip");
        mCbSoftClip.setTextColor(0xCCCCCCCC);
        mCbSoftClip.setTextSize(12);
        mCbSoftClip.setChecked(true);
        mCbSoftClip.setOnCheckedChangeListener((cb, c) -> mSoftClip = c);

        mCbTrigger = new CheckBox(this);
        mCbTrigger.setText("Trigger sync");
        mCbTrigger.setTextColor(0xCCCCCCCC);
        mCbTrigger.setTextSize(12);
        mCbTrigger.setChecked(true);
        mCbTrigger.setOnCheckedChangeListener((cb, c) -> mOscView.setTrigger(c));

        LinearLayout cbRow = new LinearLayout(this);
        cbRow.setOrientation(LinearLayout.HORIZONTAL);
        cbRow.setGravity(Gravity.CENTER_VERTICAL);
        cbRow.addView(mCbSoftClip);
        cbRow.addView(mCbTrigger);
        mAudioSrcPanel.addView(cbRow);
        panel.addView(mAudioSrcPanel);

        mVu = new VuMeterView(this);
        LinearLayout.LayoutParams vuLP = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(28));
        vuLP.topMargin = dp(2);
        panel.addView(mVu, vuLP);

        return root;
    }

    // =========================================================================
    // Разрешения
    // =========================================================================

    private void checkPerms() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                new String[]{Manifest.permission.RECORD_AUDIO}, REQ_PERMS);
        } else {
            mPermsOk = true;
            buildAudioSources();
        }
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) {
            mPermsOk = true;
            buildAudioSources();
        } else {
            status("Microphone permission required");
        }
    }

    // =========================================================================
    // Аудио
    // =========================================================================

    @SuppressLint("MissingPermission")
    private void startAudio() {
        if (!mPermsOk) return;
        int pos = mSpinner.getSelectedItemPosition();
        if (pos < 0 || pos >= mSrcList.size()) return;
        AudioSrcItem item = mSrcList.get(pos);

        int minBuf = AudioRecord.getMinBufferSize(
            AUDIO_SR,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(minBuf, AUDIO_SR / 10 * 2); // >= 100 ms

        try {
            mAudRec = new AudioRecord(
                item.audioSource, AUDIO_SR,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize);
            if (Build.VERSION.SDK_INT >= 23 && item.device != null)
                mAudRec.setPreferredDevice(item.device);
            disableAudioEffects(mAudRec.getAudioSessionId());
        } catch (Exception e) {
            status("Audio error: " + e.getMessage());
            return;
        }

        if (mAudRec.getState() != AudioRecord.STATE_INITIALIZED) {
            mAudRec.release(); mAudRec = null;
            status("AudioRecord init failed");
            return;
        }

        mAudRunning = true;
        status("\u25cf " + item.name);
        mAudThread = new Thread(this::audioLoop, "osc-audio");
        mAudThread.setDaemon(true);
        mAudThread.start();
    }

    private void stopAudio() {
        mAudRunning = false;
        AudioRecord rec = mAudRec;
        if (rec != null) try { rec.stop(); } catch (Exception ignored) {}
        if (mAudThread != null) {
            try { mAudThread.join(500); } catch (InterruptedException ignored) {}
            mAudThread = null;
        }
        if (mAudRec != null) {
            try { mAudRec.release(); } catch (Exception ignored) {}
            mAudRec = null;
        }
        if (mVu != null) mVu.setLevel(0f);
    }

    private void audioLoop() {
        final AudioRecord rec    = mAudRec;
        final int         chunk  = AUDIO_SR / 50; // 20 ms = 882 samples
        short[]           buf    = new short[chunk];
        rec.startRecording();

        while (mAudRunning) {
            int r = rec.read(buf, 0, chunk);
            if (r <= 0) continue;

            final float   g  = mGain;
            final boolean sc = mSoftClip;
            long sumSq = 0;

            for (int i = 0; i < r; i++) {
                float s = buf[i] * g;
                if (sc) {
                    final float T    = 32768f * 0.7f;
                    final float knee = 32768f - T;
                    float abs = Math.abs(s);
                    if (abs > T)
                        s = Math.signum(s) *
                            (T + knee * (float) Math.tanh((abs - T) / knee));
                }
                s = Math.max(-32768f, Math.min(32767f, s));
                buf[i] = (short) s;
                sumSq += (long) buf[i] * buf[i];
            }

            mVu.setLevel((float) Math.sqrt((double) sumSq / r) / 32768f);
            mOscView.pushSamples(buf, r);
        }

        mVu.setLevel(0f);
        try { rec.stop(); } catch (Exception ignored) {}
    }

    private void disableAudioEffects(int sid) {
        try {
            if (AutomaticGainControl.isAvailable()) {
                AutomaticGainControl a = AutomaticGainControl.create(sid);
                if (a != null) { a.setEnabled(false); a.release(); }
            }
        } catch (Exception ignored) {}
        try {
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor n = NoiseSuppressor.create(sid);
                if (n != null) { n.setEnabled(false); n.release(); }
            }
        } catch (Exception ignored) {}
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler e = AcousticEchoCanceler.create(sid);
                if (e != null) { e.setEnabled(false); e.release(); }
            }
        } catch (Exception ignored) {}
    }

    // =========================================================================
    // Аудио-источники
    // =========================================================================

    private void buildAudioSources() {
        new Thread(() -> {
            List<AudioSrcItem> list = new ArrayList<>();
            if (Build.VERSION.SDK_INT >= 23) {
                AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                AudioDeviceInfo[] devs = am.getDevices(AudioManager.GET_DEVICES_INPUTS);
                boolean hasBuiltin = false;
                for (AudioDeviceInfo d : devs) {
                    int t = d.getType();
                    if (t == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                        if (hasBuiltin) continue;
                        hasBuiltin = true;
                        list.add(new AudioSrcItem("Built-in mic",
                            MediaRecorder.AudioSource.MIC, d));
                        if (Build.VERSION.SDK_INT >= 24)
                            list.add(new AudioSrcItem("Built-in raw (unprocessed)",
                                MediaRecorder.AudioSource.UNPROCESSED, d));
                    } else if (t == AudioDeviceInfo.TYPE_USB_DEVICE
                            || t == AudioDeviceInfo.TYPE_USB_HEADSET) {
                        CharSequence pn = d.getProductName();
                        list.add(new AudioSrcItem(
                            "USB: " + (pn != null && pn.length() > 0 ? pn : "audio"),
                            MediaRecorder.AudioSource.MIC, d));
                    } else if (t == AudioDeviceInfo.TYPE_WIRED_HEADSET) {
                        list.add(new AudioSrcItem("Wired headset",
                            MediaRecorder.AudioSource.MIC, d));
                    } else if (t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                        list.add(new AudioSrcItem("Bluetooth mic",
                            MediaRecorder.AudioSource.MIC, d));
                    }
                }
            }
            if (list.isEmpty()) {
                list.add(new AudioSrcItem("Microphone",
                    MediaRecorder.AudioSource.MIC, null));
                if (Build.VERSION.SDK_INT >= 24)
                    list.add(new AudioSrcItem("Unprocessed (raw)",
                        MediaRecorder.AudioSource.UNPROCESSED, null));
            }
            final List<AudioSrcItem> finalList = list;
            runOnUiThread(() -> {
                mSrcList.clear();
                mSrcList.addAll(finalList);
                List<String> names = new ArrayList<>();
                for (AudioSrcItem item : mSrcList) names.add(item.name);
                @SuppressWarnings("unchecked")
                ArrayAdapter<String> adapter =
                    (ArrayAdapter<String>) mSpinner.getAdapter();
                adapter.clear();
                adapter.addAll(names);
                adapter.notifyDataSetChanged();

                // Приоритет: USB > UNPROCESSED > первый в списке
                int idx = 0;
                outer:
                for (int pass = 0; pass < 2; pass++) {
                    for (int i = 0; i < mSrcList.size(); i++) {
                        AudioSrcItem item = mSrcList.get(i);
                        if (pass == 0 && item.device != null
                                && Build.VERSION.SDK_INT >= 23) {
                            int t = item.device.getType();
                            if (t == AudioDeviceInfo.TYPE_USB_DEVICE
                                    || t == AudioDeviceInfo.TYPE_USB_HEADSET) {
                                idx = i; break outer;
                            }
                        }
                        if (pass == 1 && item.audioSource ==
                                MediaRecorder.AudioSource.UNPROCESSED) {
                            idx = i; break outer;
                        }
                    }
                }
                mSpinner.setSelection(idx);
                stopAudio();
                startAudio();
            });
        }).start();
    }

    // =========================================================================
    // Утилиты
    // =========================================================================

    private void status(String s) {
        runOnUiThread(() -> { if (mTvStatus != null) mTvStatus.setText(s); });
    }

    private TextView smallLabel(String t) {
        TextView v = new TextView(this);
        v.setText(t); v.setTextColor(0xCCCCCCCC); v.setTextSize(11);
        v.setBackgroundColor(0x88000000);
        v.setPadding(dp(3), dp(1), dp(3), dp(1));
        return v;
    }

    private LinearLayout hrow(View... views) {
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.HORIZONTAL);
        ll.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(2);
        ll.setLayoutParams(lp);
        for (View v : views) {
            if (v.getLayoutParams() == null)
                v.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            ll.addView(v);
        }
        return ll;
    }

    private ViewGroup.LayoutParams mp_mp() {
        return new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(int x) {
        return Math.round(x * getResources().getDisplayMetrics().density);
    }

    // =========================================================================
    // Вспомогательные классы
    // =========================================================================

    private static class AudioSrcItem {
        final String          name;
        final int             audioSource;
        final AudioDeviceInfo device;
        AudioSrcItem(String n, int s, AudioDeviceInfo d) {
            name = n; audioSource = s; device = d;
        }
    }

    // ─── OscilloscopeView ─────────────────────────────────────────────────────
    //
    // Кольцевой буфер 500 мс.
    // Trigger: rising zero-crossing в окне глубиной disp/2 перед текущим head.
    // Phosphor-эффект: широкий полупрозрачный штрих + тонкая яркая линия.
    // Двойной тап — переключение масштаба 1/2/5/10/20 мс/дел.

    static class OscilloscopeView extends View {

        private static final int[] TIME_DIV_SAMPLES = {
            441,    // 1  ms/div  (~  8 ms window)
            882,    // 2  ms/div  (~ 16 ms)
            2205,   // 5  ms/div  (~ 40 ms)  ← default
            4410,   // 10 ms/div  (~ 80 ms)
            8820,   // 20 ms/div  (~160 ms)
        };
        private static final String[] TIME_DIV_LABELS = {
            "1ms/div", "2ms/div", "5ms/div", "10ms/div", "20ms/div"
        };

        private static final int BUF    = 44100 / 2;  // 500 ms ring buffer
        private static final int H_DIVS = 8;
        private static final int V_DIVS = 6;

        private final float[] mRing    = new float[BUF];
        private int            mHead   = 0;
        private boolean        mTrigger = true;
        private int            mTimeIdx = 2;           // 5ms/div default
        private final Object   mLock   = new Object();

        private float[]        mSnap;
        private final Path     mPath   = new Path();

        private final Paint mBgPaint     = new Paint();
        private final Paint mGridPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mCenterPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mGlowPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mLinePaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mLabelPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mTrigPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);

        OscilloscopeView(Context c) {
            super(c);
            final float d = c.getResources().getDisplayMetrics().density;

            mBgPaint.setColor(0xFF040A04);

            mGridPaint.setColor(0x2200CC00);
            mGridPaint.setStrokeWidth(1f);
            mGridPaint.setStyle(Paint.Style.STROKE);

            mCenterPaint.setColor(0x5500BB00);
            mCenterPaint.setStrokeWidth(1.5f);
            mCenterPaint.setStyle(Paint.Style.STROKE);

            mGlowPaint.setColor(0x3800DD44);
            mGlowPaint.setStrokeWidth(7f * d);
            mGlowPaint.setStyle(Paint.Style.STROKE);
            mGlowPaint.setStrokeCap(Paint.Cap.ROUND);
            mGlowPaint.setStrokeJoin(Paint.Join.ROUND);

            mLinePaint.setColor(0xFF22FF55);
            mLinePaint.setStrokeWidth(1.6f * d);
            mLinePaint.setStyle(Paint.Style.STROKE);
            mLinePaint.setStrokeCap(Paint.Cap.ROUND);
            mLinePaint.setStrokeJoin(Paint.Join.ROUND);

            mLabelPaint.setColor(0x7700CC44);
            mLabelPaint.setTextSize(8.5f * d);

            mTrigPaint.setColor(0xFFDDCC00);
            mTrigPaint.setStyle(Paint.Style.FILL);

            // Двойной тап — следующий масштаб
            final GestureDetector gd = new GestureDetector(c,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDoubleTap(MotionEvent e) {
                        mTimeIdx = (mTimeIdx + 1) % TIME_DIV_SAMPLES.length;
                        postInvalidate();
                        return true;
                    }
                });
            setOnTouchListener((v, ev) -> gd.onTouchEvent(ev));
        }

        void setTrigger(boolean on) { mTrigger = on; }

        void pushSamples(short[] buf, int count) {
            synchronized (mLock) {
                for (int i = 0; i < count; i++) {
                    mRing[mHead] = buf[i] / 32768f;
                    mHead = (mHead + 1) % BUF;
                }
            }
            postInvalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldW, int oldH) {
            super.onSizeChanged(w, h, oldW, oldH);
            mSnap = new float[BUF];
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float w = getWidth(), h = getHeight();
            if (w < 4 || h < 4) return;

            final int disp = TIME_DIV_SAMPLES[mTimeIdx];

            // ── Фон ───────────────────────────────────────────────────────────
            canvas.drawRect(0, 0, w, h, mBgPaint);

            // ── Сетка ─────────────────────────────────────────────────────────
            for (int i = 1; i < H_DIVS; i++) {
                float x = w * i / H_DIVS;
                canvas.drawLine(x, 0, x, h,
                    i == H_DIVS / 2 ? mCenterPaint : mGridPaint);
            }
            for (int i = 1; i < V_DIVS; i++) {
                float y = h * i / V_DIVS;
                canvas.drawLine(0, y, w, y,
                    i == V_DIVS / 2 ? mCenterPaint : mGridPaint);
            }

            // ── Снапшот ───────────────────────────────────────────────────────
            int snapLen = Math.min(disp, BUF);
            if (mSnap == null || mSnap.length < snapLen) mSnap = new float[BUF];

            synchronized (mLock) {
                int freeStart = (mHead + BUF - disp) % BUF;
                int start     = freeStart;

                if (mTrigger) {
                    // Rising zero-crossing: ищем в окне глубиной disp/2
                    int depth = Math.min(disp / 2, BUF - disp);
                    for (int i = 1; i <= depth; i++) {
                        int idx  = (freeStart + BUF - i)    % BUF;
                        int prev = (idx       + BUF - 1)    % BUF;
                        if (mRing[prev] < 0f && mRing[idx] >= 0f) {
                            start = idx;
                            break;
                        }
                    }
                }

                for (int i = 0; i < snapLen; i++)
                    mSnap[i] = mRing[(start + i) % BUF];
            }

            // ── Волна ─────────────────────────────────────────────────────────
            final float midY   = h / 2f;
            final float scaleY = h / 2f * 0.88f;
            final float scaleX = w / (float)(snapLen - 1);

            mPath.rewind();
            for (int i = 0; i < snapLen; i++) {
                float x = i * scaleX;
                float y = midY - mSnap[i] * scaleY;
                if (i == 0) mPath.moveTo(x, y);
                else        mPath.lineTo(x, y);
            }
            canvas.drawPath(mPath, mGlowPaint);
            canvas.drawPath(mPath, mLinePaint);

            // ── Метки амплитуды ───────────────────────────────────────────────
            mLabelPaint.setTextAlign(Paint.Align.RIGHT);
            for (float a : new float[]{1f, 0.5f, 0f, -0.5f, -1f}) {
                float y = midY - a * scaleY;
                canvas.drawText(String.format("%.1f", a), w - 4, y - 2, mLabelPaint);
            }

            // ── Масштаб времени (нижний левый угол) ───────────────────────────
            mLabelPaint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(
                TIME_DIV_LABELS[mTimeIdx]
                + "  "
                + String.format("%.0f", snapLen * 1000f / 44100f) + "ms"
                + "  (2\u00d7tap)",
                dp(46), h - 5, mLabelPaint);

            // ── Индикатор trigger ─────────────────────────────────────────────
            if (mTrigger)
                canvas.drawCircle(w / 2f, dp(5), dp(4), mTrigPaint);
        }

        private int dp(int x) {
            return Math.round(x * getResources().getDisplayMetrics().density);
        }
    }

    // ─── Вертикальный SeekBar ─────────────────────────────────────────────────

    static class VerticalSeekBar extends View {
        private int    mMax = 100, mProgress = 0;
        private final Paint mTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mFillPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mThumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private SeekBar.OnSeekBarChangeListener mListener;

        VerticalSeekBar(Context c) {
            super(c);
            mTrackPaint.setColor(0x44FFFFFF);
            mFillPaint.setColor(0xFF22FF55);
            mThumbPaint.setColor(0xFFFFFFFF);
            setClickable(true);
        }

        void setMax(int max)    { mMax = max; invalidate(); }
        void setProgress(int p) { mProgress = Math.max(0, Math.min(mMax, p)); invalidate(); }
        int  getMax()           { return mMax; }
        int  getProgress()      { return mProgress; }
        void setOnSeekBarChangeListener(SeekBar.OnSeekBarChangeListener l) { mListener = l; }

        @Override
        protected void onDraw(Canvas canvas) {
            final float w = getWidth(), h = getHeight();
            final float trackW = w * 0.35f, cx = w / 2f;
            final float trkX1  = cx - trackW / 2f, trkX2 = cx + trackW / 2f;
            final float thumbR = w * 0.42f, padV = thumbR + 2f;
            final float trkT   = padV, trkB = h - padV, trkH = trkB - trkT;
            final float frac   = mMax > 0 ? (float) mProgress / mMax : 0f;
            final float thumbY = trkB - frac * trkH;

            canvas.drawRoundRect(new RectF(trkX1, trkT, trkX2, trkB),
                trackW / 2f, trackW / 2f, mTrackPaint);
            canvas.drawRoundRect(new RectF(trkX1, thumbY, trkX2, trkB),
                trackW / 2f, trackW / 2f, mFillPaint);

            // Метка 0 dB
            Paint z = new Paint(); z.setColor(0x88FFFFFF); z.setStrokeWidth(1.5f);
            float zY = trkB - 0.5f * trkH;
            canvas.drawLine(trkX1 - 4f, zY, trkX2 + 4f, zY, z);

            canvas.drawCircle(cx, thumbY, thumbR, mThumbPaint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (!isEnabled()) return false;
            final float h = getHeight(), w = getWidth();
            final float thumbR = w * 0.42f, padV = thumbR + 2f;
            final float trkT   = padV, trkH = (h - padV) - trkT;
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    if (mListener != null) mListener.onStartTrackingTouch(null);
                    // fall through
                case MotionEvent.ACTION_MOVE: {
                    float frac = 1f - (e.getY() - trkT) / trkH;
                    int p = Math.max(0, Math.min(mMax, Math.round(frac * mMax)));
                    mProgress = p; invalidate();
                    if (mListener != null) mListener.onProgressChanged(null, p, true);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (mListener != null) mListener.onStopTrackingTouch(null);
                    return true;
            }
            return false;
        }
    }

    // ─── VU-метр dBFS ─────────────────────────────────────────────────────────

    static class VuMeterView extends View {
        private static final int   N      = 30;
        private static final float MIN_DB = -60f;

        private final Paint mSegPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mLblPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRect     = new RectF();
        private float mLevelDb        = MIN_DB;

        VuMeterView(Context c) {
            super(c);
            mLblPaint.setColor(0xAAFFFFFF);
            mLblPaint.setTextAlign(Paint.Align.CENTER);
            mLblPaint.setTextSize(9f * c.getResources().getDisplayMetrics().density);
        }

        void setLevel(float rms) {
            mLevelDb = rms > 1e-6f
                ? Math.max(MIN_DB, (float)(20.0 * Math.log10(rms)))
                : MIN_DB;
            postInvalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float w = getWidth(), h = getHeight();
            final float segW = (w - N - 1f) / N;
            final float segH = h * 0.55f;
            final float segY = (h - segH) / 2f;

            for (int i = 0; i < N; i++) {
                float segDb = MIN_DB + (float) i / N * (-MIN_DB);
                boolean lit = mLevelDb >= segDb;
                int color;
                if      (segDb < -12f) color = lit ? 0xFF22FF55 : 0x22224422;
                else if (segDb <  -3f) color = lit ? 0xFFFFCC00 : 0x22332200;
                else                   color = lit ? 0xFFFF3300 : 0x22330000;
                mSegPaint.setColor(color);
                float x = 1f + i * (segW + 1f);
                mRect.set(x, segY, x + segW, segY + segH);
                canvas.drawRoundRect(mRect, 2f, 2f, mSegPaint);
            }
            for (int db : new int[]{-60, -48, -36, -24, -12, -6, -3, 0}) {
                float frac = (db - MIN_DB) / (-MIN_DB);
                canvas.drawText(db == 0 ? "0" : String.valueOf(db),
                    1f + frac * (w - 2f), h, mLblPaint);
            }
        }
    }
}
