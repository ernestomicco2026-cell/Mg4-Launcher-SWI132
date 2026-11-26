package com.mg4.launcher.svc;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;

import com.mg4.launcher.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Sorgenti audio dell'auto, versione SWI69 (EH32 Color Radio), come la home originale SWI69:
 *  - USB e musica online: MediaBrowserService dell'app com.saicmotor.media
 *  - Bluetooth: A2dpMediaBrowserService di com.android.bluetooth
 *  - Radio FM/DAB: RadioMBService (sessione: titolo, testo, logo, play/pausa, ricerca) +
 *    IRadioManager di com.saicmotor.radio (frequenza, sintonia diretta, stazioni DAB)
 *  - CarPlay / Android Auto (allgo): com.allgo.rui + MediaBrowser per i metadati, come sulla SWI68
 * I preferiti radio li tiene il launcher (l'app Radio li salva in un database privato).
 */
public final class MediaClient {
    // --- sorgenti del launcher -------------------------------------------------------------
    public static final int SRC_RADIO = 1, SRC_USB = 2, SRC_ONLINE = 3, SRC_BT = 4, SRC_CARPLAY = 5, SRC_AAUTO = 6;
    /** 0.3.2 (punto 6): seconda porta USB (tipo C), servizio Usb2MediaBrowserService dell'app Media. */
    public static final int SRC_USB2 = 7;
    /** True quando la USB 2 è tra le sorgenti: allora la prima si chiama "USB 1". */
    public static volatile boolean usb2Listed;
    /** 0.3.2 (punto 4): da dove arrivano titolo/artista/copertina del Bluetooth (per il log e il Controllo funzioni). */
    public static volatile String btMetaSource = "";
    private static volatile int currentSource;

    /** Nome della sorgente mostrata adesso (per i log dei tasti al volante). */
    public static String currentSourceName() {
        return sourceName(currentSource);
    }

    public static String sourceName(Context c, int s) {
        return sourceName(s);
    }

    public static String sourceName(int s) {
        switch (s) {
            case SRC_RADIO: return "RADIO";
            case SRC_USB: return usb2Listed ? "MUSICA USB 1" : "MUSICA USB";
            case SRC_USB2: return "MUSICA USB 2";
            case SRC_ONLINE: return "MUSICA ONLINE";
            case SRC_BT: return "BLUETOOTH";
            case SRC_CARPLAY: return "CARPLAY";
            case SRC_AAUTO: return "ANDROID AUTO";
            default: return "MEDIA";
        }
    }

    // --- MediaBrowserService (SWI69) ----------------------------------------------------------
    private static final String MEDIA_PKG = "com.saicmotor.media";
    private static final String RADIO_PKG = "com.saicmotor.radio";

    // --- radio: IRadioManager (com.android.car.radio.service) ----------------------------------
    private static final String RADIO_ACTION = "com.saicmotor.IRadioService";
    private static final String RAD = "com.android.car.radio.service.IRadioManager";
    private static final int RAD_TUNE = 1, RAD_SCAN = 8, RAD_ADD_CB = 14, RAD_CURRENT = 16, RAD_ADD_DAB_CB = 37,
            RAD_DAB_TUNE = 41, RAD_DAB_QUERY_LIST = 46;
    private static final String RAD_CB = "com.android.car.radio.service.IRadioCallback";
    private static final String DAB_CB = "com.android.car.radio.service.IDabCallback";
    private static final String ACTION_NEXT_BAND = "__ACTION_NEXT_SOURCE__";
    private static final String ACTION_PREV_BAND = "__ACTION_PREV_SOURCE__";
    /** Bande dell'app Radio SWI69 (ordine di FeatureUtil.getRadioBands: DAB, FM, AM). */
    public static final int BAND_AM = 0, BAND_FM = 1, BAND_DAB = 4;

    // --- CarPlay / Android Auto ---------------------------------------------------------------
    private static final String RUI = "com.allgo.rui.IRemoteUIService";
    private static final int RUI_LAUNCH_APP = 8, RUI_ACTIVE_DEVICE = 15, RUI_GET_STATUS = 17, RUI_MEDIA_KEY = 18;
    public static final int DEV_CARPLAY = 1, DEV_AAUTO = 2;

    /** Stazione radio (preferiti FM / DAB). Per il DAB servono tutti i campi di RadioDabStation. */
    public static final class Station {
        public int band, khz, eid, index, pty, type, channel;
        public long sid;
        public String name = "", ensemble = "";
        public String key = "";
        public Bitmap logo;

        /** Nome breve da mostrare: nome RDS/DAB o frequenza. */
        public String label() {
            if (!name.isEmpty()) return name;
            if (band == BAND_AM) return khz + " kHz";
            return String.format(com.mg4.launcher.L.locale(), "%.1f", khz / 1000f);
        }

        /** Seconda riga nell'elenco: frequenza (FM) o multiplex (DAB). */
        public String detail() {
            if (band == BAND_DAB) return ensemble.isEmpty() ? "DAB+" : "DAB+ · " + ensemble;
            if (band == BAND_AM) return khz + " kHz";
            return String.format(com.mg4.launcher.L.locale(), "%.1f MHz", khz / 1000f);
        }

        JSONObject toJson() throws Exception {
            return new JSONObject().put("band", band).put("khz", khz).put("sid", sid).put("eid", eid)
                    .put("index", index).put("pty", pty).put("type", type).put("channel", channel)
                    .put("name", name).put("ensemble", ensemble);
        }

        static Station fromJson(JSONObject o) {
            Station s = new Station();
            s.band = o.optInt("band", BAND_FM);
            s.khz = o.optInt("khz");
            s.sid = o.optLong("sid");
            s.eid = o.optInt("eid");
            s.index = o.optInt("index");
            s.pty = o.optInt("pty");
            s.type = o.optInt("type");
            s.channel = o.optInt("channel");
            s.name = o.optString("name", "");
            s.ensemble = o.optString("ensemble", "");
            return s;
        }
    }

    /** Stato media mostrato dal launcher. */
    public static final class State {
        public int source;                 // sorgente attiva o scelta
        public boolean playing;
        public String title = "";
        public String subtitle = "";
        public long positionMs = -1, durationMs = -1;
        public int radioBand = BAND_FM;
        public String radioFreq = "";      // es. "101,5"
        public String radioUnit = "MHz";
        public String stationKey = "";     // stazione in onda (per evidenziarla tra i preferiti)
        public boolean stationIsFavorite;
        public Bitmap logo;                // logo della stazione in onda, se disponibile
        public List<Station> favorites = new ArrayList<>();
        public List<Station> stations = new ArrayList<>();   // elenco della banda in onda (FM o DAB)
        public boolean scanning;                              // ricerca stazioni in corso
        public int radioKhz;                                  // frequenza FM in onda (per evidenziarla)
        public int deviceType;             // 0 nessuno, 1 CarPlay, 2 Android Auto
        public String deviceName = "";
        public final List<Integer> sources = new ArrayList<>();
        // indicazioni di navigazione da CarPlay/AA
        public boolean tbtActive;
        public String tbtDistance = "", tbtRoad = "";
    }

    public interface Listener {
        void onMediaState(State s);
    }

    /** Un MediaBrowserService con il suo controller e riconnessione automatica. */
    private final class Browser {
        final String name;
        final ComponentName cn;
        volatile MediaBrowser browser;
        volatile MediaController controller;
        final MediaController.Callback callback;

        Browser(String name, String pkg, String cls, MediaController.Callback cb) {
            this.name = name;
            this.cn = new ComponentName(pkg, cls);
            this.callback = cb;
        }

        void connect() {
            main.post(() -> {
                if (browser != null) return;
                browser = new MediaBrowser(ctx, cn, new MediaBrowser.ConnectionCallback() {
                    @Override
                    public void onConnected() {
                        try {
                            MediaController c = new MediaController(ctx, browser.getSessionToken());
                            c.registerCallback(callback != null ? callback : refresh, main);
                            controller = c;
                            Diag.put("media." + name, "collegato");
                            post(MediaClient.this::poll);
                        } catch (Exception e) {
                            Diag.error("media." + name, e.toString());
                        }
                    }

                    @Override
                    public void onConnectionSuspended() {
                        controller = null;
                        Diag.put("media." + name, "sospeso");
                        retry(5000);
                    }

                    @Override
                    public void onConnectionFailed() {
                        controller = null;
                        Diag.put("media." + name, "non disponibile");
                        retry(15000);
                    }
                }, null);
                try {
                    browser.connect();
                } catch (Exception e) {
                    Diag.error("media." + name, e.toString());
                    retry(15000);
                }
            });
        }

        void retry(long ms) {
            main.post(() -> {
                MediaBrowser b = browser;
                browser = null;
                if (b != null) {
                    try {
                        b.disconnect();
                    } catch (Exception ignored) {
                    }
                }
                if (started) main.postDelayed(this::connect, ms);
            });
        }

        void disconnect() {
            main.post(() -> {
                MediaBrowser b = browser;
                browser = null;
                controller = null;
                if (b != null) {
                    try {
                        b.disconnect();
                    } catch (Exception ignored) {
                    }
                }
            });
        }

        boolean ready() {
            return controller != null;
        }

        boolean isPlaying() {
            MediaController c = controller;
            PlaybackState ps = c == null ? null : c.getPlaybackState();
            return ps != null && ps.getState() == PlaybackState.STATE_PLAYING;
        }

        /** Stato della sessione per i log: "in riproduzione", "in pausa", "fermo"… */
        String stateName() {
            MediaController c = controller;
            PlaybackState ps = c == null ? null : c.getPlaybackState();
            if (c == null) return "sessione non collegata";
            if (ps == null) return "nessuno stato";
            switch (ps.getState()) {
                case PlaybackState.STATE_PLAYING: return "in riproduzione";
                case PlaybackState.STATE_PAUSED: return "in pausa";
                case PlaybackState.STATE_STOPPED: return "fermo";
                case PlaybackState.STATE_BUFFERING: return "in caricamento";
                case PlaybackState.STATE_NONE: return "nessuno";
                default: return "stato " + ps.getState();
            }
        }

        boolean isPaused() {
            MediaController c = controller;
            PlaybackState ps = c == null ? null : c.getPlaybackState();
            return ps != null && ps.getState() == PlaybackState.STATE_PAUSED;
        }

        MediaController.TransportControls controls() {
            MediaController c = controller;
            return c == null ? null : c.getTransportControls();
        }
    }

    private final Context ctx;
    private final ServiceLink radio, rui;
    private final Browser usb, usb2, online, bt, radioMbs, remote;
    private final HandlerThread thread = new HandlerThread("mg4-media");
    private Handler bg;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SharedPreferences sp;
    private Listener listener;
    private volatile State last = new State();
    private volatile int chosen;               // sorgente scelta dall'utente
    private volatile Bundle tbt;
    private final StationLogos logos;
    private volatile List<Station> favorites = new ArrayList<>();
    // radio: dati dai callback di IRadioManager
    private volatile Station fmCurrent, dabCurrent;
    private volatile int bandFromCallback = -1;
    private volatile List<Station> dabList = new ArrayList<>();
    private volatile List<Station> fmList = new ArrayList<>();
    private volatile boolean scanning;
    private volatile long scanUntil;
    private volatile String dabText = "";
    private boolean started, running;

    public MediaClient(Context c) {
        ctx = c.getApplicationContext();
        instanceNow = this;
        sp = ctx.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE);
        radio = new ServiceLink(ctx, "radio", ServiceLink.byAction(RADIO_PKG, RADIO_ACTION));
        radio.setOnConnected(() -> post(this::registerRadioCallbacks));
        rui = new ServiceLink(ctx, "carplay", ServiceLink.byComponent("com.allgo.rui", "com.allgo.rui.RemoteUIService", null));
        usb = new Browser("usb", MEDIA_PKG, "com.saicmotor.media.mbs.Usb1MediaBrowserService", null);
        usb2 = new Browser("usb2", MEDIA_PKG, "com.saicmotor.media.mbs.Usb2MediaBrowserService", null);
        online = new Browser("online", MEDIA_PKG, "com.saicmotor.media.mbs.OnlineMediaBrowserService", null);
        bt = new Browser("bt", "com.android.bluetooth", "com.android.bluetooth.a2dpsink.mbs.A2dpMediaBrowserService", null);
        radioMbs = new Browser("radio.sessione", RADIO_PKG, "com.saicmotor.radio.service.RadioMBService", null);
        remote = new Browser("carplay.media", "com.allgo.remoteui.mediabrowserservice",
                "com.allgo.remoteui.mediabrowserservice.RemoteUIMediaBrowserService", remoteCallback);
        logos = new StationLogos(ctx);
        logos.setOnUpdate(this::refreshLogosSoon);
        loadFavorites();
        fmList = loadList("radio_fm_elenco");
        dabList = loadList("radio_dab_elenco");
    }

    public void setListener(Listener l) {
        listener = l;
    }

    /** Chiamata in corso su CarPlay/Android Auto (stato di Allgo: com.allgo.extra.CallStatus diverso da 0). */
    public boolean remoteCallActive() {
        IBinder r = rui.binder();
        if (r == null) return false;
        try {
            android.os.Bundle b = Rpc.call(r, RUI, RUI_GET_STATUS, Rpc.NONE,
                    p -> Rpc.readNullable(p, q -> android.os.Bundle.CREATOR.createFromParcel(q)));
            return b != null && b.getInt("com.allgo.extra.CallStatus", 0) != 0;
        } catch (Exception e) {
            return false;
        }
    }

    public State last() {
        return last;
    }

    public void start() {
        if (started) return;
        started = true;
        running = true;
        if (bg == null) {
            thread.start();
            bg = new Handler(thread.getLooper());
        }
        radio.start();
        rui.start();
        usb.connect();
        usb2.connect();
        online.connect();
        bt.connect();
        radioMbs.connect();
        remote.connect();
        bg.post(loop);
        // 0.3.1 (punto 10): all'accensione si riprende l'ultima sorgente usata davvero, se nel frattempo non suona nulla.
        // Solo all'avvio dell'auto (non quando il launcher si riavvia da solo, es. dopo un aggiornamento) e al risveglio.
        // 0.3.5 (SWI68 1.0.6, issue #43): ripresa a OGNI accensione, qualunque strada prenda l'avvio
        String sys = takeSystemPowerOn();
        String pw = pendingPowerOn != null ? pendingPowerOn : sys;
        pendingPowerOn = null;
        if (pw != null) onPowerOn(pw);
        else if (android.os.SystemClock.elapsedRealtime() < 5 * 60_000L) onPowerOn("avvio del launcher all'accensione");
        else powerOnSkipped("launcher avviato " + android.os.SystemClock.elapsedRealtime() / 60_000
                + " min dopo l'accensione (aggiornamento o riavvio a metà viaggio)");
        try {
            IntentFilter f = new IntentFilter(Intent.ACTION_SCREEN_ON);
            f.addAction(Intent.ACTION_SCREEN_OFF);
            ctx.registerReceiver(screen, f);
        } catch (Exception ignored) {
        }
    }

    private long screenOffAt;
    private final android.content.BroadcastReceiver screen = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                screenOffAt = android.os.SystemClock.elapsedRealtime();
                return;
            }
            // risveglio dopo almeno 2 minuti di schermo spento = nuova accensione dell'auto
            if (screenOffAt > 0 && android.os.SystemClock.elapsedRealtime() - screenOffAt > 120_000L && bg != null) {
                long off = android.os.SystemClock.elapsedRealtime() - screenOffAt;
                chosen = 0;
                BootLog.powerOn("risveglio dello schermo dopo " + (off / 60_000) + " min");
                DeepCheck.onPowerOn(ctx, "risveglio dello schermo");   // 0.3.6: diagnostica automatica (una volta per versione)
                onPowerOn("risveglio dello schermo dopo " + (off / 60_000) + " min");
            }
        }
    };

    /** Sospende le letture (launcher non visibile) senza scollegarsi dai servizi. */
    public void pause() {
        running = false;
        if (bg != null) bg.removeCallbacks(loop);
    }

    public void resume() {
        if (bg == null || running) return;
        running = true;
        bg.post(loop);
    }

    public void stop() {
        running = false;
        started = false;
        if (instanceNow == this) instanceNow = null;   // 0.3.6 (come SWI68 1.0.7): un'accensione dopo lo stop aspetta il prossimo client
        try {
            ctx.unregisterReceiver(screen);
        } catch (Exception ignored) {
        }
        if (bg != null) bg.removeCallbacks(loop);
        radio.stop();
        rui.stop();
        usb.disconnect();
        usb2.disconnect();
        online.disconnect();
        bt.disconnect();
        radioMbs.disconnect();
        remote.disconnect();
    }

    private void post(Runnable r) {
        if (bg != null) bg.post(r);
    }

    private boolean radioMarked;

    private final Runnable loop = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            poll();
            if (!radioMarked && radio.binder() != null) {
                radioMarked = true;
                BootLog.mark(ctx, "radio", null);
            }
            bg.postDelayed(this, 1000);
        }
    };

    /** Callback generico: ogni cambio di stato/metadati aggiorna subito la UI. */
    private final MediaController.Callback refresh = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            post(MediaClient.this::poll);
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            post(MediaClient.this::poll);
        }
    };

    // ------------------------------------------------------------------------------------------
    // Lettura stato
    // ------------------------------------------------------------------------------------------

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    private static String join(String a, String b) {
        a = nz(a);
        b = nz(b);
        if (a.isEmpty()) return b;
        if (b.isEmpty()) return a;
        return a + " · " + b;
    }

    private Browser browserFor(int src) {
        switch (src) {
            case SRC_USB: return usb;
            case SRC_USB2: return usb2;
            case SRC_ONLINE: return online;
            case SRC_BT: return bt;
            case SRC_RADIO: return radioMbs;
            case SRC_CARPLAY:
            case SRC_AAUTO: return remote;
            default: return null;
        }
    }

    /** Riserva: CarPlay / Android Auto dalla sessione media attiva delle app AllGo (0 se nessuna). */
    private int projectionFromSessions() {
        try {
            android.media.session.MediaSessionManager msm =
                    (android.media.session.MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            for (MediaController mc : msm.getActiveSessions(null)) {
                String p = mc.getPackageName();
                if ("com.allgo.app.androidauto".equals(p)) return DEV_AAUTO;
                if ("com.allgo.carplay.service".equals(p)) return DEV_CARPLAY;
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private void poll() {
        State s = new State();

        // CarPlay / Android Auto collegato? 0.3.3 (SWI68 1.0.4 punto E5): fonte principale il servizio AllGo
        // (codice 15); se non risponde, le sessioni media delle app AllGo
        IBinder r = rui.binder();
        String projSrc = "AllGo (codice 15)";
        boolean ruiOk = false;
        if (r != null) {
            try {
                int[] dev = Rpc.call(r, RUI, RUI_ACTIVE_DEVICE, Rpc.NONE, p -> Rpc.readNullable(p, q -> {
                    int session = q.readInt();
                    String name = q.readString();
                    q.readString();
                    q.readString();
                    int type = q.readInt();
                    s.deviceName = nz(name);
                    return new int[]{type, session};
                }));
                s.deviceType = dev == null ? 0 : dev[0];
                ruiOk = true;
                // nel log niente nome del telefono (dato personale)
                Diag.put("carplay.dispositivo", dev == null ? "nessuno" : ("tipo " + dev[0] + ", sessione " + dev[1]));
            } catch (Exception e) {
                Diag.error("carplay", e.toString());
            }
        }
        if (!ruiOk) {
            projSrc = "sessioni media AllGo (servizio AllGo non risponde)";
            s.deviceType = projectionFromSessions();
        }
        Diag.putQuiet("proiezione", "fonte " + projSrc + " · " + (s.deviceType == DEV_AAUTO ? "AA" : s.deviceType == DEV_CARPLAY ? "CarPlay" : "nessuna"));

        // sorgenti disponibili
        s.sources.add(SRC_RADIO);
        s.sources.add(SRC_USB);
        boolean u2 = usb2.isPlaying() || usbPortAvailable("usb2AvailableStatus");
        if (u2) s.sources.add(SRC_USB2);
        if (u2 != usb2Listed) {
            usb2Listed = u2;
            Diag.put("media.usb2", u2 ? "chiavetta nella USB 2: sorgenti separate USB 1 / USB 2" : "nessuna chiavetta nella USB 2");
        }
        s.sources.add(SRC_BT);
        if (online.ready() && onlineAvailable()) s.sources.add(SRC_ONLINE);
        onlinePossible = s.sources.contains(SRC_ONLINE);
        if (s.deviceType == DEV_CARPLAY) s.sources.add(SRC_CARPLAY);
        if (s.deviceType == DEV_AAUTO) s.sources.add(SRC_AAUTO);

        // sorgente in riproduzione (quella con la sessione in PLAYING)
        int active = 0;
        if (radioMbs.isPlaying()) active = SRC_RADIO;
        else if (usb.isPlaying()) active = SRC_USB;
        else if (usb2.isPlaying()) active = SRC_USB2;
        else if (bt.isPlaying()) active = SRC_BT;
        else if (online.isPlaying()) active = SRC_ONLINE;
        else if (remote.isPlaying() && s.deviceType != 0) active = s.deviceType == DEV_AAUTO ? SRC_AAUTO : SRC_CARPLAY;
        Diag.put("media.attiva", sourceName(ctx, active));
        rememberSource(active);
        if (s.deviceType != 0) Diag.put("carplay.riproduzione", remote.stateName());
        // quando il telefono (CarPlay / Android Auto) inizia a suonare diventa la sorgente attiva: i tasti comandano lui
        if ((active == SRC_CARPLAY || active == SRC_AAUTO) && prevActive != active && chosen != active) {
            chosen = active;
            Diag.put("carplay.sorgente", sourceName(ctx, active) + " in riproduzione: diventa la sorgente attiva");
        }
        checkHijack(active);

        // di default si vede la sorgente in riproduzione, altrimenti la radio
        int shown = chosen != 0 ? chosen : (active != 0 ? active : SRC_RADIO);
        if (!s.sources.contains(shown)) shown = active != 0 && s.sources.contains(active) ? active : SRC_RADIO;
        s.source = shown;
        currentSource = shown;

        try {
            if (shown == SRC_RADIO) fillRadio(s);
            else fillSession(s, shown);
        } catch (Exception e) {
            Diag.error("media." + sourceName(ctx, shown), e.toString());
        }

        Bundle t = tbt;
        if (t != null && s.deviceType != 0) {
            s.tbtActive = t.getBoolean("IS_TBT_ACTIVE", false);
            s.tbtDistance = (nz(String.valueOf(t.get("DIST_TO_NEXT_JUNCTION"))) + " "
                    + nz(String.valueOf(t.get("DIST_TO_NEXT_JUNCTION_UNIT")))).replace("null", "").trim();
            s.tbtRoad = nz(t.getString("ROAD_AFTER_JUNCTION"));
        }

        last = s;
        main.post(() -> {
            if (listener != null) listener.onMediaState(s);
        });
    }

    /** USB, Bluetooth, online, CarPlay/AA: tutto dalla sessione media. */
    private void fillSession(State s, int src) {
        Browser b = browserFor(src);
        MediaController c = b == null ? null : b.controller;
        if (c != null) {
            MediaMetadata m = c.getMetadata();
            PlaybackState ps = c.getPlaybackState();
            if (m != null) {
                s.title = nz(m.getString(MediaMetadata.METADATA_KEY_TITLE));
                s.subtitle = join(m.getString(MediaMetadata.METADATA_KEY_ARTIST), m.getString(MediaMetadata.METADATA_KEY_ALBUM));
                s.durationMs = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
                if (src == SRC_BT) {
                    Bitmap art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                    String artFrom = art != null ? "ALBUM_ART" : "";
                    if (art == null) {
                        art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
                        if (art != null) artFrom = "ART";
                    }
                    if (art == null) {
                        art = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
                        if (art != null) artFrom = "DISPLAY_ICON";
                    }
                    s.logo = art;
                    btMetaSource = "sessione A2DP/AVRCP del telefono (titolo " + (s.title.isEmpty() ? "assente" : "TITLE")
                            + ", artista " + (nz(m.getString(MediaMetadata.METADATA_KEY_ARTIST)).isEmpty() ? "assente" : "ARTIST")
                            + ", copertina " + (artFrom.isEmpty() ? "assente" : artFrom) + ")";
                }
            }
            if (src == SRC_BT) {
                if (m == null) btMetaSource = "nessun metadato dal telefono";
                Diag.put("media.bt.info", "titolo " + (s.title.isEmpty() ? "no" : "sì") + " · artista "
                        + (m != null && !nz(m.getString(MediaMetadata.METADATA_KEY_ARTIST)).isEmpty() ? "sì" : "no")
                        + " · copertina " + (s.logo != null ? "sì" : "no") + " · fonte " + btMetaSource);
            }
            if (ps != null) {
                s.playing = ps.getState() == PlaybackState.STATE_PLAYING;
                long pos = ps.getPosition();
                if (s.playing && ps.getLastPositionUpdateTime() > 0) {
                    pos += (long) ((SystemClock.elapsedRealtime() - ps.getLastPositionUpdateTime()) * ps.getPlaybackSpeed());
                }
                s.positionMs = pos >= 0 ? Math.min(pos, s.durationMs > 0 ? s.durationMs : pos) : -1;
                Diag.put("media." + b.name + ".stato", ps.getState());
            }
        } else if (b != null) {
            Diag.put("media." + b.name + ".stato", "non collegato");
        }
        switch (src) {
            case SRC_USB:
            case SRC_USB2:
                if (s.title.isEmpty()) s.title = com.mg4.launcher.L.s(R.string.no_track);
                if (s.subtitle.isEmpty()) s.subtitle = com.mg4.launcher.L.s(R.string.plug_usb);
                break;
            case SRC_BT:
                if (s.title.isEmpty()) s.title = com.mg4.launcher.L.s(R.string.bt_music);
                if (s.subtitle.isEmpty()) s.subtitle = com.mg4.launcher.L.s(R.string.bt_start);
                break;
            case SRC_ONLINE:
                if (s.title.isEmpty()) s.title = com.mg4.launcher.L.s(R.string.online_music);
                break;
            case SRC_CARPLAY:
            case SRC_AAUTO:
                fillProjection(s, c);
                if (s.title.isEmpty()) s.title = src == SRC_CARPLAY ? "CarPlay" : "Android Auto";
                if (s.subtitle.isEmpty()) s.subtitle = s.deviceName.isEmpty() ? com.mg4.launcher.L.s(R.string.from_phone) : s.deviceName;
                break;
            default:
                break;
        }
    }

    // ---- 0.3.5 (SWI68 1.0.6, issue #28 e #30): Android Auto / CarPlay tra le sorgenti, titolo, artista, copertina ----

    /** Sessione media dell'app AllGo (com.allgo.app.androidauto o com.allgo.carplay.service), null se non c'è. */
    private MediaController allgoSession() {
        try {
            android.media.session.MediaSessionManager msm =
                    (android.media.session.MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            for (MediaController mc : msm.getActiveSessions(null)) {
                String p = mc.getPackageName();
                if ("com.allgo.app.androidauto".equals(p) || "com.allgo.carplay.service".equals(p)) return mc;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Tocco sul pulsante CarPlay/AA tra le sorgenti: play alla sessione AllGo, altrimenti il tasto del servizio. */
    public void playProjection() {
        post(() -> {
            MediaController a = allgoSession();
            try {
                if (a != null) {
                    a.getTransportControls().play();
                    Diag.put("carplay.tasto", "play alla sessione " + a.getPackageName());
                } else {
                    remotePlay(true);
                    Diag.put("carplay.tasto", "play al servizio RemoteUI (nessuna sessione AllGo)");
                }
            } catch (Exception e) {
                Diag.error("carplay.play", e.toString());
            }
        });
    }

    private String coverUri;
    private Bitmap coverUriBmp;
    private String lastProjStudy;

    /**
     * Titolo e artista: sessione RemoteUI del servizio media; se vuota, la sessione dell'app AllGo. Copertina: si
     * provano tutte le fonti (immagini ALBUM_ART/ART/DISPLAY_ICON, indirizzi *_URI, immagine della voce in coda) delle
     * due sessioni; la prima trovata va in s.logo. Riga media.proiezione per ogni fonte: sì/no e dimensioni, MAI testi.
     */
    private void fillProjection(State s, MediaController remoteC) {
        MediaController a = allgoSession();
        if (s.title.isEmpty() && a != null && a.getMetadata() != null) {
            MediaMetadata m = a.getMetadata();
            s.title = nz(m.getString(MediaMetadata.METADATA_KEY_TITLE));
            s.subtitle = join(m.getString(MediaMetadata.METADATA_KEY_ARTIST), m.getString(MediaMetadata.METADATA_KEY_ALBUM));
            if (s.durationMs <= 0) s.durationMs = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
        }
        StringBuilder st = new StringBuilder(s.deviceType == DEV_AAUTO ? "AA" : "CarPlay");
        st.append(" · titolo ").append(s.title.isEmpty() ? "no" : "sì").append(" · artista ").append(s.subtitle.isEmpty() ? "no" : "sì");
        Bitmap found = null;
        String foundFrom = "";
        // 0.3.6 (issue SWI68 #30, CarPlay senza copertina): oltre a RemoteUI e alla sessione AllGo scelta, tutte le
        // sessioni attive delle app AllGo (CarPlay e Android Auto hanno sessioni diverse). Solo letture.
        java.util.List<Object[]> srcList = new ArrayList<>();
        srcList.add(new Object[]{"RemoteUI", remoteC});
        srcList.add(new Object[]{a == null ? "AllGo" : a.getPackageName(), a});
        try {
            android.media.session.MediaSessionManager msm =
                    (android.media.session.MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
            for (MediaController mc : msm.getActiveSessions(null)) {
                String p = mc.getPackageName();
                if (!p.startsWith("com.allgo.")) continue;
                boolean dup = (a != null && mc.getSessionToken().equals(a.getSessionToken()))
                        || (remoteC != null && mc.getSessionToken().equals(remoteC.getSessionToken()));
                if (!dup) srcList.add(new Object[]{p, mc});
            }
        } catch (Throwable ignored) {
        }
        StringBuilder keys = new StringBuilder();
        for (Object[] sc : srcList) {
            String name = (String) sc[0];
            MediaController mc = (MediaController) sc[1];
            if (mc == null) {
                st.append(" | ").append(name).append(": sessione assente");
                continue;
            }
            MediaMetadata m = mc.getMetadata();
            st.append(" | ").append(name).append(':');
            String[] bk = {MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON};
            String[] bn = {"ALBUM_ART", "ART", "DISPLAY_ICON"};
            for (int i = 0; i < bk.length; i++) {
                Bitmap bm = m == null ? null : m.getBitmap(bk[i]);
                st.append(' ').append(bn[i]).append(bm == null ? " no" : " " + bm.getWidth() + "×" + bm.getHeight());
                if (bm != null) keys.append(' ').append(name.replace("com.allgo.", "")).append(':').append(bn[i]).append(' ').append(bm.getWidth()).append('×').append(bm.getHeight());
                if (bm != null && found == null) {
                    found = bm;
                    foundFrom = name + " " + bn[i];
                }
            }
            String[] uk = {MediaMetadata.METADATA_KEY_ALBUM_ART_URI, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI};
            String[] un = {"ALBUM_ART_URI", "ART_URI", "DISPLAY_ICON_URI"};
            for (int i = 0; i < uk.length; i++) {
                String u = m == null ? null : m.getString(uk[i]);
                if (u == null || u.isEmpty()) {
                    st.append(' ').append(un[i]).append(" no");
                    continue;
                }
                Bitmap bm = loadUri(u);
                String scheme = u.contains(":") ? u.substring(0, u.indexOf(':')) : "?";
                keys.append(' ').append(name.replace("com.allgo.", "")).append(':').append(un[i]).append('(').append(scheme).append(bm == null ? ", non leggibile)" : ")");
                st.append(' ').append(un[i]).append(" sì (").append(scheme).append(bm == null ? ", non leggibile)" : ", " + bm.getWidth() + "×" + bm.getHeight() + ")");
                if (bm != null && found == null) {
                    found = bm;
                    foundFrom = name + " " + un[i];
                }
            }
            // icona della descrizione dei metadati (alcune sessioni mettono lì la copertina)
            android.media.MediaDescription md = m == null ? null : m.getDescription();
            Bitmap dib = md == null ? null : md.getIconBitmap();
            if (dib == null && md != null && md.getIconUri() != null) dib = loadUri(md.getIconUri().toString());
            st.append(" descrizione ").append(dib == null ? "no" : dib.getWidth() + "×" + dib.getHeight());
            if (dib != null) keys.append(' ').append(name.replace("com.allgo.", "")).append(":descrizione ").append(dib.getWidth()).append('×').append(dib.getHeight());
            if (dib != null && found == null) {
                found = dib;
                foundFrom = name + " descrizione";
            }
            try {
                java.util.List<android.media.session.MediaSession.QueueItem> q = mc.getQueue();
                PlaybackState ps = mc.getPlaybackState();
                long activeId = ps == null ? -1 : ps.getActiveQueueItemId();
                android.media.MediaDescription d = null;
                if (q != null) for (android.media.session.MediaSession.QueueItem it : q) if (it.getQueueId() == activeId) d = it.getDescription();
                if (d == null && q != null && !q.isEmpty()) d = q.get(0).getDescription();
                Bitmap ib = d == null ? null : d.getIconBitmap();
                if (ib == null && d != null && d.getIconUri() != null) ib = loadUri(d.getIconUri().toString());
                st.append(" coda ").append(q == null ? "assente" : q.size() + " voci").append(" immagine ").append(ib == null ? "no" : ib.getWidth() + "×" + ib.getHeight());
                if (ib != null && found == null) {
                    found = ib;
                    foundFrom = name + " coda";
                }
            } catch (Throwable e) {
                st.append(" coda non leggibile");
            }
        }
        s.logo = found;
        st.append(" · copertina mostrata ").append(found == null ? "no (icona AA/CarPlay)" : "sì da " + foundFrom);
        String txt = st.toString();
        if (!txt.equals(lastProjStudy)) {
            lastProjStudy = txt;
            Diag.put("media.proiezione", txt);
        }
        // 0.3.6 (punto 8): media.copertina: sorgente, chiavi arrivate (nomi e misure, mai titoli), esito; solo se cambia
        String cov = (s.deviceType == DEV_AAUTO ? "Android Auto" : "CarPlay") + " · sessioni " + srcList.size() + " · chiavi"
                + (keys.length() == 0 ? " nessuna" : keys) + " · esito " + (found == null ? "nessuna copertina (resta l'icona)" : "mostrata da " + foundFrom);
        if (!cov.equals(lastCover)) {
            lastCover = cov;
            Diag.put("media.copertina", cov);
        }
    }

    /** Immagine da un indirizzo content://, file:// o android.resource:// (la stessa si tiene in memoria). */
    private String lastCover = "";

    private Bitmap loadUri(String u) {
        if (u.equals(coverUri)) return coverUriBmp;
        Bitmap bm = null;
        // solo indirizzi locali (mai internet); immagine ridotta a circa 512 px per non pesare sulla RAM
        if (u.startsWith("content://") || u.startsWith("file://") || u.startsWith("android.resource://")) {
            android.net.Uri uri = android.net.Uri.parse(u);
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            try (java.io.InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                o.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeStream(in, null, o);
            } catch (Throwable ignored) {
            }
            int sample = 1;
            while (o.outWidth / (sample * 2) >= 512 && o.outHeight / (sample * 2) >= 512) sample *= 2;
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            try (java.io.InputStream in = ctx.getContentResolver().openInputStream(uri)) {
                bm = android.graphics.BitmapFactory.decodeStream(in, null, o);
            } catch (Throwable ignored) {
            }
        }
        coverUri = u;
        coverUriBmp = bm;
        return bm;
    }

    /** Radio: sessione RadioMBService (nome, testo, logo DAB, stato) + frequenza da IRadioManager. */
    private void fillRadio(State s) {
        MediaController c = radioMbs.controller;
        MediaMetadata m = c == null ? null : c.getMetadata();
        PlaybackState ps = c == null ? null : c.getPlaybackState();
        String author = m == null ? "" : nz(m.getString(MediaMetadata.METADATA_KEY_AUTHOR));
        String title = m == null ? "" : nz(m.getString(MediaMetadata.METADATA_KEY_TITLE));
        String artist = m == null ? "" : nz(m.getString(MediaMetadata.METADATA_KEY_ARTIST));
        Bitmap art = m == null ? null : m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        s.playing = ps != null && ps.getState() == PlaybackState.STATE_PLAYING;

        // stazione FM/AM corrente (frequenza) dal servizio radio
        IBinder b = radio.binder();
        if (b != null) {
            try {
                Station cur = Rpc.call(b, RAD, RAD_CURRENT, Rpc.NONE, p -> Rpc.readNullable(p, MediaClient::readRadioStation));
                if (cur != null) fmCurrent = cur;
            } catch (Exception e) {
                Diag.error("radio.corrente", e.toString());
            }
        }

        // la sessione radio mette "DAB" come autore per il DAB; per FM/AM nessun autore
        int band;
        if ("DAB".equalsIgnoreCase(author)) band = BAND_DAB;
        else if (m != null) band = fmCurrent != null && fmCurrent.band == BAND_AM ? BAND_AM : BAND_FM;
        else band = bandFromCallback >= 0 ? bandFromCallback : BAND_FM;
        s.radioBand = band;

        if (band == BAND_DAB) {
            Station d = currentDab(title);
            String name = !title.isEmpty() ? title : d != null ? d.name : "";
            s.title = name;
            s.radioFreq = "DAB";
            s.radioUnit = d != null ? d.ensemble : "";
            s.subtitle = !artist.isEmpty() ? artist : nz(dabText);
            s.stationKey = StationLogos.key(BAND_DAB, d != null ? d.sid : 0, 0, name);
            if (art != null) logos.put(s.stationKey, art);
            s.logo = art != null ? art : logos.get(s.stationKey, name);
        } else {
            Station f = fmCurrent;
            int khz = f != null ? f.khz : 0;
            String name = !title.isEmpty() ? title : f != null ? f.name : "";
            if (band == BAND_AM) {
                s.radioFreq = khz > 0 ? String.valueOf(khz) : "";
                s.radioUnit = "kHz";
            } else {
                s.radioFreq = khz > 0 ? String.format(com.mg4.launcher.L.locale(), "%.1f", khz / 1000f) : "";
                s.radioUnit = "MHz";
            }
            // per l'FM la sessione usa il nome RDS come titolo; se è solo la frequenza non è un nome
            s.title = name.matches("[0-9.,\\s]*(MHz|kHz)?") ? "" : name;
            s.subtitle = artist;
            s.stationKey = StationLogos.key(band, 0, khz, s.title);
            s.logo = logos.get(s.stationKey, s.title);
            s.radioKhz = khz;
            if (band == BAND_FM && khz > 0) rememberFm(khz, s.title);
        }
        // elenco delle stazioni della banda in onda
        List<Station> list = band == BAND_DAB ? dabList : fmList;
        String from = band == BAND_DAB ? dabListFrom : fmListFrom;
        // 0.3.2 (punto 7): elenco vuoto → preferiti della stessa banda. 0.3.5 (punto 17): fonti a confronto (elenco del
        // servizio/salvato, stazioni DAB ascoltate come la SWI69 EH32, preferiti): si usa quella che dà più stazioni
        List<Station> fb = new ArrayList<>();
        for (Station st : favorites) if ((st.band == BAND_DAB) == (band == BAND_DAB)) fb.add(st);
        int nList = list.size();
        if (fb.size() > nList) {
            list = fb;
            from = nList == 0 ? "preferiti (elenco vuoto)" : "preferiti (più stazioni dell'elenco: " + fb.size() + " contro " + nList + ")";
        }
        String cmp = (band == BAND_DAB ? "DAB" : "FM") + " · " + (band == BAND_DAB ? dabListFrom : fmListFrom) + " " + nList
                + " · preferiti " + fb.size() + " → scelta " + from;
        if (!cmp.equals(sourcesNote)) {
            sourcesNote = cmp;
            Diag.put("radio.elenco.fonti", cmp);
        }
        for (Station st : list) {
            if (st.key.isEmpty()) st.key = StationLogos.key(st.band, st.sid, st.khz, st.name);
            if (st.logo == null) st.logo = logos.get(st.key, st.name);
        }
        s.stations = list;
        noteList(band, from, list.size());
        if (scanning && SystemClock.uptimeMillis() > scanUntil) scanning = false;
        s.scanning = scanning;
        Diag.put("radio.corrente", "banda " + band + ", " + s.radioFreq + " " + s.radioUnit + ", " + s.title
                + (ps != null ? " (stato " + ps.getState() + ")" : ""));

        List<Station> fav = favorites;
        for (Station st : fav) {
            if (st.logo == null) st.logo = logos.get(st.key, st.name);
            if (st.key.equals(s.stationKey)) s.stationIsFavorite = true;
        }
        s.favorites = fav;
        if (s.title.isEmpty()) s.title = s.radioFreq.isEmpty() ? com.mg4.launcher.L.s(R.string.src_radio) : s.radioFreq + " " + s.radioUnit;
    }

    /** Stazione DAB in onda: dal callback, oppure cercata per nome nell'elenco DAB. */
    private Station currentDab(String name) {
        Station d = dabCurrent;
        if (d != null && (name.isEmpty() || d.name.equalsIgnoreCase(name))) return d;
        for (Station x : dabList) if (x.name.equalsIgnoreCase(name)) return x;
        return d;
    }

    /** RadioStation: canale (kHz), sottocanale, banda, RadioRds (Parcelable o null). */
    private static Station readRadioStation(Parcel p) {
        Station st = new Station();
        st.khz = p.readInt();
        p.readInt();                               // sottocanale
        st.band = p.readInt();
        String cls = p.readString();               // writeParcelable: nome della classe, null se assente
        if (cls != null) {
            st.name = nz(p.readString());          // programService (nome RDS)
            p.readString();                        // artista
            p.readString();                        // titolo
        }
        return st;
    }

    /** RadioDabStation (ordine di writeToParcel). */
    private static Station readDabStation(Parcel p) {
        Station st = new Station();
        st.band = BAND_DAB;
        st.pty = p.readInt();
        st.name = nz(p.readString());
        st.sid = p.readLong();
        st.ensemble = nz(p.readString());
        st.eid = p.readInt();
        st.index = p.readInt();
        st.type = p.readInt();
        st.channel = p.readInt();
        return st;
    }

    private static void writeDabStation(Parcel p, Station st) {
        p.writeInt(1);                             // non null
        p.writeInt(st.pty);
        p.writeString(st.name);
        p.writeLong(st.sid);
        p.writeString(st.ensemble);
        p.writeInt(st.eid);
        p.writeInt(st.index);
        p.writeInt(st.type);
        p.writeInt(st.channel);
    }

    // ---- callback dal servizio radio (stazione, banda, DAB) ---------------------------------

    private final Binder radioCallback = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(RAD_CB);
                return true;
            }
            try {
                data.enforceInterface(RAD_CB);
                if (code == 1) {                   // onRadioStationChanged(RadioStation)
                    if (data.readInt() != 0) fmCurrent = readRadioStation(data);
                } else if (code == 4) {            // onScanStarted(int banda)
                    scanning = true;
                    scanUntil = SystemClock.uptimeMillis() + 120_000;
                    Diag.put("radio.ricerca", "avviata, banda " + data.readInt());
                } else if (code == 5) {            // onScanFinished(int banda, List<RadioStation>)
                    int band = data.readInt();
                    int n = data.readInt();
                    List<Station> found = new ArrayList<>();
                    for (int i = 0; i < n && i < 200; i++) if (data.readInt() != 0) found.add(readRadioStation(data));
                    scanning = false;
                    Diag.put("radio.ricerca", "finita, banda " + band + ": " + found.size() + " stazioni");
                    if (!found.isEmpty() && band != BAND_DAB) {
                        mergeFm(found, true);
                        fmListFrom = "ricerca completa";
                    }
                    if (band != BAND_DAB) LogoPacks.noteStations(BAND_FM, found);
                } else if (code == 3) {            // onRadioBandChanged(int)
                    bandFromCallback = data.readInt();
                    Diag.put("radio.banda", bandFromCallback);
                } else if (code == 9) {            // onSearchFinished(int frequenza): stazione trovata con la ricerca avanti/indietro
                    int khz = data.readInt();
                    if (khz > 60000 && khz < 120000) {
                        rememberFm(khz, "", true);
                        fmListFrom = "stazioni trovate e ascoltate";
                    }
                } else if (code == 19) {           // onRdsMainInfoChanged(char[] nome, int frequenza)
                    char[] ps = data.createCharArray();
                    int khz = data.readInt();
                    String name = ps == null ? "" : new String(ps).trim();
                    if (khz > 60000 && khz < 120000 && !name.isEmpty()) {
                        rememberFm(khz, name, false);
                        fmListFrom = "stazioni trovate e ascoltate";
                    }
                } else if (code == 7) {            // onInnerScanFinished(int banda): l'app Radio ha aggiornato il suo elenco
                    Diag.put("radio.ricerca", "ricerca interna finita, banda " + data.readInt() + " (l'elenco resta dentro l'app Radio)");
                }
            } catch (Exception e) {
                Diag.error("radio.callback", e.toString());
            }
            if (reply != null) reply.writeNoException();
            if (code == 1 || code == 3 || code == 4 || code == 5 || code == 9 || code == 19) post(MediaClient.this::poll);   // non per segnale, RDS…
            return true;
        }
    };

    private final Binder dabCallback = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code == INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(DAB_CB);
                return true;
            }
            try {
                data.enforceInterface(DAB_CB);
                if (code == 2) {                   // notifyDabStationListInfoChanged(List<RadioDabStation>)
                    int n = data.readInt();
                    List<Station> out = new ArrayList<>();
                    for (int i = 0; i < n && i < 300; i++) if (data.readInt() != 0) out.add(readDabStation(data));
                    if (!out.isEmpty()) {
                        java.util.Collections.sort(out, (x, y) -> x.name.compareToIgnoreCase(y.name));
                        dabList = out;
                        dabListFrom = "servizio radio (elenco DAB)";
                        saveList("radio_dab_elenco", out);
                    }
                    Diag.put("radio.dab.elenco", out.size() + " stazioni");
                    // paese RDS dalle stazioni DAB (conferma o corregge il paese automatico dei loghi)
                    LogoPacks.noteStations(BAND_DAB, out);
                } else if (code == 3) {            // notifyDabMainInfoChanged(RadioDabStation)
                    if (data.readInt() != 0) {
                        dabCurrent = readDabStation(data);
                        rememberDab(dabCurrent);
                        bandFromCallback = BAND_DAB;
                    }
                } else if (code == 4) {            // notifyDabDLSChanged(char[])
                    char[] t = data.createCharArray();
                    dabText = t == null ? "" : new String(t).trim();
                }
            } catch (Exception e) {
                Diag.error("radio.dab.callback", e.toString());
            }
            if (reply != null) reply.writeNoException();
            if (code == 2 || code == 3) post(MediaClient.this::poll);
            return true;
        }
    };

    private volatile IBinder callbacksFor;

    private void registerRadioCallbacks() {
        IBinder b = radio.binder();
        if (b == null) return;
        try {
            Rpc.call(b, RAD, RAD_ADD_CB, p -> p.writeStrongBinder(radioCallback));
            callbacksFor = b;
            Diag.put("radio.callback", "registrato");
        } catch (Exception e) {
            Diag.error("radio.callback.registra", e.toString());
        }
        try {
            Rpc.call(b, RAD, RAD_ADD_DAB_CB, p -> p.writeStrongBinder(dabCallback));
            Rpc.call(b, RAD, RAD_DAB_QUERY_LIST, Rpc.NONE);
        } catch (Exception e) {
            Diag.error("radio.dab.registra", e.toString());
        }
    }

    // ------------------------------------------------------------------------------------------
    // Comandi
    // ------------------------------------------------------------------------------------------

    /** Passa alla sorgente precedente/successiva e la avvia. */
    public void cycleSource(int dir) {
        State s = last;
        List<Integer> list = s.sources;
        if (list.isEmpty()) return;
        int i = list.indexOf(s.source);
        int next = list.get(((i < 0 ? 0 : i) + dir + list.size()) % list.size());
        selectSource(next);
    }

    /** Mostra la sorgente e la avvia (play sulla sua sessione, come fa la home originale). */
    // --- musica online: funziona solo se l'auto ha la funzione attiva (come l'app Media originale) ---
    private static final String FEATURE_URI = "content://com.saicmotor.caradapter.featureprovider/feature/key/";
    private static final String FEATURE_ONLINE = "media_Onlinemusic___";
    private volatile String onlineFeature;      // valore della configurazione, null = non ancora letto / non leggibile
    private volatile boolean onlineFeatureRead;
    private volatile boolean onlineFailed;      // toccata ma non è partita: nascosta fino al riavvio del launcher

    /** "0" = funzione assente sull'auto: il servizio esiste ma non riproduce nulla, quindi il pulsante non si mostra. */
    /** 0.3.3: la musica Online di solito non c'è: l'opzione si mostra solo se la sorgente è comparsa. */
    public static volatile boolean onlinePossible;

    private boolean onlineAvailable() {
        if (!onlineFeatureRead) {
            onlineFeatureRead = true;
            try (android.database.Cursor q = ctx.getContentResolver().query(
                    android.net.Uri.parse(FEATURE_URI + FEATURE_ONLINE), null, null, null, null)) {
                if (q != null && q.moveToNext()) onlineFeature = q.getString(0);
            } catch (Throwable e) {
                Diag.error("media.online.funzione", e.toString());
            }
            Diag.put("media.online.funzione", onlineFeature == null ? "non leggibile" : onlineFeature + (("0".equals(onlineFeature)) ? " (assente)" : ""));
        }
        if (onlineFailed) return false;
        return !"0".equals(onlineFeature);
    }

    public void selectSource(int src) {
        chosen = src;
        if (src == SRC_RADIO) markRadioByUser();
        post(() -> {
            try {
                if (src == SRC_RADIO) {
                    // 0.3.1 (punti 4 e 10): la radio si avvia davvero (sessione radio dell'auto, ultima stazione), con nuovi
                    // tentativi a 1, 2 e 4 s se il servizio non è ancora pronto
                    startRadio("scelta Radio", 0);
                } else if (src == SRC_CARPLAY || src == SRC_AAUTO) {
                    remotePlay(true);
                } else {
                    Browser b = browserFor(src);
                    MediaController.TransportControls tc = b == null ? null : b.controls();
                    // Bluetooth: come l'app Media originale, prima si chiede all'auto la sorgente audio Bluetooth
                    // (fuoco audio con key_car_source_type = 5), altrimenti l'auto resta sulla radio
                    if (src == SRC_BT) requestBtSource();
                    if (tc != null) tc.play();
                    else Diag.put("media.avvio", sourceName(ctx, src) + ": sessione non collegata");
                    if (src == SRC_BT) checkBtStarted(tc != null, 1);
                    if (src == SRC_ONLINE) {
                        // se dopo qualche secondo non è partita, la funzione non c'è: si torna alla radio e si nasconde
                        bg.postDelayed(() -> {
                            if (!online.isPlaying()) {
                                onlineFailed = true;
                                Diag.put("media.online", "non parte: nascosta");
                                if (chosen == SRC_ONLINE) chosen = SRC_RADIO;
                                poll();
                            }
                        }, 6000);
                    }
                }
            } catch (Exception e) {
                Diag.error("media.avvio " + sourceName(ctx, src), e.toString());
            }
            poll();
        });
    }

    // --- Bluetooth: sorgente audio dell'auto, come l'app Media originale (com.saicmotor.media) ------------

    private android.media.AudioFocusRequest btFocus;

    /** Fuoco audio con la sorgente Bluetooth (AudioAttributes con bundle key_car_source_type = 5). */
    private void requestBtSource() {
        try {
            android.media.AudioManager am = (android.media.AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            android.os.Bundle b = new android.os.Bundle();
            b.putInt("key_car_source_type", 5);
            android.media.AudioAttributes.Builder ab = new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA);
            // addBundle è un'API di sistema (firma platform): si chiama per riflessione
            ab.getClass().getMethod("addBundle", android.os.Bundle.class).invoke(ab, b);
            if (btFocus != null) am.abandonAudioFocusRequest(btFocus);
            btFocus = new android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(ab.build())
                    .setWillPauseWhenDucked(false)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(fc -> {
                        if (fc == android.media.AudioManager.AUDIOFOCUS_LOSS && btFocus != null) {
                            try {
                                am.abandonAudioFocusRequest(btFocus);
                            } catch (Exception ignored) {
                            }
                            btFocus = null;
                        }
                    })
                    .build();
            int r = am.requestAudioFocus(btFocus);
            Diag.put("media.bt.sorgente", "richiesta sorgente Bluetooth: " + (r == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED ? "concessa"
                    : r == android.media.AudioManager.AUDIOFOCUS_REQUEST_DELAYED ? "in attesa" : "rifiutata"));
        } catch (Throwable e) {
            Diag.put("media.bt.sorgente", "errore " + e.getClass().getSimpleName());
        }
    }

    /**
     * Esito dopo 3 secondi: se il Bluetooth non è partito si ripiega sull'app Media originale (pagina Bluetooth),
     * che fa partire la musica come quando si sceglie la sorgente lì.
     */
    private void checkBtStarted(boolean sent, int attempt) {
        bg.postDelayed(() -> {
            boolean playing = bt.isPlaying();
            if (playing) {
                Diag.put("media.bt.esito", "partito (tentativo " + attempt + ")");
                return;
            }
            if (chosen != SRC_BT) return;   // nel frattempo l'utente ha scelto un'altra sorgente
            // 0.3.1 (punto 10c): spesso al primo colpo la richiesta della sorgente arriva prima che il Bluetooth sia pronto:
            // si riprova (sorgente + play, sessione ricollegata se serve) prima di aprire la pagina Bluetooth dell'app Media
            if (attempt < 3) {
                if (!bt.ready()) bt.retry(200);
                requestBtSource();
                MediaController.TransportControls tc = bt.controls();
                if (tc != null) tc.play();
                Diag.put("media.bt.esito", "non partito al tentativo " + attempt + ": riprovo" + (tc == null ? " (sessione non collegata)" : ""));
                checkBtStarted(tc != null, attempt + 1);
                return;
            }
            Diag.put("media.bt.esito", "non partito dopo " + attempt + " tentativi" + (sent ? "" : " (sessione non collegata)") + ": apro la pagina Bluetooth dell'app Media");
            try {
                Intent i = new Intent(Intent.ACTION_MAIN)
                        .setComponent(new android.content.ComponentName(com.mg4.launcher.apps.MgApps.MEDIA_PKG, com.mg4.launcher.apps.MgApps.MEDIA_CLS))
                        .putExtra("type", 2)   // MainActivity dell'app Media: type 2 = pagina Bluetooth
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(i);
            } catch (Exception e) {
                Diag.error("media.bt.app", e.toString());
            }
            poll();
        }, attempt == 1 ? 1500 : 2000);
    }

    // ---- 0.3.1: avvio vero della radio, con nuovi tentativi (punti 4 e 10b) --------------------------

    private static final long[] RADIO_RETRY_MS = {1000, 2000, 4000};
    /** 0.3.3 (SWI68 1.0.4 punto C1): all'accensione controlli per circa 49 s (1+2+3+5+8+10+10+10). */
    private static final long[] RADIO_RESUME_MS = {1000, 2000, 3000, 5000, 8000, 10_000, 10_000, 10_000};
    private static final long RADIO_READY_MAX_MS = 20_000;
    private volatile int radioRun;   // il tentativo più recente vince sui precedenti
    private volatile boolean radioPowerOn;
    private long[] radioWaits = RADIO_RETRY_MS;
    private long radioStartAt, radioReadyWaited;

    /**
     * 0.3.3: all'accensione il sintonizzatore può non essere ancora pronto. Prima si aspetta che il servizio radio sia
     * collegato e la sua sessione pronta (fino a 20 s, controllo ogni secondo), poi si avvia con i controlli lunghi.
     * Se nel frattempo l'utente sceglie un'altra sorgente la ripresa si ferma. Log radio.pronta e radio.tentativi.
     */
    private static final int RADIO_EXTRA_ROUNDS = 2;
    private static final long RADIO_ROUND_MAX_WAIT_MS = 60_000;
    private int radioRound;

    /** 0.3.5: attesa tra due giri: si riparte appena il servizio radio e la sua sessione sono pronti, al massimo dopo 60 s. */
    private void waitRadioRound(long since, int run, String why) {
        if (run != radioRun || chosen != SRC_RADIO) {
            Diag.put("radio.tentativi", "avvio RADIO: giro annullato, l'utente ha scelto un'altra sorgente");
            radioRound = 0;
            return;
        }
        long waited = SystemClock.uptimeMillis() - since;
        boolean ready = radio.binder() != null && radioMbs.ready();
        if (ready || waited >= RADIO_ROUND_MAX_WAIT_MS) {
            radioWaits = RADIO_RESUME_MS;
            startRadio(why, 0, true);
            return;
        }
        bg.postDelayed(() -> waitRadioRound(since, run, why), 2000);
    }

    private void waitRadioReady(String why, int run) {
        if (run != radioRun || chosen != SRC_RADIO) {
            Diag.put("radio.tentativi", "avvio RADIO: annullato, l'utente ha scelto un'altra sorgente");
            return;
        }
        long waited = SystemClock.uptimeMillis() - radioStartAt;
        boolean ready = radio.binder() != null && radioMbs.ready();
        if (ready || waited >= RADIO_READY_MAX_MS) {
            radioReadyWaited = waited;
            Diag.put("radio.pronta", (ready ? "pronta" : "non ancora pronta, provo lo stesso") + " dopo " + Math.round(waited / 1000.0)
                    + " s (servizio " + (radio.binder() != null ? "collegato" : "non collegato") + ", sessione " + (radioMbs.ready() ? "pronta" : "non pronta") + ")");
            radioWaits = RADIO_RESUME_MS;
            startRadio(why, 0, true);
            return;
        }
        bg.postDelayed(() -> waitRadioReady(why, run), 1000);
    }

    /**
     * Avvia la sessione radio dell'auto (focus audio e ultima stazione, come il play dell'app Radio). Se il servizio o la
     * sessione non sono ancora pronti, o la radio non suona, riprova dopo 1, 2 e 4 s; se un tentativo riesce non compare
     * nessun errore. I tentativi vanno nella diagnostica (radio.avvio).
     */
    private void startRadio(String why, int attempt) {
        if (attempt == 0 && radioPowerOn) {
            // ripresa all'accensione: prima si aspetta il sintonizzatore
            radioPowerOn = false;
            radioRound = 0;
            radioRun++;
            radioStartAt = SystemClock.uptimeMillis();
            waitRadioReady(why + " (accensione)", radioRun);
            return;
        }
        if (attempt == 0) radioWaits = RADIO_RETRY_MS;
        startRadio(why, attempt, false);
    }

    private void startRadio(String why, int attempt, boolean keepRun) {
        if (attempt == 0 && !keepRun) radioRun++;
        final int run = radioRun;
        final long[] waits = radioWaits;
        final boolean power = waits == RADIO_RESUME_MS;
        MediaController.TransportControls tc = radioMbs.controls();
        boolean service = radio.binder() != null;
        if (tc != null) {
            tc.play();
            if (fmCurrent == null && dabCurrent == null) registerRadioCallbacks();
        } else {
            radioMbs.retry(200);
        }
        String state = (service ? "servizio pronto" : "servizio non pronto") + ", " + (tc != null ? "play inviato" : "sessione non collegata");
        if (attempt >= waits.length) {
            Diag.put("radio.avvio", why + " · tentativo " + (attempt + 1) + ": " + state + " · " + (radioMbs.isPlaying() ? "in onda" : "non partita, smetto"));
            boolean on = radioMbs.isPlaying();
            if (power) Diag.put("radio.tentativi", "avvio RADIO: " + (on ? "riuscito" : "non riuscito") + " · giro " + (radioRound + 1)
                    + " · attesa servizio " + Math.round(radioReadyWaited / 1000.0) + " s · tentativi " + (attempt + 1) + " · totale "
                    + Math.round((SystemClock.uptimeMillis() - radioStartAt) / 1000.0) + " s · " + clock());
            // 0.3.5 (SWI68 1.0.6): un altro giro quando il servizio radio è pronto, o comunque dopo 60 s (al massimo 2 giri)
            if (power && !on && radioRound < RADIO_EXTRA_ROUNDS && chosen == SRC_RADIO && run == radioRun) {
                radioRound++;
                Diag.put("radio.tentativi", "avvio RADIO: nuovo giro " + (radioRound + 1) + " quando la radio è pronta (al massimo 60 s) · " + clock());
                waitRadioRound(SystemClock.uptimeMillis(), run, why);
            } else if (power) radioRound = 0;
            return;
        }
        Diag.put("radio.avvio", why + " · tentativo " + (attempt + 1) + ": " + state);
        bg.postDelayed(() -> {
            if (run != radioRun || chosen != SRC_RADIO) {
                if (power) Diag.put("radio.tentativi", "avvio RADIO: annullato, l'utente ha scelto un'altra sorgente · tentativi " + (attempt + 1));
                return;
            }
            if (radioMbs.isPlaying()) {
                Diag.put("radio.avvio", why + " · in onda al tentativo " + (attempt + 1));
                if (power) Diag.put("radio.tentativi", "avvio RADIO: riuscito · attesa servizio " + Math.round(radioReadyWaited / 1000.0)
                        + " s · tentativi " + (attempt + 1) + " · totale " + Math.round((SystemClock.uptimeMillis() - radioStartAt) / 1000.0) + " s");
                poll();
                return;
            }
            startRadio(why, attempt + 1, true);
        }, waits[attempt]);
    }

    // ---- 0.3.1: ultima sorgente usata davvero e ripresa all'accensione (punto 10a) ---------------------

    private int rememberCandidate;
    private long rememberSince;

    /** Una sorgente che suona per almeno 5 s diventa "l'ultima usata" (salvata, vale anche dopo lo spegnimento). */
    private void rememberSource(int active) {
        long now = android.os.SystemClock.uptimeMillis();
        if (active == 0) {
            rememberCandidate = 0;
            return;
        }
        if (active != rememberCandidate) {
            rememberCandidate = active;
            rememberSince = now;
            return;
        }
        if (now - rememberSince >= 5000 && sp.getInt("mediaUltimaSorgente", 0) != active) {
            sp.edit().putInt("mediaUltimaSorgente", active).apply();
            Diag.put("media.ultimaSorgente", sourceName(ctx, active));
        }
    }

    /** Telefono collegato in Bluetooth per l'audio (profilo A2DP sink) o sessione Bluetooth già pronta. */
    private boolean btAudioConnected() {
        try {
            android.bluetooth.BluetoothAdapter a = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (a != null && a.isEnabled() && a.getProfileConnectionState(11 /* A2DP_SINK */) == android.bluetooth.BluetoothProfile.STATE_CONNECTED) return true;
        } catch (Throwable ignored) {
        }
        MediaController c = bt.controller;
        return c != null && c.getMetadata() != null;
    }

    /**
     * Riprende l'ultima sorgente usata davvero, come l'app Media originale: solo se non suona già nulla (se l'auto ha
     * ripreso da sola, la si lascia). Se la sorgente non è disponibile (telefono non collegato, chiavetta assente) si
     * passa alla radio. Per CarPlay/Android Auto si aspetta il collegamento del telefono fino a 30 s.
     */
    // ---- 0.3.5 (SWI68 1.0.6, issue #43): ripresa a OGNI accensione, media.avvio sempre scritto ----------------------
    private static volatile MediaClient instanceNow;
    private static volatile String systemPowerOn;
    private static final android.os.Handler MAIN = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final long SYSTEM_WAIT_MS = 4 * 60_000L;
    private volatile String pendingPowerOn;
    private long lastPowerOn = Long.MIN_VALUE / 2;
    private String lastPowerOnWhy = "";

    private static String clock() {
        return new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date());
    }

    /**
     * Accensione vista dal sistema (BOOT_COMPLETED o servizio di accessibilità), anche se la schermata del launcher non
     * è ancora stata creata: la ripresa parte appena i servizi media sono avviati. Se in 4 minuti non partono,
     * media.avvio lo scrive comunque ("non eseguita").
     */
    public static void powerOnFromSystem(String why) {
        MediaClient m = instanceNow;
        if (m != null && m.bg != null) {
            m.onPowerOn(why);
            return;
        }
        if (systemPowerOn != null) return;
        systemPowerOn = why;
        MAIN.postDelayed(() -> {
            String w = systemPowerOn;
            if (w == null) return;
            systemPowerOn = null;
            Diag.put("media.avvio", "ripresa non eseguita: servizi media del launcher non partiti entro 4 min (" + w + ") · " + clock());
        }, SYSTEM_WAIT_MS);
    }

    private static String takeSystemPowerOn() {
        String w = systemPowerOn;
        systemPowerOn = null;
        return w;
    }

    /** Accensione in cui la ripresa non si fa: media.avvio lo dice lo stesso, con il motivo. */
    public static void powerOnSkipped(String why) {
        if (systemPowerOn != null) return;
        MediaClient m = instanceNow;
        if (m != null && android.os.SystemClock.uptimeMillis() - m.lastPowerOn < 5 * 60_000L) return;
        Diag.put("media.avvio", "ripresa non eseguita: " + why + " · " + clock());
    }

    /** Accensione (avvio del launcher, del sistema o risveglio): dopo 9 s si riprende l'ultima sorgente. */
    public void onPowerOn(String why) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastPowerOn < 30_000) {
            Diag.putQuiet("media.avvioDoppio", "\"" + why + "\" ignorata: ripresa già avviata " + (now - lastPowerOn) / 1000 + " s fa (" + lastPowerOnWhy + ")");
            return;
        }
        lastPowerOn = now;
        lastPowerOnWhy = why;
        if (bg == null) {
            pendingPowerOn = why;
            return;
        }
        Diag.put("media.avvio", why + " · ripresa tra 9 s · " + clock());
        bg.postDelayed(() -> resumeLastSource(why, 0), 9000);
    }

    private void resumeLastSource(String why, int waited) {
        int saved = sp.getInt("mediaUltimaSorgente", 0);
        int active = prevActive;
        if (active != 0) {
            if (chosen == 0) chosen = active;
            Diag.put("media.avvio", why + " · salvata " + sourceName(ctx, saved) + " · già in riproduzione " + sourceName(ctx, active) + ": lasciata · " + clock());
            BootLog.mark(ctx, "ripresa", "già in riproduzione");
            return;
        }
        if (chosen != 0) {
            Diag.put("media.avvio", why + " · salvata " + sourceName(ctx, saved) + " · l'utente ha già scelto " + sourceName(ctx, chosen) + " · " + clock());
            BootLog.mark(ctx, "ripresa", "scelta dell'utente");
            return;
        }
        int target;
        String note = "";
        switch (saved) {
            case SRC_BT:
                // 0.3.2 (punto 4): prima del play si aspetta il collegamento A2DP + AVRCP (eventi, al massimo 30 s)
                new BtWait(why).begin();
                return;
            case SRC_USB:
                target = usb.ready() ? SRC_USB : SRC_RADIO;
                if (target == SRC_RADIO) note = " (chiavetta non pronta)";
                break;
            case SRC_USB2:
                target = usb2.ready() && usbPortAvailable("usb2AvailableStatus") ? SRC_USB2 : SRC_RADIO;
                if (target == SRC_RADIO) note = " (chiavetta USB 2 non pronta)";
                break;
            case SRC_CARPLAY:
            case SRC_AAUTO:
                if (last.deviceType != 0) target = last.deviceType == DEV_AAUTO ? SRC_AAUTO : SRC_CARPLAY;
                else if (waited < 30000) {
                    bg.postDelayed(() -> resumeLastSource(why, waited + 5000), 5000);
                    return;
                } else {
                    target = SRC_RADIO;
                    note = " (telefono non collegato)";
                }
                break;
            case SRC_ONLINE:
                target = SRC_RADIO;
                note = " (musica online: si riparte dalla radio)";
                break;
            default:
                target = SRC_RADIO;
                break;
        }
        Diag.put("media.avvio", why + " · salvata " + (saved == 0 ? "nessuna" : sourceName(ctx, saved)) + " · ripresa "
                + sourceName(ctx, target) + note + " · attesa " + (waited / 1000) + " s · " + clock());
        BootLog.mark(ctx, "ripresa", sourceName(ctx, target));
        if (target == SRC_RADIO) radioPowerOn = true;
        boolean opened = radioOpenedFlag;
        selectSource(target);
        radioOpenedFlag = opened;   // la ripresa automatica non conta come "Radio aperta" (Controllo funzioni, punto 4)
    }

    // ---- 0.3.2 (punto 4): attesa del Bluetooth all'accensione -------------------------------------

    private static final int PROFILE_A2DP_SINK = 11, PROFILE_AVRCP_CONTROLLER = 12;
    private static final long BT_WAIT_MAX_MS = 30_000;

    private static int profileState(int profile) {
        try {
            android.bluetooth.BluetoothAdapter a = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
            if (a == null || !a.isEnabled()) return -1;
            return a.getProfileConnectionState(profile);
        } catch (Throwable e) {
            return -1;
        }
    }

    /**
     * Aspetta che il telefono sia collegato per l'audio (A2DP sink) e per i comandi/metadati (AVRCP controller), poi fa
     * partire il Bluetooth. Si sveglia a ogni cambio di collegamento (avviso di sistema) e comunque ogni secondo. Dopo
     * 30 s: con il solo A2DP prova lo stesso il Bluetooth, senza nessuno dei due passa alla radio. Log media.bt.attesa.
     */
    private final class BtWait extends android.content.BroadcastReceiver implements Runnable {
        final String why;
        final long t0 = SystemClock.elapsedRealtime();
        long a2dpAt = -1, avrcpAt = -1;
        int events;
        boolean done, registered;

        BtWait(String why) {
            this.why = why;
        }

        void begin() {
            try {
                IntentFilter f = new IntentFilter("android.bluetooth.a2dp-sink.profile.action.CONNECTION_STATE_CHANGED");
                f.addAction("android.bluetooth.avrcp-controller.profile.action.CONNECTION_STATE_CHANGED");
                f.addAction(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED);
                ctx.registerReceiver(this, f, null, bg);
                registered = true;
            } catch (Throwable e) {
                Diag.error("media.bt.attesa", "avvisi non disponibili: " + e.getClass().getSimpleName());
            }
            Diag.put("media.bt.attesa", why + " · in attesa di A2DP e AVRCP (al massimo 30 s)");
            bg.post(this);
        }

        @Override
        public void onReceive(Context c, Intent i) {
            events++;
            run();
        }

        @Override
        public void run() {
            if (done) return;
            long el = SystemClock.elapsedRealtime() - t0;
            if (chosen != 0 || prevActive != 0) {
                finish("l'utente ha scelto " + sourceName(ctx, chosen != 0 ? chosen : prevActive) + " o suona già qualcosa: niente play", 0);
                return;
            }
            if (a2dpAt < 0 && profileState(PROFILE_A2DP_SINK) == android.bluetooth.BluetoothProfile.STATE_CONNECTED) a2dpAt = el;
            if (avrcpAt < 0 && profileState(PROFILE_AVRCP_CONTROLLER) == android.bluetooth.BluetoothProfile.STATE_CONNECTED) avrcpAt = el;
            if (a2dpAt >= 0 && avrcpAt >= 0) {
                if (!bt.ready()) bt.retry(100);   // sessione Bluetooth ricollegata ora che il telefono c'è
                finish("A2DP e AVRCP collegati: play Bluetooth", SRC_BT);
            } else if (el >= BT_WAIT_MAX_MS) {
                if (a2dpAt >= 0) finish("dopo 30 s solo A2DP (AVRCP assente): provo comunque il Bluetooth", SRC_BT);
                else finish("telefono non collegato in 30 s: radio", SRC_RADIO);
            } else {
                bg.removeCallbacks(this);
                bg.postDelayed(this, 1000);
            }
        }

        private void finish(String what, int target) {
            done = true;
            bg.removeCallbacks(this);
            if (registered) {
                try {
                    ctx.unregisterReceiver(this);
                } catch (Throwable ignored) {
                }
            }
            long el = SystemClock.elapsedRealtime() - t0;
            Diag.put("media.bt.attesa", why + " · attesa " + Math.round(el / 100.0) / 10.0 + " s · A2DP "
                    + (a2dpAt < 0 ? "no" : "dopo " + a2dpAt / 1000 + " s") + " · AVRCP " + (avrcpAt < 0 ? "no" : "dopo " + avrcpAt / 1000 + " s")
                    + " · " + events + " avvisi · " + what);
            if (target != 0) {
                Diag.put("media.avvio", why + " · salvata BLUETOOTH · ripresa " + sourceName(ctx, target));
                // il play dopo un attimo: la sessione Bluetooth appena ricollegata deve essere pronta
                bg.postDelayed(() -> {
                    if (chosen == 0 && prevActive == 0) {
                        if (target == SRC_RADIO) radioPowerOn = true;
                        selectSource(target);
                    }
                }, target == SRC_BT && !bt.ready() ? 1500 : 0);
            }
        }
    }

    // ---- 0.3.2: USB 1 / USB 2, letture per il Controllo funzioni, elenco stazioni -----------------------

    /** Chiavetta presente nella porta: Settings.System usb1AvailableStatus / usb2AvailableStatus = 1 (app Media). */
    private boolean usbPortAvailable(String key) {
        try {
            return android.provider.Settings.System.getInt(ctx.getContentResolver(), key, 0) == 1;
        } catch (Throwable e) {
            return false;
        }
    }

    /** Porte con una chiavetta ("usb1", "usb2"); null se il servizio media non risponde. */
    public List<String> usbPortsSeen() {
        if (!usb.ready() && !usb2.ready()) return null;
        List<String> out = new ArrayList<>();
        if (usbPortAvailable("usb1AvailableStatus") || usb.isPlaying()) out.add("usb1");
        if (usbPortAvailable("usb2AvailableStatus") || usb2.isPlaying()) out.add("usb2");
        return out;
    }

    /** 0.3.6 (diagnostica): c'è una sessione media attiva di un'app AllGo (CarPlay / Android Auto)? */
    public static boolean allgoSessionPresent(Context c) {
        try {
            android.media.session.MediaSessionManager msm =
                    (android.media.session.MediaSessionManager) c.getSystemService(Context.MEDIA_SESSION_SERVICE);
            for (MediaController mc : msm.getActiveSessions(null)) if (mc.getPackageName().startsWith("com.allgo.")) return true;
        } catch (Throwable ignored) {
        }
        return false;
    }

    public boolean radioLinked() {
        return radio.binder() != null || radioMbs.ready();
    }

    public boolean mediaLinked() {
        return usb.ready() || usb2.ready() || online.ready();
    }

    public boolean btLinked() {
        return bt.ready();
    }

    /** Stato del collegamento Bluetooth audio: 2 collegato, 0 no, -1 Bluetooth spento. */
    public int btState() {
        return profileState(PROFILE_A2DP_SINK);
    }

    /** Servizio Allgo (CarPlay/Android Auto): null se non installato. */
    public Boolean projectionLinked() {
        try {
            ctx.getPackageManager().getPackageInfo("com.allgo.rui", 0);
        } catch (Exception e) {
            return null;
        }
        return rui.binder() != null;
    }

    private volatile String fmListFrom = "stazioni salvate", dabListFrom = "stazioni salvate";

    /**
     * 0.3.3 (da SWI69 0.4.4, EH32): il servizio radio non manda l'elenco DAB ai client esterni e l'elenco FM
     * pre-scansionato resta nel database privato dell'app Radio. Ogni stazione DAB sintonizzata entra nell'elenco e viene
     * ricordata, come per l'FM (nomi RDS e risultati delle ricerche).
     */
    private void rememberDab(Station d) {
        if (d == null || d.name.trim().isEmpty() || d.sid == 0) return;
        List<Station> cur = dabList;
        for (Station st : cur) if (st.sid == d.sid && st.name.equalsIgnoreCase(d.name)) return;
        List<Station> out = new ArrayList<>(cur);
        out.add(d);
        java.util.Collections.sort(out, (x, y) -> x.name.compareToIgnoreCase(y.name));
        if (out.size() > 300) return;
        dabList = out;
        saveList("radio_dab_elenco", out);
        dabListFrom = "stazioni DAB ascoltate";
    }
    private String listNote = "", sourcesNote = "";

    private void noteList(int band, String from, int n) {
        String txt = "fonte " + from + " · " + n + " stazioni (" + (band == BAND_DAB ? "DAB" : "FM") + ")";
        if (!txt.equals(listNote)) {
            listNote = txt;
            Diag.put("radio.elenco", txt);
        }
    }

    /**
     * 0.3.2 (punto 7): rilegge gli elenchi dal servizio radio (in onResume e quando si torna dall'app Radio): DAB dal
     * servizio, callback FM di nuovo registrate. L'elenco FM completo resta dentro l'app Radio (nessuna API per leggerlo):
     * arriva solo dal risultato di una ricerca, dalle stazioni trovate/ascoltate o, se vuoto, dai preferiti.
     */
    public void refreshRadioLists(String why) {
        post(() -> {
            IBinder b = radio.binder();
            if (b == null) {
                Diag.put("radio.elenco.aggiorna", why + " · servizio radio non collegato");
                return;
            }
            if (b != callbacksFor) registerRadioCallbacks();
            else {
                try {
                    Rpc.call(b, RAD, RAD_DAB_QUERY_LIST, Rpc.NONE);
                } catch (Exception e) {
                    Diag.error("radio.dab.elenco", e.toString());
                }
            }
            Diag.put("radio.elenco.aggiorna", why + " · FM " + fmList.size() + " · DAB " + dabList.size() + " · preferiti " + favorites.size());
            poll();
        });
    }

    // ---- 0.3.2 (punto 6): tasti al volante con la USB 2 -----------------------------------------------

    private volatile long wheelAt;

    /** 0.3.3: la USB 2 è la sorgente in riproduzione. */
    public boolean usb2Playing() {
        return prevActive == SRC_USB2 && usb2.isPlaying();
    }

    /** 0.3.3: avanti/indietro subito alla sessione della USB 2 (dal listener dei tasti multimediali). */
    public void skipUsb2(boolean forward) {
        post(() -> {
            MediaController.TransportControls tc = usb2.controls();
            if (tc == null) {
                Diag.put("media.tastiVolante.instradamento", (forward ? "avanti" : "indietro") + " · USB 2: sessione non collegata");
                return;
            }
            if (forward) tc.skipToNext();
            else tc.skipToPrevious();
            Diag.put("media.tastiVolante.instradamento", (forward ? "avanti" : "indietro") + " · USB 2: mandato alla sessione USB 2 (tasto multimediale)");
            bg.postDelayed(this::poll, 800);
        });
    }

    /**
     * Tasto avanti/indietro al volante visto (avviso com.saic.keyevent.hardkey.report). Se suona la USB 2, dopo 0,8 s si
     * controlla se il brano è cambiato da solo; se no, si manda avanti/indietro alla sessione della USB 2 (sul sistema il
     * tasto finisce alla sessione della USB 1). Con le altre sorgenti non si fa nulla: ci pensa il sistema.
     */
    public void onWheelSkip(boolean forward) {
        long now = SystemClock.uptimeMillis();
        if (now - wheelAt < 400) return;   // doppio avviso dello stesso tasto
        wheelAt = now;
        post(() -> {
            int active = prevActive;
            if (active != SRC_USB2) {
                Diag.put("media.tastiVolante.instradamento", (forward ? "avanti" : "indietro") + " · sorgente " + sourceName(ctx, active)
                        + ": gestito dal sistema");
                return;
            }
            MediaController c = usb2.controller;
            MediaMetadata m = c == null ? null : c.getMetadata();
            String before = m == null ? "" : nz(m.getString(MediaMetadata.METADATA_KEY_TITLE));
            bg.postDelayed(() -> {
                MediaController c2 = usb2.controller;
                MediaMetadata m2 = c2 == null ? null : c2.getMetadata();
                String after = m2 == null ? "" : nz(m2.getString(MediaMetadata.METADATA_KEY_TITLE));
                if (!after.equals(before)) {
                    Diag.put("media.tastiVolante.instradamento", (forward ? "avanti" : "indietro") + " · USB 2: brano già cambiato dal sistema");
                    return;
                }
                MediaController.TransportControls tc = usb2.controls();
                if (tc == null) {
                    Diag.put("media.tastiVolante.instradamento", (forward ? "avanti" : "indietro") + " · USB 2: sessione non collegata");
                    return;
                }
                if (forward) tc.skipToNext();
                else tc.skipToPrevious();
                Diag.put("media.tastiVolante.instradamento", (forward ? "avanti" : "indietro") + " · USB 2: inoltrato alla sessione USB 2");
                bg.postDelayed(this::poll, 800);
            }, 800);
        });
    }

    /**
     * Cambia banda (FM / DAB) come l'app Radio: azione "sorgente successiva/precedente" della sessione,
     * che riprende l'ultima stazione ascoltata su quella banda. Ordine delle bande: DAB, FM, AM.
     */
    // --- ripresa automatica: dopo una notifica il sistema a volte rimette la radio al posto della musica ---
    private static final long HIJACK_WINDOW_MS = 2500, RESUME_DELAY_MS = 2500, RADIO_USER_MS = 6000;
    private int prevActive;                    // sorgente in riproduzione al giro precedente
    private int lastMedia;                     // ultima sorgente musicale in riproduzione (BT, USB, Online)
    private long lastMediaAt;                  // quando era ancora in riproduzione
    private volatile long radioUserAt;         // ultimo comando radio dato dall'utente dal launcher
    private boolean resumePending;
    private final java.util.ArrayDeque<Long> resumes = new java.util.ArrayDeque<>();

    private Browser musicBrowser(int src) {
        return src == SRC_BT ? bt : src == SRC_USB ? usb : src == SRC_USB2 ? usb2 : src == SRC_ONLINE ? online : null;
    }

    /** L'utente ha scelto o comandato la radio dal launcher: in quel caso la radio non va mai "corretta". */
    private void markRadioByUser() {
        radioUserAt = android.os.SystemClock.uptimeMillis();
        radioOpenedFlag = true;
    }

    private static volatile boolean radioOpenedFlag;

    /** 0.3.5 (punto 4): la Radio è stata aperta dall'utente (sorgente Radio scelta nel launcher o app Radio davanti). */
    public static boolean radioOpened() {
        return radioOpenedFlag;
    }

    public static void noteRadioAppOpened() {
        radioOpenedFlag = true;
    }

    /** Chiamata a ogni giro di poll(), sul thread di lavoro. */
    private void checkHijack(int active) {
        long now = android.os.SystemClock.uptimeMillis();
        if (active == SRC_BT || active == SRC_USB || active == SRC_USB2 || active == SRC_ONLINE) {
            lastMedia = active;          // vale anche se la musica è partita dall'app di sistema
            lastMediaAt = now;
        }
        boolean radioJustStarted = active == SRC_RADIO && prevActive != SRC_RADIO;
        prevActive = active;
        if (!radioJustStarted || resumePending || lastMedia == 0) return;
        Browser b = musicBrowser(lastMedia);
        if (b == null) return;
        // la musica suonava fino a un attimo fa, ora è in pausa e la radio è partita senza un comando dal launcher
        if (now - lastMediaAt > HIJACK_WINDOW_MS) return;
        if (now - radioUserAt < RADIO_USER_MS) return;
        if (chosen == SRC_RADIO && now - radioUserAt < 60_000) return;
        final int src = lastMedia;
        resumePending = true;
        bg.postDelayed(() -> resumeIfStillHijacked(src), RESUME_DELAY_MS);
    }

    private void resumeIfStillHijacked(int src) {
        resumePending = false;
        Browser b = musicBrowser(src);
        String what = sourceName(ctx, src) + " dopo cambio a RADIO";
        if (b == null || !radioMbs.isPlaying() || !b.isPaused()) return;   // risolto da solo o cambiato dall'utente
        if (android.os.SystemClock.uptimeMillis() - radioUserAt < RADIO_USER_MS) return;
        long now = android.os.SystemClock.uptimeMillis();
        while (!resumes.isEmpty() && now - resumes.peekFirst() > 60_000) resumes.pollFirst();
        if (resumes.size() >= 2) {
            Diag.put("media.ripresaAuto", what + " (saltata: già 2 riprese nell'ultimo minuto)");
            return;
        }
        resumes.addLast(now);
        MediaController.TransportControls tc = b.controls();
        if (tc == null) {
            Diag.put("media.ripresaAuto", what + " (fallita: sessione non collegata)");
            return;
        }
        tc.play();   // stesso comando di selectSource(): a mano funziona
        chosen = src;
        Diag.put("media.ripresaAuto", what + " (inviata)");
        bg.postDelayed(() -> {
            Diag.put("media.ripresaAuto", what + (b.isPlaying() ? " (riuscita)" : " (fallita)"));
            poll();
        }, 3000);
    }

    public void switchBand(int band) {
        chosen = SRC_RADIO;
        markRadioByUser();
        post(() -> {
            try {
                int cur = last.radioBand;
                MediaController.TransportControls tc = radioMbs.controls();
                if (tc == null) throw new RemoteException("sessione radio non collegata");
                if (cur == band) {
                    tc.play();
                } else {
                    String action;
                    if (band == BAND_FM) action = cur == BAND_DAB ? ACTION_NEXT_BAND : ACTION_PREV_BAND;
                    else action = cur == BAND_FM ? ACTION_PREV_BAND : ACTION_NEXT_BAND;
                    tc.sendCustomAction(action, null);
                    bandFromCallback = band;
                }
                Diag.put("radio.banda.scelta", cur + " → " + band);
            } catch (Exception e) {
                Diag.error("radio.banda", e.toString());
            }
            poll();
        });
    }

    /** Sintonizza una stazione dei preferiti e passa alla radio. */
    public void tune(Station st) {
        chosen = SRC_RADIO;
        markRadioByUser();
        post(() -> {
            try {
                IBinder b = radio.binder();
                if (b == null) throw new RemoteException("servizio radio non collegato");
                if (st.band == BAND_DAB) {
                    Station d = st;
                    if (d.sid == 0) for (Station x : dabList) if (x.name.equalsIgnoreCase(st.name)) { d = x; break; }
                    Station dd = d;
                    Rpc.call(b, RAD, RAD_DAB_TUNE, p -> writeDabStation(p, dd));
                    bandFromCallback = BAND_DAB;
                } else {
                    Rpc.call(b, RAD, RAD_TUNE, p -> {
                        p.writeInt(1);             // non null
                        p.writeInt(st.khz);
                        p.writeInt(0);
                        p.writeInt(st.band);
                        p.writeString(null);       // RadioRds assente
                    });
                    bandFromCallback = st.band;
                }
                Diag.put("radio.sintonia", st.label());
            } catch (Exception e) {
                Diag.error("radio.sintonia", e.toString());
            }
            poll();
        });
    }

    // ---- elenco stazioni ---------------------------------------------------------------------

    private List<Station> loadList(String key) {
        List<Station> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp.getString(key, "[]"));
            for (int i = 0; i < a.length(); i++) {
                Station st = Station.fromJson(a.getJSONObject(i));
                st.key = StationLogos.key(st.band, st.sid, st.khz, st.name);
                out.add(st);
            }
        } catch (Exception e) {
            Diag.error("radio.elenco", e.toString());
        }
        return out;
    }

    private void saveList(String key, List<Station> list) {
        JSONArray a = new JSONArray();
        try {
            for (Station st : list) a.put(st.toJson());
        } catch (Exception e) {
            Diag.error("radio.elenco.salva", e.toString());
        }
        sp.edit().putString(key, a.toString()).apply();
    }

    /** Unisce stazioni FM all'elenco (stessa frequenza = stessa stazione); replace = risultato di una ricerca completa. */
    private void mergeFm(List<Station> found, boolean replace) {
        java.util.Map<Integer, Station> byKhz = new java.util.TreeMap<>();
        if (!replace) for (Station st : fmList) byKhz.put(st.khz, st);
        for (Station st : found) {
            if (st.khz <= 0 || st.band == BAND_DAB) continue;
            Station old = byKhz.get(st.khz);
            if (old != null && st.name.isEmpty()) st.name = old.name;
            if (replace && old == null) {
                for (Station prev : fmList) if (prev.khz == st.khz && st.name.isEmpty()) st.name = prev.name;
            }
            st.band = BAND_FM;
            st.key = StationLogos.key(BAND_FM, 0, st.khz, st.name);
            byKhz.put(st.khz, st);
        }
        List<Station> out = new ArrayList<>(byKhz.values());
        fmList = out;
        saveList("radio_fm_elenco", out);
        Diag.put("radio.fm.elenco", out.size() + " stazioni");
    }

    /** Ogni stazione FM ascoltata con il suo nome RDS entra nell'elenco (anche senza ricerca). */
    private void rememberFm(int khz, String name) {
        rememberFm(khz, name, false);
    }

    /** allowNoName: la stazione entra anche senza nome (trovata dalla ricerca avanti/indietro). */
    private void rememberFm(int khz, String name, boolean allowNoName) {
        for (Station st : fmList) {
            if (st.khz == khz) {
                if (st.name.isEmpty() && !name.isEmpty()) {
                    st.name = name;
                    st.key = StationLogos.key(BAND_FM, 0, khz, name);
                    saveList("radio_fm_elenco", fmList);
                }
                return;
            }
        }
        if (name.isEmpty() && !allowNoName) return;
        Station st = new Station();
        st.band = BAND_FM;
        st.khz = khz;
        st.name = name;
        List<Station> one = new ArrayList<>();
        one.add(st);
        mergeFm(one, false);
    }

    /** FM: ricerca completa delle stazioni (scan dell'app Radio). DAB: aggiorna l'elenco dal servizio. */
    public void searchStations() {
        markRadioByUser();
        int band = last.radioBand;
        post(() -> {
            try {
                IBinder b = radio.binder();
                if (b == null) throw new RemoteException("servizio radio non collegato");
                if (band == BAND_DAB) {
                    Rpc.call(b, RAD, RAD_DAB_QUERY_LIST, Rpc.NONE);
                } else {
                    scanning = true;
                    scanUntil = SystemClock.uptimeMillis() + 120_000;
                    Rpc.call(b, RAD, RAD_SCAN, Rpc.NONE);
                }
                Diag.put("radio.ricerca", "richiesta, banda " + band);
            } catch (Exception e) {
                scanning = false;
                Diag.error("radio.ricerca", e.toString());
            }
            poll();
        });
    }

    /** Aggiunge o toglie dai preferiti una stazione dell'elenco. @return true se ora è tra i preferiti */
    public boolean toggleFavorite(Station st) {
        List<Station> list = new ArrayList<>(favorites);
        for (int i = 0; i < list.size(); i++) {
            Station f = list.get(i);
            boolean same = st.band == BAND_DAB ? f.band == BAND_DAB && (f.sid == st.sid || f.key.equals(st.key))
                    : f.band != BAND_DAB && f.khz == st.khz;
            if (same) {
                list.remove(i);
                saveFavorites(list);
                post(this::poll);
                return false;
            }
        }
        Station c = new Station();
        c.band = st.band;
        c.khz = st.khz;
        c.sid = st.sid;
        c.eid = st.eid;
        c.index = st.index;
        c.pty = st.pty;
        c.type = st.type;
        c.channel = st.channel;
        c.name = st.name;
        c.ensemble = st.ensemble;
        c.key = st.key;
        c.logo = st.logo;
        list.add(c);
        saveFavorites(list);
        post(this::poll);
        return true;
    }

    /** true se la stazione dell'elenco è già tra i preferiti. */
    public boolean isFavorite(Station st) {
        for (Station f : favorites) {
            if (st.band == BAND_DAB ? f.band == BAND_DAB && (f.sid == st.sid || f.key.equals(st.key))
                    : f.band != BAND_DAB && f.khz == st.khz) return true;
        }
        return false;
    }

    // ---- preferiti radio del launcher ------------------------------------------------------

    private void loadFavorites() {
        List<Station> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp.getString("radio_preferiti", "[]"));
            for (int i = 0; i < a.length(); i++) {
                Station st = Station.fromJson(a.getJSONObject(i));
                st.key = StationLogos.key(st.band, st.sid, st.khz, st.name);
                out.add(st);
            }
        } catch (Exception e) {
            Diag.error("radio.preferiti", e.toString());
        }
        favorites = out;
    }

    private void saveFavorites(List<Station> list) {
        JSONArray a = new JSONArray();
        try {
            for (Station st : list) a.put(st.toJson());
        } catch (Exception e) {
            Diag.error("radio.preferiti.salva", e.toString());
        }
        sp.edit().putString("radio_preferiti", a.toString()).apply();
        favorites = list;
        Diag.put("radio.preferiti", list.size() + " stazioni");
    }

    /**
     * Aggiunge ai preferiti la stazione in onda, o la toglie se c'è già.
     * @return true se ora è tra i preferiti, false se è stata tolta, null se non c'è una stazione valida
     */
    public Boolean toggleCurrentFavorite() {
        State s = last;
        if (s.source != SRC_RADIO || s.stationKey.isEmpty()) return null;
        List<Station> list = new ArrayList<>(favorites);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).key.equals(s.stationKey)) {
                list.remove(i);
                saveFavorites(list);
                post(this::poll);
                return false;
            }
        }
        Station st;
        if (s.radioBand == BAND_DAB) {
            Station d = currentDab(s.title);
            if (d == null || d.sid == 0) return null;
            st = new Station();
            st.band = BAND_DAB;
            st.pty = d.pty;
            st.name = d.name.isEmpty() ? s.title : d.name;
            st.sid = d.sid;
            st.ensemble = d.ensemble;
            st.eid = d.eid;
            st.index = d.index;
            st.type = d.type;
            st.channel = d.channel;
        } else {
            Station f = fmCurrent;
            if (f == null || f.khz <= 0) return null;
            st = new Station();
            st.band = f.band;
            st.khz = f.khz;
            st.name = s.title.equals(s.radioFreq + " " + s.radioUnit) ? "" : s.title;
        }
        st.key = s.stationKey;
        st.logo = s.logo;
        list.add(st);
        saveFavorites(list);
        post(this::poll);
        return true;
    }

    public void removeFavorite(Station st) {
        List<Station> list = new ArrayList<>(favorites);
        list.remove(st);
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).key.equals(st.key)) list.remove(i);
        saveFavorites(list);
        post(this::poll);
    }

    private void refreshLogosSoon() {
        post(this::poll);
    }

    // ---- play / pausa / salto ------------------------------------------------------------------

    public void playPause() {
        if (last.source == SRC_RADIO) markRadioByUser();
        post(() -> {
            State s = last;
            try {
                if (s.source == SRC_CARPLAY || s.source == SRC_AAUTO) {
                    remotePlay(!remote.isPlaying());
                } else if (s.source == SRC_RADIO && !s.playing) {
                    // 0.3.1: Play sulla radio = avvio vero della radio, come la scelta della sorgente
                    chosen = SRC_RADIO;
                    startRadio("tasto Play", 0);
                } else {
                    Browser b = browserFor(s.source);
                    MediaController.TransportControls tc = b == null ? null : b.controls();
                    if (s.source == SRC_BT && !s.playing) requestBtSource();
                    if (tc != null) {
                        if (s.playing) tc.pause();
                        else tc.play();
                    }
                    if (s.source == SRC_BT) btCommandResult(s.playing ? "pausa" : "play", tc != null, !s.playing);
                }
            } catch (Exception e) {
                Diag.error("media.play", e.toString());
            }
            poll();
        });
    }

    /** 0.3.1: esito dei comandi Bluetooth della card media (media.bt.comandi); wantPlaying null = salto brano. */
    private void btCommandResult(String what, boolean sent, Boolean wantPlaying) {
        if (!sent) {
            Diag.put("media.bt.comandi", what + " · NON inviato (sessione Bluetooth non collegata)");
            bt.retry(200);
            return;
        }
        String title = last.title;
        bg.postDelayed(() -> {
            String res;
            if (wantPlaying != null) res = bt.isPlaying() == wantPlaying ? "ok" : "nessun effetto (" + bt.stateName() + ")";
            else {
                MediaController c = bt.controller;
                MediaMetadata m = c == null ? null : c.getMetadata();
                String now = m == null ? "" : nz(m.getString(MediaMetadata.METADATA_KEY_TITLE));
                res = now.isEmpty() ? "inviato (il telefono non manda il titolo)" : !now.equals(title) ? "ok (brano cambiato)" : "inviato, brano uguale";
            }
            Diag.put("media.bt.comandi", what + " · " + res);
            poll();
        }, 1200);
    }

    public void skip(boolean forward) {
        if (last.source == SRC_RADIO) markRadioByUser();
        post(() -> {
            State s = last;
            try {
                if (s.source == SRC_CARPLAY || s.source == SRC_AAUTO) {
                    // CarPlay / Android Auto: sempre i tasti del servizio RemoteUI, come la home originale
                    if (!remoteKey(forward ? "avanti" : "indietro", forward ? 7 : 8, forward ? 11 : 12)) {
                        MediaController.TransportControls tc = remote.controls();
                        if (tc != null) {
                            if (forward) tc.skipToNext();
                            else tc.skipToPrevious();
                            Diag.put("carplay.tasto", (forward ? "avanti" : "indietro") + " · RemoteUI non collegato: MediaController");
                        }
                    }
                } else {
                    Browser b = browserFor(s.source);
                    MediaController.TransportControls tc = b == null ? null : b.controls();
                    if (tc != null) {
                        if (forward) tc.skipToNext();
                        else tc.skipToPrevious();
                    }
                    if (s.source == SRC_BT) btCommandResult(forward ? "avanti" : "indietro", tc != null, null);
                }
            } catch (Exception e) {
                Diag.error("media.salto", e.toString());
            }
            poll();
        });
    }

    /**
     * Play o pausa per CarPlay / Android Auto. Come la home originale (CPAAModel.mediaControlCPAA) si manda sempre il
     * tasto del servizio RemoteUI: 20 premuto / 21 rilasciato, un tasto unico che commuta. Per questo si preme solo se
     * lo stato attuale della sessione è diverso da quello voluto (altrimenti scegliendo la sorgente CarPlay la musica
     * andrebbe in pausa). Il MediaController di Android si usa solo se il servizio RemoteUI non è collegato: molti
     * adattatori wireless (es. AutoKit) lo ignorano.
     */
    private void remotePlay(boolean play) throws RemoteException {
        boolean playingNow = remote.isPlaying();
        if (playingNow == play) {
            Diag.put("carplay.tasto", (play ? "play" : "pausa") + " · già " + remote.stateName() + ": nessun tasto");
            return;
        }
        if (remoteKey(play ? "play" : "pausa", 20, 21)) return;
        MediaController.TransportControls tc = remote.controls();
        if (tc != null) {
            if (play) tc.play();
            else tc.pause();
            Diag.put("carplay.tasto", (play ? "play" : "pausa") + " · RemoteUI non collegato: MediaController");
        } else {
            Diag.put("carplay.tasto", (play ? "play" : "pausa") + " · né RemoteUI né sessione collegati");
        }
    }

    /** Tasto media del servizio RemoteUI (sendMediaPlayControlKey, transazione 18): premuto e poi rilasciato. */
    private boolean remoteKey(String what, int down, int up) {
        IBinder r = rui.binder();
        if (r == null) return false;
        try {
            Integer a = Rpc.call(r, RUI, RUI_MEDIA_KEY, p -> p.writeInt(down), Parcel::readInt);
            Integer b = Rpc.call(r, RUI, RUI_MEDIA_KEY, p -> p.writeInt(up), Parcel::readInt);
            Diag.put("carplay.tasto", what + " · sendMediaPlayControlKey " + down + "/" + up + " → " + a + "/" + b);
            return true;
        } catch (Exception e) {
            Diag.error("carplay.tasto", what + ": " + e);
            return false;
        }
    }

    /** Apre CarPlay o Android Auto a schermo intero. */
    public boolean openProjection() {
        int type = last.deviceType;
        Intent i = new Intent(Intent.ACTION_MAIN);
        if (type == DEV_AAUTO) {
            i.setComponent(new ComponentName("com.allgo.app.androidauto", "com.allgo.app.androidauto.ProjectionActivity"));
        } else {
            i.setComponent(new ComponentName("com.allgo.carplay.service", "com.allgo.carplay.service.CarPlayActivity"));
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
            return true;
        } catch (Exception e) {
            Diag.error("carplay.apri", e.toString());
            return false;
        }
    }

    /** Apre l'app di navigazione di CarPlay/AA (launchApp 2, tipo dispositivo). */
    public void openRemoteNavigation() {
        int type = last.deviceType;
        post(() -> {
            try {
                Rpc.call(rui.binder(), RUI, RUI_LAUNCH_APP, p -> {
                    p.writeInt(2);
                    p.writeInt(type);
                }, Parcel::readInt);
            } catch (Exception e) {
                Diag.error("carplay.navigazione", e.toString());
            }
        });
    }

    // ------------------------------------------------------------------------------------------
    // CarPlay / Android Auto: indicazioni di navigazione dalla sessione remota
    // ------------------------------------------------------------------------------------------

    private final MediaController.Callback remoteCallback = new MediaController.Callback() {
        @Override
        public void onSessionEvent(String event, Bundle extras) {
            if ("ACTION_CARPLAY_NAVI_METADATA".equals(event) && extras != null) {
                tbt = extras;
                Diag.put("carplay.navigazione", extras.toString());
            }
        }

        @Override
        public void onExtrasChanged(Bundle extras) {
            if (extras != null && extras.containsKey("IS_TBT_ACTIVE")) tbt = extras;
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            post(MediaClient.this::poll);
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            post(MediaClient.this::poll);
        }

        @Override
        public void onSessionDestroyed() {
            remote.retry(5000);
        }
    };
}
