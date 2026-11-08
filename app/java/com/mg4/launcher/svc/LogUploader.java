package com.mg4.launcher.svc;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/**
 * Invio automatico dei log nel repository privato dei log su GitHub (API "contents", un file per invio):
 *   SWI132/<codice installazione>/<aaaa-mm-gg>/<hh-mm-ss>.txt
 * All'avvio (dopo 90 s, quando i servizi dell'auto hanno risposto), ogni 30 minuti e con "Salva log".
 * Senza rete i log restano in coda (al massimo 20) e partono al primo invio riuscito.
 * 0.3.2 (punto 5): con viaggi brevi non arrivava nulla (primo invio a 90 s spesso senza rete, poi pausa comune e nuovo
 * tentativo solo dopo 30 minuti). Ora: la coda si rimanda già 45 s dopo l'accensione; dopo un errore si riprova a 2, 4,
 * 8 e 15 minuti; allo spegnimento dello schermo (auto spenta) il log si mette in coda subito, così parte all'accensione
 * dopo. La riga log.stato (salvata, vale anche tra un'accensione e l'altra) dice ultimo invio riuscito, ultimo errore e
 * quanti log aspettano in coda.
 * Il token NON è nel codice: build.sh genera LogConfig da tools/log-token.txt (escluso da git).
 */
public final class LogUploader {
    private static final String REPO = "ernestomicco2026-cell/mg4-launcher-logs";
    private static final String FOLDER = "SWI132";
    private static final long FIRST_DELAY_MS = 90_000, PERIOD_MS = 30 * 60_000, QUEUE_DELAY_MS = 45_000;
    private static final long[] RETRY_MS = {2 * 60_000L, 4 * 60_000L, 8 * 60_000L, 15 * 60_000L};
    private int retries;
    private static final int MAX_QUEUE = 20;
    private static final int MAX_BYTES = 400_000;

    private static volatile LogUploader instance;
    private static volatile String status = "";

    private final Context ctx;
    private final SharedPreferences sp;
    private final HandlerThread thread = new HandlerThread("mg4-log-upload");
    private Handler bg;

    public LogUploader(Context c) {
        ctx = c.getApplicationContext();
        sp = ctx.getSharedPreferences("mg4launcher", Context.MODE_PRIVATE);
        instance = this;
    }

    /** true se questa versione è stata compilata con il token del repository dei log. */
    public static boolean configured() {
        return LogConfig.token().length() > 10;
    }

    public boolean enabled() {
        return sp.getBoolean("log_invio", true);
    }

    public void setEnabled(boolean on) {
        sp.edit().putBoolean("log_invio", on).apply();
        if (on) requestNow();
    }

    /** Stato da mostrare nelle impostazioni e nel Controllo funzioni (testo di log.stato). */
    public static String status() {
        LogUploader u = instance;
        return u == null ? status : u.stateLine();
    }

    private static String now(String fmt) {
        return new SimpleDateFormat(fmt, Locale.ITALY).format(new Date());
    }

    private int queued() {
        File[] q = queueDir().listFiles();
        return q == null ? 0 : q.length;
    }

    /** log.stato: ultimo invio riuscito, ultimo errore, coda. Salvato nelle preferenze (resta tra le accensioni). */
    private String stateLine() {
        String ok = sp.getString("logUltimoOk", ""), err = sp.getString("logUltimoErrore", "");
        long okAt = sp.getLong("logUltimoOkAlle", 0), errAt = sp.getLong("logUltimoErroreAlle", 0);
        String last = errAt > okAt ? "ultimo tentativo non riuscito" : okAt > 0 ? "ultimo tentativo riuscito" : "nessun invio finora";
        return last + " · ultimo invio riuscito " + (ok.isEmpty() ? "mai" : ok) + " · ultimo errore " + (err.isEmpty() ? "nessuno" : err)
                + " · in coda " + queued();
    }

    private void writeState() {
        Diag.put("log.stato", stateLine());
    }

    private void noteOk() {
        sp.edit().putString("logUltimoOk", now("dd/MM HH:mm")).putLong("logUltimoOkAlle", System.currentTimeMillis()).apply();
        retries = 0;
        writeState();
    }

    private void noteError(String why) {
        sp.edit().putString("logUltimoErrore", now("dd/MM HH:mm") + " " + why).putLong("logUltimoErroreAlle", System.currentTimeMillis()).apply();
        writeState();
        // nuovo tentativo presto, senza aspettare i 30 minuti (viaggi brevi)
        if (retries < RETRY_MS.length && bg != null) {
            long wait = Math.max(RETRY_MS[retries++], Offline.waitMs());
            bg.removeCallbacks(retry);
            bg.postDelayed(retry, wait);
            Diag.put("log.riprova", "tra " + wait / 60_000 + " min (tentativo " + retries + " di " + RETRY_MS.length + ")");
        }
    }

    private final Runnable retry = () -> {
        if (queued() > 0) flushQueueTry("nuovo tentativo");
        else uploadOnce();
    };

    /** Codice anonimo di questa installazione (non è il numero di telaio). */
    public String installId() {
        String id = sp.getString("log_id", "");
        if (id.isEmpty()) {
            id = UUID.randomUUID().toString().substring(0, 8);
            sp.edit().putString("log_id", id).apply();
        }
        return id;
    }

    public void start() {
        if (bg != null) return;
        thread.start();
        bg = new Handler(thread.getLooper());
        if (!configured()) {
            status = "non configurato";
            Diag.put("log.invio", "non configurato in questa versione");
            return;
        }
        if (notCar()) return;
        writeState();
        bg.postDelayed(periodic, FIRST_DELAY_MS);
        // log rimasti in coda dalle accensioni prima: si rimandano subito, prima del log nuovo
        if (queued() > 0) bg.postDelayed(() -> flushQueueTry("all'accensione"), QUEUE_DELAY_MS);
        try {
            ctx.registerReceiver(new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context c, android.content.Intent i) {
                    if (bg != null) bg.post(LogUploader.this::snapshotToQueue);
                }
            }, new android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF));
        } catch (Throwable e) {
            Diag.error("log.spegnimento", e.toString());
        }
    }

    /** Schermo spento (auto spenta): il log va subito in coda e si prova a mandarlo; altrimenti parte all'accensione dopo. */
    private void snapshotToQueue() {
        if (!configured() || !enabled() || notCar()) return;
        String path = FOLDER + "/" + installId() + "/" + now("yyyy-MM-dd") + "/" + now("HH-mm-ss") + "-spegnimento.txt";
        enqueue(path, logBytes());
        Diag.put("log.invio", "schermo spento: log messo in coda");
        flushQueueTry("allo spegnimento");
    }

    private byte[] logBytes() {
        String text = Diag.buildLog(ctx);
        byte[] data;
        try {
            data = text.getBytes("UTF-8");
        } catch (Exception e) {
            data = text.getBytes();
        }
        if (data.length > MAX_BYTES) data = Arrays.copyOfRange(data, data.length - MAX_BYTES, data.length);
        return data;
    }

    /** Rimanda la coda; se il primo file non parte si annota l'errore (e si riprova più tardi). */
    private void flushQueueTry(String why) {
        if (!configured() || !enabled() || notCar()) return;
        int before = queued();
        if (before == 0) return;
        try {
            int sent = flushQueue();
            Offline.ok();
            Diag.put("log.coda", why + ": rimandati " + sent + " di " + before);
            noteOk();
        } catch (Exception e) {
            String err = Offline.isNetError(e) ? "rete: " + Diag.netError(e) : String.valueOf(e.getMessage());
            Offline.failed("log", e);
            Diag.put("log.coda", why + ": non rimandati (" + err + "), " + queued() + " in coda");
            noteError(err);
        }
    }

    public void stop() {
        if (bg != null) bg.removeCallbacksAndMessages(null);
    }

    /** Invia subito un log (es. dopo "Salva log"). */
    public static void requestNow() {
        LogUploader u = instance;
        if (u != null && u.bg != null) u.bg.post(u::uploadOnce);
    }

    private final Runnable periodic = new Runnable() {
        @Override
        public void run() {
            uploadOnce();
            bg.postDelayed(this, PERIOD_MS);
        }
    };

    private File queueDir() {
        File d = new File(ctx.getFilesDir(), "log-coda");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    private Boolean notCar;

    /** Telefono, emulatore o firmware non riconosciuto: nessun log (e la coda si svuota). */
    private boolean notCar() {
        if (notCar == null) {
            String why = Firmware.notACar(ctx);
            notCar = why != null;
            if (notCar) {
                status = "disattivato";
                Diag.put("log.invio", "disattivato: " + why);
                File[] q = queueDir().listFiles();
                if (q != null) for (File f : q) //noinspection ResultOfMethodCallIgnored
                    f.delete();
            }
        }
        return notCar;
    }

    private void uploadOnce() {
        if (!configured() || !enabled() || notCar()) return;
        String day = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
        String time = new SimpleDateFormat("HH-mm-ss", Locale.ROOT).format(new Date());
        byte[] data = logBytes();
        String path = FOLDER + "/" + installId() + "/" + day + "/" + time + ".txt";
        if (!Offline.canTry()) {
            // 0.4.0: senza rete il log va in coda senza tentare (pausa comune crescente, vedi rete.offline)
            status = "offline";
            Diag.put("log.invio", "offline: in coda");
            enqueue(path, data);
            noteError("rete assente (pausa comune, prossimo tentativo tra " + Math.max(1, Offline.waitMs() / 60_000) + " min)");
            return;
        }
        try {
            put(path, data);
            Offline.ok();
            String hhmm = new SimpleDateFormat("HH:mm", Locale.ROOT).format(new Date());
            status = "ok " + hhmm;
            Diag.put("log.invio", "inviato alle " + hhmm + " → " + path);
            noteOk();
            try {
                int n = flushQueue();
                if (n > 0) Diag.put("log.coda", "rimandati " + n + " log in coda");
            } catch (Exception e) {
                Diag.put("log.coda", "non rimandati (" + e.getMessage() + "), " + queued() + " in coda");
            }
            writeState();
        } catch (Exception e) {
            status = "errore " + e.getMessage();
            String err = Offline.isNetError(e) ? "rete: " + Diag.netError(e) : String.valueOf(e.getMessage());
            if (Offline.failed("log", e)) Diag.put("log.invio", "offline: in coda");
            else Diag.put("log.invio", "non riuscito (" + err + "), in coda");
            enqueue(path, data);
            noteError(err);
        }
    }

    private void enqueue(String path, byte[] data) {
        File dir = queueDir();
        File[] old = dir.listFiles();
        if (old != null && old.length >= MAX_QUEUE) {
            Arrays.sort(old, (a, b) -> a.getName().compareTo(b.getName()));
            for (int i = 0; i <= old.length - MAX_QUEUE; i++) //noinspection ResultOfMethodCallIgnored
                old[i].delete();
        }
        try (OutputStream o = new FileOutputStream(new File(dir, path.replace('/', '~')))) {
            o.write(data);
        } catch (Exception e) {
            Diag.error("log.coda", e.toString());
        }
    }

    /** Manda i log in coda, dal più vecchio; al primo errore si ferma e lo rilancia (riprova al prossimo invio). */
    private int flushQueue() throws Exception {
        File[] files = queueDir().listFiles();
        if (files == null) return 0;
        Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        int sent = 0;
        for (File f : files) {
            byte[] data = readAll(new FileInputStream(f));
            put(f.getName().replace('~', '/'), data);
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            sent++;
        }
        return sent;
    }

    /** PUT /repos/{repo}/contents/{path}: crea il file con il contenuto in base64. */
    private void put(String path, byte[] data) throws Exception {
        JSONObject body = new JSONObject();
        body.put("message", "log " + path);
        body.put("content", Base64.encodeToString(data, Base64.NO_WRAP));
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.github.com/repos/" + REPO + "/contents/" + path).openConnection();
        c.setRequestMethod("PUT");
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setDoOutput(true);
        c.setRequestProperty("Authorization", "Bearer " + LogConfig.token());
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("User-Agent", "MG4Launcher");
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (OutputStream o = c.getOutputStream()) {
            o.write(body.toString().getBytes("UTF-8"));
        }
        int code = c.getResponseCode();
        c.disconnect();
        // 422: il file esiste già (reinvio dalla coda di un log già arrivato) → considerato inviato
        if (code == 422) {
            Diag.put("log.giàPresente", path);
            return;
        }
        if (code != 200 && code != 201) throw new Exception("HTTP " + code + (code == 401 ? " (token non valido)"
                : code == 403 ? " (accesso negato o limite)" : code == 404 ? " (repository non trovato o token senza permesso)" : ""));
    }

    private static byte[] readAll(InputStream in) throws Exception {
        try (InputStream i = in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = i.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        }
    }
}
