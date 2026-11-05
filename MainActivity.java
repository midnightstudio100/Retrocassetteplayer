package com.muse.retrocassette;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.net.Uri;
import android.database.Cursor;
import android.provider.OpenableColumns;
import java.util.ArrayList;

public class MainActivity extends Activity {

    private RadioService service;
    private boolean bound = false;

    private TextView stationName, stationDesc, statusText, tapeCounter;
    private ImageView reelLeft, reelRight;
    private Button btnPlay, btnPause, btnPrev, btnStop, btnNext, btnRew, btnFf, btnEject;
    private SeekBar volumeSeek;
    private LinearLayout stationListContainer;
    private ArrayList<TextView> stationRows = new ArrayList<>();
    private ListView musicList;
    private TextView musicTrack;
    private Button btnPickMusic, btnShuffle;
    private Button btnTabTapes, btnTabRadio, btnRec, btnFm, btnAm, btnCounterReset, btnStations, btnBluetooth;
    private Button btnPreset1, btnPreset2, btnPreset3, btnPreset4, btnPreset5, btnPreset6;
    private LinearLayout musicSection, stationSection;
    private TextView tunerFreq, tunerStation, tunerDesc, tunerBand;
    private ArrayList<String> musicUris = new ArrayList<>();
    private ArrayList<String> musicNames = new ArrayList<>();
    private ArrayAdapter<String> musicAdapter;
    private boolean musicModeLocal = false;
    private int musicIndexLocal = 0;
    private static final int REQ_PICK_MUSIC = 1001;
    private static final int REQ_MUSIC_PERMISSION = 1002;
    private static final int REQ_BLUETOOTH_PERMISSION = 1003;

    private boolean hasBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= 31) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }
    private void openBluetoothSettings() {
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS));
        } catch (Exception e) {
            Toast.makeText(this, "Open Settings > Connections > Bluetooth to connect headphones", Toast.LENGTH_LONG).show();
        }
    }

    private String musicPermissionName() {
        if (Build.VERSION.SDK_INT >= 33) return Manifest.permission.READ_MEDIA_AUDIO;
        return Manifest.permission.READ_EXTERNAL_STORAGE;
    }
    private boolean hasMusicPermission() {
        return checkSelfPermission(musicPermissionName()) == PackageManager.PERMISSION_GRANTED;
    }
    private void requestMusicPermission() {
        requestPermissions(new String[]{musicPermissionName()}, REQ_MUSIC_PERMISSION);
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_MUSIC_PERMISSION && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            pickMusic();
        }
        if (requestCode == REQ_BLUETOOTH_PERMISSION && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Bluetooth allowed - tap the Bluetooth button to connect headphones", Toast.LENGTH_SHORT).show();
        }
        if (requestCode == 1004) {
            for (int i = 0; i < permissions.length && i < grantResults.length; i++) {
                if (Manifest.permission.BLUETOOTH_CONNECT.equals(permissions[i])
                        && grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Bluetooth allowed - tap the Bluetooth button to connect headphones", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }
    private AudioManager audioManager;
    private SoundPool clickPool;
    private int clickSoundId = 0;
    private boolean clickLoaded = false;

    private ObjectAnimator animLeft, animRight;
    private Handler counterHandler = new Handler();
    // Rebuilt GX-880 default, matching the user's original screenshot:
    // WGTS 91.9 tuned, tape counter at 0080.
    private int counterSeconds = 80;
    private boolean counterRunning = false;
    private String currentState = "STOPPED";
    private int currentIndex = 15;

    private final Runnable counterRunnable = new Runnable() {
        @Override public void run() {
            if (counterRunning) {
                counterSeconds++;
                tapeCounter.setText(String.format("%04d", counterSeconds % 10000));
                counterHandler.postDelayed(this, 1000);
            }
        }
    };

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            if (RadioService.BROADCAST_STATE.equals(intent.getAction())) {
                currentState = intent.getStringExtra(RadioService.EXTRA_STATE);
                currentIndex = intent.getIntExtra(RadioService.EXTRA_STATION_INDEX, currentIndex);
                String mode = intent.getStringExtra(RadioService.EXTRA_MODE);
                if (mode != null) musicModeLocal = "music".equals(mode);
                if (musicModeLocal) musicIndexLocal = intent.getIntExtra(RadioService.EXTRA_MUSIC_INDEX, musicIndexLocal);
                refreshUi();
            }
        }
    };

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            service = ((RadioService.LocalBinder) b).getService();
            bound = true;
            currentIndex = service.getCurrentIndex();
            currentState = service.getState();
            boolean svcMusic = service.isMusicMode();
            if (!svcMusic && !musicUris.isEmpty()
                    && getSharedPreferences("muse_prefs", 0).getBoolean("last_mode_music", false)) {
                musicModeLocal = true;
            } else {
                musicModeLocal = svcMusic;
            }
            musicIndexLocal = service.getMusicIndex();
            if (service.getMusicCount() > 0) {
                musicUris = service.getMusicUris();
                musicNames = service.getMusicNames();
                if (musicAdapter != null) {
                    musicAdapter.clear();
                    musicAdapter.addAll(musicNames);
                    musicAdapter.notifyDataSetChanged();
                }
            }
            refreshUi();
        }
        @Override public void onServiceDisconnected(ComponentName n) {
            service = null; bound = false;
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Galaxy S24 punch-hole camera: never draw into the cutout area,
        // and let the window inset the content away from it, the status
        // bar and the navigation bar, so the page fits the screen.
        try {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
                getWindow().setAttributes(lp);
            }
        } catch (Exception ignored) {}
        setContentView(R.layout.activity_main);
        try {
            final android.widget.ScrollView sv = findViewById(R.id.main_scroll);
            if (sv != null && android.os.Build.VERSION.SDK_INT >= 28) {
                sv.setOnApplyWindowInsetsListener(new android.view.View.OnApplyWindowInsetsListener() {
                    @Override public android.view.WindowInsets onApplyWindowInsets(android.view.View v, android.view.WindowInsets insets) {
                        try {
                            android.view.DisplayCutout cut = insets.getDisplayCutout();
                            int top = insets.getSystemWindowInsetTop();
                            int bottom = insets.getSystemWindowInsetBottom();
                            int left = insets.getSystemWindowInsetLeft();
                            int right = insets.getSystemWindowInsetRight();
                            if (cut != null) {
                                top = Math.max(top, cut.getSafeInsetTop());
                                bottom = Math.max(bottom, cut.getSafeInsetBottom());
                                left = Math.max(left, cut.getSafeInsetLeft());
                                right = Math.max(right, cut.getSafeInsetRight());
                            }
                            v.setPadding(left, top, right, bottom);
                        } catch (Exception ignored) {}
                        return insets;
                    }
                });
            }
        } catch (Exception ignored) {}

        stationName = findViewById(R.id.station_name);
        stationDesc = findViewById(R.id.station_desc);
        statusText = findViewById(R.id.status_text);
        tapeCounter = findViewById(R.id.tape_counter);
        reelLeft = findViewById(R.id.reel_left);
        reelRight = findViewById(R.id.reel_right);
        btnPlay = findViewById(R.id.btn_play);
        btnPause = findViewById(R.id.btn_pause);
        btnPrev = findViewById(R.id.btn_prev);
        btnStop = findViewById(R.id.btn_stop);
        btnNext = findViewById(R.id.btn_next);
        btnRew = findViewById(R.id.btn_rew);
        btnFf = findViewById(R.id.btn_ff);
        btnEject = findViewById(R.id.btn_eject);
        volumeSeek = findViewById(R.id.volume_seek);
        stationListContainer = findViewById(R.id.station_list_container);
        musicList = findViewById(R.id.music_list);
        musicTrack = findViewById(R.id.music_track);
        btnPickMusic = findViewById(R.id.btn_pick_music);
        btnShuffle = findViewById(R.id.btn_shuffle);
        btnTabTapes = findViewById(R.id.btn_tab_tapes);
        btnTabRadio = findViewById(R.id.btn_tab_radio);
        btnRec = findViewById(R.id.btn_rec);
        btnFm = findViewById(R.id.btn_fm);
        btnAm = findViewById(R.id.btn_am);
        btnCounterReset = findViewById(R.id.btn_counter_reset);
        btnStations = findViewById(R.id.btn_stations);
        btnBluetooth = findViewById(R.id.btn_bluetooth);
        btnPreset1 = findViewById(R.id.btn_preset1);
        btnPreset2 = findViewById(R.id.btn_preset2);
        btnPreset3 = findViewById(R.id.btn_preset3);
        btnPreset4 = findViewById(R.id.btn_preset4);
        btnPreset5 = findViewById(R.id.btn_preset5);
        btnPreset6 = findViewById(R.id.btn_preset6);
        musicSection = findViewById(R.id.music_section);
        stationSection = findViewById(R.id.station_section);
        tunerFreq = findViewById(R.id.tuner_freq);
        tunerStation = findViewById(R.id.tuner_station);
        tunerDesc = findViewById(R.id.tuner_desc);
        tunerBand = findViewById(R.id.tuner_band);

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            volumeSeek.setMax(max);
            volumeSeek.setProgress(cur);
            volumeSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                    if (fromUser && audioManager != null) {
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0);
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar s) {}
                @Override public void onStopTrackingTouch(SeekBar s) {}
            });
        }

        // Cassette-button click sound (GX-880 style): short click.wav via SoundPool.
        try {
            AudioAttributes clickAttrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            clickPool = new SoundPool.Builder().setMaxStreams(4).setAudioAttributes(clickAttrs).build();
            clickPool.setOnLoadCompleteListener(new SoundPool.OnLoadCompleteListener() {
                @Override public void onLoadComplete(SoundPool sp, int sampleId, int status) {
                    if (status == 0) clickLoaded = true;
                }
            });
            clickSoundId = clickPool.load(this, R.raw.click, 1);
        } catch (Exception ignored) {}

        String[] names = new String[Stations.ALL.length];
        for (int i = 0; i < Stations.ALL.length; i++) names[i] = Stations.ALL[i].toString();
        // Station list as plain clickable rows, NOT a ListView inside the
        // outer ScrollView. A ListView nested in a ScrollView steals /
        // swallows taps and scrolling on Android 17, which is why the
        // entire station list did nothing when tapped. Plain rows in a
        // LinearLayout let the outer ScrollView scroll the page and let
        // every tap reach its click listener directly.
        // High-contrast rows: white bold, FEBC gold bold at the bottom.
        if (stationListContainer != null) {
            stationListContainer.removeAllViews();
            stationRows.clear();
            for (int i = 0; i < names.length; i++) {
                final int pos = i;
                TextView row = new TextView(this);
                row.setText(names[i]);
                row.setTextSize(18);
                row.setTypeface(null, android.graphics.Typeface.BOLD);
                row.setTextColor(names[i].contains("FEBC") ? getResources().getColor(R.color.gx_gold) : 0xFFFFFFFF);
                row.setPadding(28, 28, 28, 28);
                row.setClickable(true);
                row.setFocusable(true);
                row.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        playClick();
                        startServiceForIndex(pos);
                    }
                });
                stationRows.add(row);
                stationListContainer.addView(row);
            }
        }

        musicAdapter = new ArrayAdapter<>(this, R.layout.station_list_item, new ArrayList<String>());
        // Restore picked music from last time, so the list is still there
        // after reopening the app (URIs were saved with persistable permission).
        try {
            String savedUris = getSharedPreferences("muse_prefs", 0).getString("music_uris", "");
            String savedNames = getSharedPreferences("muse_prefs", 0).getString("music_names", "");
            if (savedUris != null && !savedUris.isEmpty()) {
                String[] su = savedUris.split("\u001f", -1);
                String[] sn = savedNames == null ? new String[0] : savedNames.split("\u001f", -1);
                for (int i = 0; i < su.length; i++) {
                    if (su[i].isEmpty()) continue;
                    musicUris.add(su[i]);
                    musicNames.add(i < sn.length && !sn[i].isEmpty() ? sn[i] : su[i]);
                }
                musicAdapter.addAll(musicNames);
                musicAdapter.notifyDataSetChanged();
            }
        } catch (Exception ignored) {}
        musicList.setAdapter(musicAdapter);
        // Music ListView is also nested in the ScrollView, so stop the
        // parent ScrollView from intercepting its touches.
        musicList.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, android.view.MotionEvent event) {
                if (event.getAction() == android.view.MotionEvent.ACTION_DOWN) {
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                }
                return false;
            }
        });
        musicList.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        musicList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                startServiceForMusic(pos);
            }
        });
        btnPickMusic.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { playClick(); pickMusic(); }
        });
        if (btnShuffle != null) btnShuffle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                playClick();
                if (musicUris.isEmpty()) { Toast.makeText(MainActivity.this, "Pick music first", Toast.LENGTH_SHORT).show(); return; }
                java.util.ArrayList<String> su = new ArrayList<>(musicUris);
                java.util.ArrayList<String> sn = new ArrayList<>(musicNames);
                java.util.Collections.shuffle(su);
                // shuffle names in the same order by pairing
                java.util.ArrayList<String> paired = new ArrayList<>();
                for (int i = 0; i < musicUris.size(); i++) paired.add(musicUris.get(i) + "\u001f" + (i < musicNames.size() ? musicNames.get(i) : ""));
                java.util.Collections.shuffle(paired);
                musicUris.clear(); musicNames.clear();
                for (String pr : paired) {
                    int sep = pr.indexOf("\u001f");
                    musicUris.add(sep >= 0 ? pr.substring(0, sep) : pr);
                    musicNames.add(sep >= 0 ? pr.substring(sep + 1) : "");
                }
                if (musicAdapter != null) { musicAdapter.clear(); musicAdapter.addAll(musicNames); musicAdapter.notifyDataSetChanged(); }
                try { getSharedPreferences("muse_prefs", 0).edit().putString("music_uris", android.text.TextUtils.join("\u001f", musicUris)).putString("music_names", android.text.TextUtils.join("\u001f", musicNames)).apply(); } catch (Exception ignored) {}
                startServiceForMusic(0);
                Toast.makeText(MainActivity.this, "Shuffle playing all " + musicUris.size() + " songs", Toast.LENGTH_SHORT).show();
            }
        });
        // Settings button is now in the page footer (scrolls with content,
        // so it never overlaps the tuner or hides behind the nav bar on the S24).
        findViewById(R.id.btn_settings).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(MainActivity.this, SettingsActivity.class)); }
        });
        // Cassette-deck controls: separate Play / Pause, plus Rewind / FF / Eject.
        // Rewind = previous station/track, Fast-Forward = next station/track,
        // Eject = stop playback (same as Stop), preserving existing service behaviour.
        btnPlay.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                playClick();
                if (bound && service != null) service.toggle();
                else { Intent i = new Intent(MainActivity.this, RadioService.class); i.setAction(RadioService.ACTION_TOGGLE); startService(i); }
            }
        });
        btnPause.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                playClick();
                if (bound && service != null) service.pausePlayback();
            }
        });
        btnRew.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                playClick();
                Intent i = new Intent(MainActivity.this, RadioService.class); i.setAction(RadioService.ACTION_PREV); startService(i);
                currentState = "BUFFERING"; refreshUi();
            }
        });
        btnFf.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                playClick();
                Intent i = new Intent(MainActivity.this, RadioService.class); i.setAction(RadioService.ACTION_NEXT); startService(i);
                currentState = "BUFFERING"; refreshUi();
            }
        });
        btnEject.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                playClick();
                Intent i = new Intent(MainActivity.this, RadioService.class);
                i.setAction(RadioService.ACTION_STOP);
                startService(i);
            }
        });
        btnPrev.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                playClick();
                Intent i = new Intent(MainActivity.this, RadioService.class); i.setAction(RadioService.ACTION_PREV); startService(i);
                currentState = "BUFFERING"; refreshUi();
            }
        });
        btnNext.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                playClick();
                Intent i = new Intent(MainActivity.this, RadioService.class); i.setAction(RadioService.ACTION_NEXT); startService(i);
                currentState = "BUFFERING"; refreshUi();
            }
        });
        btnStop.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                playClick();
                Intent i = new Intent(MainActivity.this, RadioService.class);
                i.setAction(RadioService.ACTION_STOP);
                startService(i);
            }
        });

        // GX-880 controls: TAPES / RADIO tabs, FM / AM band indicator,
        // REC (playback-only), counter reset, STATIONS, and P1-P6 presets.
        // Presets match the GX-880 screenshot: P1 WGTS 91.9, P2 BRIGHT-FM 95.1,
        // P3-P6 are filled with real stations (not EMPTY) for usability.
        // WGTS is Stations index 15 (appended, original 15 order unchanged).
        if (btnTabTapes != null) btnTabTapes.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                playClick();
                if (musicSection != null) musicSection.setVisibility(View.VISIBLE);
                if (stationSection != null) stationSection.setVisibility(View.GONE);
            }
        });
        if (btnTabRadio != null) btnTabRadio.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                playClick();
                if (musicSection != null) musicSection.setVisibility(View.GONE);
                if (stationSection != null) stationSection.setVisibility(View.VISIBLE);
            }
        });
        if (btnStations != null) btnStations.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                playClick();
                if (musicSection != null) musicSection.setVisibility(View.GONE);
                if (stationSection != null) stationSection.setVisibility(View.VISIBLE);
            }
        });
        // Bluetooth for the app: ask for BLUETOOTH_CONNECT, then open the
        // phone's Bluetooth screen where headphones are tapped to connect.
        // Android does not let an app silently force-connect headphones —
        // the user taps the headphones there, then returns here to play,
        // and audio routes to the headphones automatically.
        if (btnBluetooth != null) btnBluetooth.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                playClick();
                if (!hasBluetoothPermission()) {
                    requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, REQ_BLUETOOTH_PERMISSION);
                } else {
                    openBluetoothSettings();
                }
            }
        });
        if (btnFm != null) btnFm.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                playClick();
                if (tunerBand != null) tunerBand.setText("\u25CF STEREO");
            }
        });
        if (btnAm != null) btnAm.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                playClick();
                if (tunerBand != null) tunerBand.setText("AM");
            }
        });
        if (btnRec != null) btnRec.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                playClick();
                Toast.makeText(MainActivity.this, "Recording not supported - playback only", Toast.LENGTH_SHORT).show();
            }
        });
        if (btnCounterReset != null) btnCounterReset.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                playClick();
                counterSeconds = 0;
                if (tapeCounter != null) tapeCounter.setText("0000");
            }
        });
        final int[] presetIndices = new int[]{15, 0, 1, 2, 4, 6};
        Button[] presets = new Button[]{btnPreset1, btnPreset2, btnPreset3, btnPreset4, btnPreset5, btnPreset6};
        for (int pi = 0; pi < presets.length; pi++) {
            final int pidx = presetIndices[pi];
            if (presets[pi] != null) presets[pi].setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    playClick();
                    startServiceForIndex(pidx);
                }
            });
        }

        // All runtime permissions in ONE request. 2.0 called
        // requestPermissions three times back-to-back in onCreate
        // (notifications, then music, then Bluetooth) — Android only
        // shows the first dialog and silently drops the later ones,
        // which is why Bluetooth never asked. One combined request
        // shows notifications + music + Bluetooth together.
        {
            java.util.ArrayList<String> missing = new java.util.ArrayList<>();
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                            != PackageManager.PERMISSION_GRANTED) {
                missing.add(Manifest.permission.POST_NOTIFICATIONS);
            }
            if (!hasMusicPermission()) {
                missing.add(musicPermissionName());
            }
            if (Build.VERSION.SDK_INT >= 31 && !hasBluetoothPermission()) {
                missing.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
            if (!missing.isEmpty()) {
                requestPermissions(missing.toArray(new String[0]), 1004);
            }
        }

        // Start + bind service so Activity can control it, but service lives on
        // independently as a foreground service for background play.
        Intent svc = new Intent(this, RadioService.class);
        startService(svc);
        bindService(svc, conn, Context.BIND_AUTO_CREATE);

        refreshUi();
    }

    private void playClick() {
        // Brief tactile cassette-button click, like the GX-880.
        // SoundPool with USAGE_ASSISTANCE_SONIFICATION mixes as a UI sound and
        // does not stop, pause, or duck the foreground-service MediaPlayer
        // (STREAM_MUSIC radio / local music). Falls back to the system
        // FX_KEY_CLICK if the click sample has not finished loading yet.
        try {
            if (clickPool != null && clickLoaded && clickSoundId != 0) {
                clickPool.play(clickSoundId, 0.6f, 0.6f, 1, 0, 1.0f);
            } else if (audioManager != null) {
                audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK);
            }
        } catch (Exception ignored) {}
    }

    private void pickMusic() {
        if (!hasMusicPermission()) {
            requestMusicPermission();
            return;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("audio/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(Intent.createChooser(i, "Pick music files"), REQ_PICK_MUSIC);
        } catch (Exception e) {
            Toast.makeText(this, "No file picker available on this device", Toast.LENGTH_SHORT).show();
        }
    }

    private String displayNameFor(Uri uri) {
        try {
            Cursor c = getContentResolver().query(uri, null, null, null, null);
            if (c != null) {
                try {
                    int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0 && c.moveToFirst()) {
                        String n = c.getString(idx);
                        if (n != null && !n.isEmpty()) return n;
                    }
                } finally { c.close(); }
            }
        } catch (Exception ignored) {}
        String s = uri.getLastPathSegment();
        return s != null ? s : uri.toString();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_MUSIC && resultCode == RESULT_OK && data != null) {
            ArrayList<String> uris = new ArrayList<>();
            ArrayList<String> names = new ArrayList<>();
            try {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    for (int k = 0; k < n; k++) {
                        Uri u = data.getClipData().getItemAt(k).getUri();
                        if (u == null) continue;
                        try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
                        uris.add(u.toString());
                        names.add(displayNameFor(u));
                    }
                } else if (data.getData() != null) {
                    Uri u = data.getData();
                    try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
                    uris.add(u.toString());
                    names.add(displayNameFor(u));
                }
            } catch (Exception e) {
                Toast.makeText(this, "Could not read picked files", Toast.LENGTH_SHORT).show();
                return;
            }
            if (uris.isEmpty()) return;
            try {
                StringBuilder ju = new StringBuilder(); StringBuilder jn = new StringBuilder();
                for (int i = 0; i < uris.size(); i++) {
                    if (i > 0) { ju.append("\u001f"); jn.append("\u001f"); }
                    ju.append(uris.get(i)); jn.append(names.get(i));
                }
                getSharedPreferences("muse_prefs", 0).edit().putString("music_uris", ju.toString()).putString("music_names", jn.toString()).apply();
            } catch (Exception ignored) {}
            musicUris = uris;
            musicNames = names;
            musicAdapter.clear();
            musicAdapter.addAll(names);
            musicAdapter.notifyDataSetChanged();
            startServiceForMusic(0);
        }
    }


    private boolean isMusicSkip() {
        if (!musicUris.isEmpty() && musicModeLocal) return true;
        if (!musicUris.isEmpty()) {
            try { if (getSharedPreferences("muse_prefs", 0).getBoolean("last_mode_music", false)) return true; } catch (Exception ignored) {}
            try { if (bound && service != null && service.isMusicMode()) return true; } catch (Exception ignored) {}
        }
        return false;
    }

    private void startServiceForMusic(int idx) {
        if (musicUris.isEmpty()) {
            Toast.makeText(this, "Pick music first", Toast.LENGTH_SHORT).show();
            return;
        }
        if (idx < 0) idx = musicUris.size() - 1;
        if (idx >= musicUris.size()) idx = 0;
        musicIndexLocal = idx;
        musicModeLocal = true;
        currentState = "BUFFERING"; refreshUi();
        try { getSharedPreferences("muse_prefs", 0).edit().putBoolean("last_mode_music", true).apply(); } catch (Exception ignored) {}
        Intent i = new Intent(this, RadioService.class);
        i.setAction(RadioService.ACTION_PLAY_MUSIC_LIST);
        i.putStringArrayListExtra(RadioService.EXTRA_MUSIC_URIS, musicUris);
        i.putStringArrayListExtra(RadioService.EXTRA_MUSIC_NAMES, musicNames);
        i.putExtra(RadioService.EXTRA_INDEX, idx);
        startService(i);
        refreshUi();
    }

    private void startServiceForIndex(int idx) {
        if (idx < 0) idx = Stations.ALL.length - 1;
        if (idx >= Stations.ALL.length) idx = 0;
        currentIndex = idx;
        musicModeLocal = false;
        try { getSharedPreferences("muse_prefs", 0).edit().putBoolean("last_mode_music", false).apply(); } catch (Exception ignored) {};
        // Respond immediately in the UI, don't wait for the service
        // broadcast — otherwise a slow stream looks like "nothing happens".
        currentState = "BUFFERING";
        refreshUi();
        Intent i = new Intent(this, RadioService.class);
        i.setAction(RadioService.ACTION_PLAY_INDEX);
        i.putExtra(RadioService.EXTRA_INDEX, idx);
        startService(i);
        refreshUi();
    }

    private void updateTuner() {
        try {
            if (musicModeLocal && !musicNames.isEmpty()) {
                int mi = musicIndexLocal;
                if (mi < 0 || mi >= musicNames.size()) mi = 0;
                if (tunerFreq != null) tunerFreq.setText("LOCAL");
                if (tunerStation != null) tunerStation.setText(musicNames.get(mi));
                if (tunerDesc != null) tunerDesc.setText("Your Music - Local file");
                return;
            }
            if (currentIndex < 0 || currentIndex >= Stations.ALL.length) currentIndex = 0;
            Stations.Station st = Stations.ALL[currentIndex];
            if (tunerStation != null) tunerStation.setText(st.name);
            if (tunerDesc != null) tunerDesc.setText(st.desc);
            if (tunerFreq != null) {
                if (st.name.contains("95.1")) tunerFreq.setText("95.1 MHz");
                else if (st.name.contains("91.9")) tunerFreq.setText("91.9 MHz");
                else if (st.name.contains("90.1")) tunerFreq.setText("90.1 MHz");
                else tunerFreq.setText("FM");
            }
        } catch (Exception ignored) {}
    }

    private void refreshUi() {
        if (musicModeLocal && !musicNames.isEmpty()) {
            int mi = musicIndexLocal;
            if (mi < 0 || mi >= musicNames.size()) mi = 0;
            stationName.setText(musicNames.get(mi));
            stationDesc.setText("Your Music - Local file");
            musicTrack.setText("Now: " + musicNames.get(mi));
            musicList.setItemChecked(mi, true);
        } else if (musicModeLocal && musicNames.isEmpty() && musicTrack != null) {
            musicTrack.setText("No music picked yet - tap Pick Music to choose audio files from your device.");
        } else if (musicTrack != null && !musicNames.isEmpty()) {
            musicTrack.setText(musicNames.size() + " track(s) picked - tap one to play it.");
        }
        if (currentIndex < 0 || currentIndex >= Stations.ALL.length) currentIndex = 0;
        if (!musicModeLocal) {
            Stations.Station st = Stations.ALL[currentIndex];
            stationName.setText(st.name);
            stationDesc.setText(st.desc);
        }
        for (int ri = 0; ri < stationRows.size(); ri++) {
            stationRows.get(ri).setBackgroundColor(
                    (!musicModeLocal && ri == currentIndex) ? 0xFF3A2F00 : 0x00000000);
        }

        boolean playing = "PLAYING".equals(currentState);
        boolean buffering = "BUFFERING".equals(currentState);
        if (playing) {
            statusText.setText("▶ Playing");
            startReels();
            startCounter();
        } else if (buffering) {
            statusText.setText("Buffering…");
            startReels();
            startCounter();
        } else if ("PAUSED".equals(currentState)) {
            statusText.setText("Paused");
            stopReels();
            stopCounter();
        } else if ("ERROR".equals(currentState)) {
            statusText.setText("Stream error — try another station");
            stopReels();
            stopCounter();
            // Toast only once per error transition is hard to track; keep it silent here
            // to avoid spam, the status text already explains it.
        } else {
            statusText.setText("Stopped");
            stopReels();
            stopCounter();
        }
        updateTuner();
    }

    private void startReels() {
        if (animLeft == null) {
            animLeft = ObjectAnimator.ofFloat(reelLeft, "rotation", 0f, 360f);
            animLeft.setDuration(2000);
            animLeft.setRepeatCount(ValueAnimator.INFINITE);
            animLeft.setInterpolator(new LinearInterpolator());
            animRight = ObjectAnimator.ofFloat(reelRight, "rotation", 0f, 360f);
            animRight.setDuration(2000);
            animRight.setRepeatCount(ValueAnimator.INFINITE);
            animRight.setInterpolator(new LinearInterpolator());
        }
        if (!animLeft.isRunning()) animLeft.start();
        if (!animRight.isRunning()) animRight.start();
    }

    private void stopReels() {
        if (animLeft != null) animLeft.cancel();
        if (animRight != null) animRight.cancel();
    }

    private void startCounter() {
        if (!counterRunning) {
            counterRunning = true;
            counterHandler.post(counterRunnable);
        }
    }

    private void stopCounter() {
        counterRunning = false;
        counterHandler.removeCallbacks(counterRunnable);
    }

    @Override protected void onResume() {
        super.onResume();
        // Android 14+ (API 34+) throws SecurityException if a dynamic
        // receiver is registered without an export flag. This is why 1.6
        // (target 37) crashed on launch while 1.5 (target 33) did not:
        // the old 2-arg call was allowed at target 33, not at 37.
        // Our state broadcast is internal to this app, so NOT_EXPORTED.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, new IntentFilter(RadioService.BROADCAST_STATE), Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, new IntentFilter(RadioService.BROADCAST_STATE));
        }
        if (bound && service != null) {
            currentIndex = service.getCurrentIndex();
            currentState = service.getState();
            boolean svcMusic2 = service.isMusicMode();
            if (!svcMusic2 && !musicUris.isEmpty()
                    && getSharedPreferences("muse_prefs", 0).getBoolean("last_mode_music", false)) {
                musicModeLocal = true;
            } else {
                musicModeLocal = svcMusic2;
            }
            musicIndexLocal = service.getMusicIndex();
            if (service.getMusicCount() > 0) {
                musicUris = service.getMusicUris();
                musicNames = service.getMusicNames();
                if (musicAdapter != null) {
                    musicAdapter.clear();
                    musicAdapter.addAll(musicNames);
                    musicAdapter.notifyDataSetChanged();
                }
            }
            refreshUi();
        }
    }

    @Override protected void onPause() {
        // Intentionally do NOT stop the service here — background play continues.
        try { unregisterReceiver(stateReceiver); } catch (Exception ignored) {}
        super.onPause();
    }

    @Override protected void onDestroy() {
        stopCounter();
        if (clickPool != null) {
            try { clickPool.release(); } catch (Exception ignored) {}
            clickPool = null;
        }
        if (bound) {
            try { unbindService(conn); } catch (Exception ignored) {}
            bound = false;
        }
        super.onDestroy();
    }
}
