package com.muse.retrocassette;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.net.Uri;
import java.util.ArrayList;
import android.util.Log;

import java.io.IOException;

public class RadioService extends Service
        implements MediaPlayer.OnPreparedListener, MediaPlayer.OnErrorListener,
        MediaPlayer.OnCompletionListener {

    public static final String ACTION_PLAY = "com.muse.retrocassette.PLAY";
    public static final String ACTION_PAUSE = "com.muse.retrocassette.PAUSE";
    public static final String ACTION_TOGGLE = "com.muse.retrocassette.TOGGLE";
    public static final String ACTION_STOP = "com.muse.retrocassette.STOP";
    public static final String ACTION_NEXT = "com.muse.retrocassette.NEXT";
    public static final String ACTION_PREV = "com.muse.retrocassette.PREV";
    public static final String ACTION_PLAY_INDEX = "com.muse.retrocassette.PLAY_INDEX";
    public static final String EXTRA_INDEX = "index";
    public static final String ACTION_PLAY_MUSIC_LIST = "com.muse.retrocassette.PLAY_MUSIC_LIST";
    public static final String EXTRA_MUSIC_URIS = "music_uris";
    public static final String EXTRA_MUSIC_NAMES = "music_names";
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_MUSIC_INDEX = "music_index";
    public static final String EXTRA_MUSIC_NAME = "music_name";

    public static final String BROADCAST_STATE = "com.muse.retrocassette.STATE";
    public static final String EXTRA_STATE = "state"; // PLAYING, PAUSED, BUFFERING, STOPPED, ERROR
    public static final String EXTRA_STATION_INDEX = "station_index";

    private static final String CHANNEL_ID = "radio_playback";
    private static final int NOTIF_ID = 1;
    private static final String TAG = "RadioService";

    private MediaPlayer player;
    private MediaSession mediaSession;
    // GX-880 rebuild default: WGTS 91.9 (Stations index 15)
    private int currentIndex = 15;
    private boolean musicMode = false;
    private ArrayList<String> musicUris = new ArrayList<>();
    private ArrayList<String> musicNames = new ArrayList<>();
    private int musicIndex = 0;
    private String state = "STOPPED";
    private android.os.ParcelFileDescriptor musicPfd = null;
    private boolean isPrepared = false;
    private int musicErrorRetries = 0;
    private boolean skipCacheOnce = false;
    private int preparingStation = -1;
    private int preparingMusic = -1;
    private boolean preparingIsMusic = false;

    private final IBinder binder = new LocalBinder();
    private BroadcastReceiver btPauseReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            try {
                boolean on = getSharedPreferences("muse_prefs", 0).getBoolean("pause_on_bt_disconnect", false);
                if (on && "PLAYING".equals(state)) pausePlayback();
            } catch (Exception ignored) {}
        }
    };
    public class LocalBinder extends Binder {
        public RadioService getService() { return RadioService.this; }
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public void onCreate() {
        super.onCreate();
        try {
            android.content.SharedPreferences p = getSharedPreferences("muse_prefs", 0);
            String su = p.getString("music_uris", "");
            String sn = p.getString("music_names", "");
            if (su != null && !su.isEmpty()) {
                String[] uu = su.split("\u001f", -1);
                String[] nn = sn == null ? new String[0] : sn.split("\u001f", -1);
                for (int i = 0; i < uu.length; i++) {
                    if (uu[i].isEmpty()) continue;
                    musicUris.add(uu[i]);
                    musicNames.add(i < nn.length && !nn[i].isEmpty() ? nn[i] : uu[i]);
                }
                musicMode = p.getBoolean("last_mode_music", false) && !musicUris.isEmpty();
            }
        } catch (Exception ignored) {}
        createChannel();
        mediaSession = new MediaSession(this, "RetroCassette");
        mediaSession.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { if (musicMode && !musicUris.isEmpty()) playMusicCurrent(); else playCurrent(); }
            @Override public void onPause() { pausePlayback(); }
            @Override public void onStop() { stopPlayback(); }
            @Override public void onSkipToNext() { next(); }
            @Override public void onSkipToPrevious() { prev(); }
        });
        mediaSession.setActive(true);
        try {
            IntentFilter f = new IntentFilter();
            f.addAction(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(btPauseReceiver, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(btPauseReceiver, f);
            }
        } catch (Exception ignored) {}
    }

    private void startForegroundCompat() {
        // Android 17 / target 37: startForeground must declare the service
        // type explicitly, matching the manifest mediaPlayback type, or
        // playback can fail to start / be killed.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, buildNotification(),
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForegroundCompat();
            }
        } catch (Exception e) {
            Log.w(TAG, "startForeground failed", e);
            try { startForeground(NOTIF_ID, buildNotification()); } catch (Exception ignored) {}
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                    "Radio playback", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Retro Cassette Radio playback controls");
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private PendingIntent actionIntent(String action) {
        Intent i = new Intent(this, RadioService.class);
        i.setAction(action);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getService(this, action.hashCode(), i, flags);
    }

    private PendingIntent playIndexIntent(int idx) {
        Intent i = new Intent(this, RadioService.class);
        i.setAction(ACTION_PLAY_INDEX);
        i.putExtra(EXTRA_INDEX, idx);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getService(this, 1000 + idx, i, flags);
    }

    public String getCurrentTitle() {
        if (musicMode && !musicUris.isEmpty()) return musicNames.get(musicIndex);
        return Stations.ALL[currentIndex].name;
    }
    public String getCurrentDesc() {
        if (musicMode && !musicUris.isEmpty()) return "Your Music - Local file";
        return Stations.ALL[currentIndex].desc;
    }
    private Notification buildNotification() {
        String title = getCurrentTitle();
        String desc = getCurrentDesc();
        boolean playing = "PLAYING".equals(state) || "BUFFERING".equals(state);
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open, flags);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle(title + " — Retro Cassette Radio")
         .setContentText(playing ? "Playing • " + desc : desc)
         .setSmallIcon(android.R.drawable.ic_media_play)
         .setContentIntent(openPi)
         .setOngoing(playing)
         .setVisibility(Notification.VISIBILITY_PUBLIC);
        // Media style with session token (API 21+)
        try {
            Notification.MediaStyle style = new Notification.MediaStyle();
            style.setMediaSession(mediaSession.getSessionToken());
            b.setStyle(style);
        } catch (Exception e) { Log.w(TAG, "media style failed", e); }
        b.addAction(new Notification.Action(android.R.drawable.ic_media_previous, "Prev", actionIntent(ACTION_PREV)));
        if (playing) {
            b.addAction(new Notification.Action(android.R.drawable.ic_media_pause, "Pause", actionIntent(ACTION_PAUSE)));
        } else {
            b.addAction(new Notification.Action(android.R.drawable.ic_media_play, "Play", actionIntent(ACTION_PLAY)));
        }
        b.addAction(new Notification.Action(android.R.drawable.ic_media_next, "Next", actionIntent(ACTION_NEXT)));
        b.addAction(new Notification.Action(android.R.drawable.ic_media_play, "Stop", actionIntent(ACTION_STOP)));
        return b.build();
    }

    private void updateNotification() {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIF_ID, buildNotification());
        } catch (Exception e) { Log.w(TAG, "notify failed", e); }
    }

    private void setState(String s) {
        state = s;
        updatePlaybackState();
        updateNotification();
        Intent b = new Intent(BROADCAST_STATE);
        b.putExtra(EXTRA_STATE, s);
        b.putExtra(EXTRA_STATION_INDEX, currentIndex);
        b.putExtra(EXTRA_MODE, musicMode ? "music" : "radio");
        b.putExtra(EXTRA_MUSIC_INDEX, musicIndex);
        if (musicMode && !musicUris.isEmpty()) b.putExtra(EXTRA_MUSIC_NAME, musicNames.get(musicIndex));
        sendBroadcast(b);
    }

    private void updatePlaybackState() {
        if (mediaSession == null) return;
        int ps = PlaybackState.STATE_STOPPED;
        if ("PLAYING".equals(state)) ps = PlaybackState.STATE_PLAYING;
        else if ("PAUSED".equals(state)) ps = PlaybackState.STATE_PAUSED;
        else if ("BUFFERING".equals(state)) ps = PlaybackState.STATE_BUFFERING;
        PlaybackState.Builder builder = new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE |
                        PlaybackState.ACTION_STOP | PlaybackState.ACTION_SKIP_TO_NEXT |
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                .setState(ps, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1.0f);
        mediaSession.setPlaybackState(builder.build());
        try {
            android.media.MediaMetadata.Builder mb = new android.media.MediaMetadata.Builder();
            mb.putString(android.media.MediaMetadata.METADATA_KEY_TITLE, getCurrentTitle());
            mb.putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, getCurrentDesc());
            mediaSession.setMetadata(mb.build());
        } catch (Exception ignored) {}
    }

    private void ensurePlayer() {
        if (player == null) {
            player = new MediaPlayer();
            player.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            player.setOnPreparedListener(this);
            player.setOnErrorListener(this);
            player.setOnCompletionListener(this);
        }
    }

    public synchronized void playIndex(int idx) {
        if (idx < 0) idx = Stations.ALL.length - 1;
        if (idx >= Stations.ALL.length) idx = 0;
        currentIndex = idx;
        playCurrent();
    }

    public synchronized void playCurrent() {
        musicMode = false;
        preparingStation = currentIndex;
        preparingIsMusic = false;
        ensurePlayer();
        try {
            player.reset();
            isPrepared = false;
            setState("BUFFERING");
            // Foreground first, so playback survives background / screen off.
            startForegroundCompat();
            player.setDataSource(Stations.ALL[currentIndex].url);
            player.prepareAsync();
        } catch (IOException e) {
            Log.e(TAG, "play failed", e);
            setState("ERROR");
        }
    }

    public synchronized void setMusicPlaylist(ArrayList<String> uris, ArrayList<String> names, int start) {
        musicUris = new ArrayList<>(uris);
        musicNames = new ArrayList<>(names);
        if (start < 0) start = 0;
        if (start >= musicUris.size()) start = 0;
        musicIndex = start;
        playMusicCurrent();
    }
    public synchronized void playMusicIndex(int idx) {
        if (musicUris.isEmpty()) return;
        if (idx < 0) idx = musicUris.size() - 1;
        if (idx >= musicUris.size()) idx = 0;
        musicIndex = idx;
        playMusicCurrent();
    }
    private String musicCacheExt() {
        try {
            String nm = musicNames.get(musicIndex);
            int dot = nm == null ? -1 : nm.lastIndexOf('.');
            if (dot >= 0 && nm.length() - dot <= 5) return nm.substring(dot);
        } catch (Exception ignored) {}
        return ".mp3";
    }
    public synchronized void playMusicCurrent() {
        if (musicUris.isEmpty()) return;
        musicMode = true;
        preparingMusic = musicIndex;
        preparingIsMusic = true;
        ensurePlayer();
        try {
            player.reset();
            isPrepared = false;
            setState("BUFFERING");
            startForegroundCompat();
            Uri uri = Uri.parse(musicUris.get(musicIndex));
            boolean ok = false;
            // Direct first: starts immediately, no full-file copy delay.
            // The cache-first order in 3.0-3.3 made the first tap wait
            // while the whole song was copied on the main thread, so it
            // looked like the first tap did nothing and only a second
            // tap played - and a second tap / skip while copying could
            // overwrite the cache file the player was preparing from,
            // stopping playback entirely.
            try { player.setDataSource(getApplicationContext(), uri); ok = true; }
            catch (Exception e2) { Log.w(TAG, "setDataSource(Uri) failed", e2); }
            if (!ok) {
                try {
                    player.reset();
                    android.os.ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r");
                    if (pfd == null) throw new java.io.IOException("no file descriptor");
                    player.setDataSource(pfd.getFileDescriptor());
                    try { pfd.close(); } catch (Exception ignored) {}
                    ok = true;
                } catch (Exception e3) { Log.w(TAG, "setDataSource(FD) failed", e3); }
            }
            if (!ok) {
                try {
                    if (skipCacheOnce) { skipCacheOnce = false; throw new java.io.IOException("cache skipped after error"); }
                    player.reset();
                    java.io.InputStream in = getContentResolver().openInputStream(uri);
                    if (in == null) throw new java.io.IOException("no input stream");
                    java.io.File outFile = new java.io.File(getCacheDir(), "music_play" + musicCacheExt());
                    java.io.FileOutputStream out = new java.io.FileOutputStream(outFile);
                    byte[] buf = new byte[8192]; int n;
                    while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
                    out.close(); in.close();
                    if (outFile.length() <= 0) { try { outFile.delete(); } catch (Exception ignored) {} throw new java.io.IOException("empty cache copy"); }
                    player.setDataSource(outFile.getAbsolutePath());
                    ok = true;
                } catch (Exception e1) { Log.w(TAG, "music cache copy failed", e1); }
            }
            if (!ok) {
                // MediaStore-by-name fallback: if this one song's saved
                // link is stale (file moved, permission lost), the other
                // songs still play, but this one fails direct/FD/cache.
                // Finding the same file name in MediaStore rescues it
                // without making the user re-pick the whole list.
                try {
                    String nm = musicIndex < musicNames.size() ? musicNames.get(musicIndex) : null;
                    if (nm != null && !nm.isEmpty()) {
                        android.database.Cursor c = getContentResolver().query(
                            android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            new String[]{android.provider.MediaStore.Audio.Media._ID},
                            android.provider.MediaStore.Audio.Media.DISPLAY_NAME + "=?",
                            new String[]{nm}, null);
                        if (c != null) {
                            if (c.moveToFirst()) {
                                long id = c.getLong(0);
                                android.net.Uri mu = android.content.ContentUris.withAppendedId(
                                    android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
                                player.reset();
                                player.setDataSource(getApplicationContext(), mu);
                                musicUris.set(musicIndex, mu.toString());
                                ok = true;
                            }
                            c.close();
                        }
                    }
                } catch (Exception e4) { Log.w(TAG, "music MediaStore fallback failed", e4); }
            }
            if (!ok) { setState("ERROR"); return; }
            player.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "music play failed", e);
            setState("ERROR");
        }
    }
    public synchronized void nextMusic() {
        if (musicUris.isEmpty()) restoreMusicFromPrefs();
        playMusicIndex(musicIndex + 1);
    }
    private void restoreMusicFromPrefs() {
        try {
            android.content.SharedPreferences p = getSharedPreferences("muse_prefs", 0);
            String su = p.getString("music_uris", "");
            String sn = p.getString("music_names", "");
            if (su == null || su.isEmpty()) return;
            String[] uu = su.split("\u001f", -1);
            String[] nn = sn == null ? new String[0] : sn.split("\u001f", -1);
            musicUris.clear(); musicNames.clear();
            for (int i = 0; i < uu.length; i++) {
                if (uu[i].isEmpty()) continue;
                musicUris.add(uu[i]);
                musicNames.add(i < nn.length && !nn[i].isEmpty() ? nn[i] : uu[i]);
            }
            musicMode = true;
        } catch (Exception ignored) {}
    }
    public synchronized void prevMusic() { if (musicUris.isEmpty()) restoreMusicFromPrefs(); playMusicIndex(musicIndex - 1); }
    public boolean isMusicMode() { return musicMode; }
    public int getMusicIndex() { return musicIndex; }
    public int getMusicCount() { return musicUris.size(); }
    public String getMusicName() {
        if (musicMode && !musicUris.isEmpty()) return musicNames.get(musicIndex);
        return "";
    }
    public ArrayList<String> getMusicUris() { return new ArrayList<>(musicUris); }
    public ArrayList<String> getMusicNames() { return new ArrayList<>(musicNames); }

    public synchronized void pausePlayback() {
        if (player != null && isPrepared && player.isPlaying()) {
            try { player.pause(); } catch (Exception ignored) {}
        } else if (player != null && "BUFFERING".equals(state)) {
            try { player.reset(); } catch (Exception ignored) {}
            isPrepared = false;
        }
        setState("PAUSED");
        // Keep foreground notification so user can resume from notification / lock screen.
        try { startForegroundCompat(); } catch (Exception ignored) {}
    }

    public synchronized void toggle() {
        if ("BUFFERING".equals(state)) return;
        if (!musicMode && !musicUris.isEmpty()) {
            try { if (getSharedPreferences("muse_prefs", 0).getBoolean("last_mode_music", false)) musicMode = true; } catch (Exception ignored) {}
        }
        if ("PLAYING".equals(state)) pausePlayback();
        else if ("PAUSED".equals(state)) {
            if (player != null && isPrepared) {
                try { player.start(); setState("PLAYING"); } catch (Exception e) {
                    if (musicMode) playMusicCurrent(); else playCurrent();
                }
            } else if (musicMode) playMusicCurrent(); else playCurrent();
        } else if (musicMode) playMusicCurrent(); else playCurrent();
    }

    public synchronized void stopPlayback() {
        if (player != null) {
            try { player.reset(); } catch (Exception ignored) {}
            isPrepared = false;
        }
        setState("STOPPED");
        try { stopForeground(true); } catch (Exception ignored) {}
        // Do not stopSelf immediately if bound; Activity stop also calls stopSelf via command.
        // For notification Stop, stop the service.
        // Note: if Activity is bound, stopSelf will be deferred until unbind, which is fine.
    }

    public synchronized void next() { if (musicMode && !musicUris.isEmpty()) nextMusic(); else playIndex(currentIndex + 1); }
    public synchronized void prev() { if (musicMode && !musicUris.isEmpty()) prevMusic(); else playIndex(currentIndex - 1); }

    public int getCurrentIndex() { return currentIndex; }
    public String getState() { return state; }
    public boolean isPlaying() { return "PLAYING".equals(state); }

    @Override public void onPrepared(MediaPlayer mp) {
        if (preparingIsMusic != musicMode) return;
        if (musicMode) { if (preparingMusic != musicIndex) return; }
        else { if (preparingStation != currentIndex) return; }
        musicErrorRetries = 0;
        isPrepared = true;
        try { mp.start(); } catch (Exception e) { Log.e(TAG, "start failed", e); }
        setState("PLAYING");
    }

    @Override public boolean onError(MediaPlayer mp, int what, int extra) {
        Log.e(TAG, "player error what=" + what + " extra=" + extra);
        isPrepared = false;
        preparingStation = -1;
        preparingMusic = -1;
        if (musicMode && !musicUris.isEmpty() && musicErrorRetries < 3) {
            musicErrorRetries++;
            skipCacheOnce = true;
            try { playMusicCurrent(); } catch (Exception ignored) {}
            return true;
        }
        musicErrorRetries = 0;
        setState("ERROR");
        return true;
    }

    @Override public void onCompletion(MediaPlayer mp) {
        if (musicMode && !musicUris.isEmpty()) {
            // Local music: advance to next track, wrapping around.
            playMusicIndex(musicIndex + 1);
            return;
        }
        // Live radio should not complete; if it does, try reconnect once.
        setState("BUFFERING");
        playCurrent();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            String a = intent.getAction();
            if (ACTION_PLAY.equals(a)) {
                if ("BUFFERING".equals(state)) {
                } else if ("PAUSED".equals(state) && player != null && isPrepared) {
                    try { player.start(); setState("PLAYING"); } catch (Exception e) {
                        if (musicMode) playMusicCurrent(); else playCurrent();
                    }
                } else if (musicMode) playMusicCurrent(); else playCurrent();
            } else if (ACTION_PAUSE.equals(a)) pausePlayback();
            else if (ACTION_TOGGLE.equals(a)) toggle();
            else if (ACTION_STOP.equals(a)) { stopPlayback(); stopSelf(); }
            else if (ACTION_NEXT.equals(a)) next();
            else if (ACTION_PREV.equals(a)) prev();
            else if (ACTION_PLAY_INDEX.equals(a)) playIndex(intent.getIntExtra(EXTRA_INDEX, 0));
            else if (ACTION_PLAY_MUSIC_LIST.equals(a)) {
                ArrayList<String> uris = intent.getStringArrayListExtra(EXTRA_MUSIC_URIS);
                ArrayList<String> names = intent.getStringArrayListExtra(EXTRA_MUSIC_NAMES);
                if (uris != null && !uris.isEmpty()) {
                    if (names == null || names.size() != uris.size()) {
                        names = new ArrayList<>();
                        for (String u : uris) {
                            int slash = u.lastIndexOf('/');
                            names.add(slash >= 0 ? u.substring(slash + 1) : u);
                        }
                    }
                    setMusicPlaylist(uris, names, intent.getIntExtra(EXTRA_INDEX, 0));
                }
            }
        }
        return START_STICKY;
    }

    @Override public void onDestroy() {
        try { unregisterReceiver(btPauseReceiver); } catch (Exception ignored) {}
        if (player != null) {
            try { player.release(); } catch (Exception ignored) {}
            player = null;
        }
        if (mediaSession != null) {
            try { mediaSession.setActive(false); mediaSession.release(); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }
}
