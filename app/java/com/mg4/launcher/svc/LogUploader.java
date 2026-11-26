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
 * Invio automatico dei log nel repository privato dei log, un file per invio:
 *   SWI132/<codice installazione>/<aaaa-mm-gg>/<hh-mm-ss>.txt
 * All'avvio (dopo 90 s, quando i servizi dell'auto hanno risposto), ogni 30 minuti e con "Salva log".
 * Senza rete i log restano in coda (al massimo 20) e partono al primo invio riuscito.
 * 0.3.2 (punto 5): con viaggi brevi non arrivava nulla. Ora la coda si rimanda già 45 s dopo l'accensione; dopo un
 * errore si riprova a 2, 4, 8 e 15 minuti; allo spegnimento dello schermo (auto spenta) il log si mette in coda subito,
 * così parte all'accensione dopo. La riga log.stato (salvata) dice ultimo invio riuscito, ultimo errore e coda.
 * <p>
 * 0.3.6 (come SWI68 1.0.7): i log passano dal ponte Cloudflare (Worker "mg4-log-ponte"), che imita l'API "contents" di GitHub
 * (PUT …/repos/…/contents/<percorso>, JSON message + content base64). Nell'app NON c'è più nessun token GitHub e
 * la chiave del ponte serve solo per consegnare log, mai per altro. Chiave e indirizzo non sono nel codice:
 * build.sh genera LogConfig da tools/log-token.txt e tools/log-relay.txt (esclusi da git).
 * Esiti: 201 inviato, 422 già presente (= inviato), 401/403 chiave rifiutata (resta in coda, niente tentativi a
 * raffica fino al prossimo invio periodico), 413 troppo grande (tolto dalla coda), altro: resta in coda.
 */
public final class LogUploader {
    private static final String REPO = "ernestomicco2026-cell/mg4-launcher-logs";
    private static final String FOLDER = "SWI132";
    private static final long FIRST_DELAY_MS = 90_000, PERIOD_MS = 30 * 60_000, QUEUE_DELAY_MS = 45_000;
    private static final long[] RETRY_MS = {2 * 60_000L, 4 * 60_000L, 8 * 60_000L, 15 * 60_000L};
    private int retries;
    private static final int MAX_QUEUE = 20;
    private static final int MAX_BYTES = 400_000;
    private static final int REPORT_MAX_BYTES = 700_000;

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

    /** true se questa versione è stata compilata con la chiave e l'indirizzo del ponte dei log. */
    public static boolean configured() {
        return LogConfig.token().length() > 10 && LogConfig.relay().startsWith("https://");
    }

    /** Il ponte ha rifiutato la chiave (401/403): niente nuovi tentativi automatici prima di questo istante. */
    private static volatile long keyRejectedUntil;

    /** Errore HTTP del ponte, con il codice. */
    static final class RelayError extends Exception {
        private static final long serialVersionUID = 1L;
        final int code;

        RelayError(int code) {
            super("HTTP " + code);
            this.code = code;
        }

        boolean keyRejected() {
            return code == 401 || code == 403;
        }
    }

    /** Percorso accettato dal ponte: <CARTELLA>/<id 6-16 esadecimale>/<aaaa-mm-gg>/<nome .txt, max 80>. */
    static boolean validPath(String path) {
        return path.matches("(SWI68|SWI69|SWI132|SWI133|DBI68|CLIMA-[A-Z0-9]+|ENERGIA-[A-Z0-9]+|360-[A-Z0-9]+|GAMES)"
                + "/[0-9a-f]{6,16}/[0-9]{4}-[0-9]{2}-[0-9]{2}/[A-Za-z0-9._\\-]{1,76}\\.txt");
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

    /** Date di percorsi e log sempre con cifre 0-9 (Locale.US: auto con il sistema in arabo). */
    private static String now(String fmt) {
        return new SimpleDateFormat(fmt, Locale.US).format(new Date());
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
        return last + " · via ponte · ultimo invio riuscito " + (ok.isEmpty() ? "mai" : ok) + " · ultimo errore "
                + (err.isEmpty() ? "nessuno" : err) + " · in coda " + queued();
    }

    private void writeState() {
        Diag.put("log.stato", stateLine());
    }

    private void noteOk() {
        sp.edit().putString("logUltimoOk", now("dd/MM HH:mm")).putLong("logUltimoOkAlle", System.currentTimeMillis()).apply();
        retries = 0;
        if (bg != null) bg.removeCallbacks(retry);
        writeState();
    }

    private void noteError(String why) {
        sp.edit().putString("logUltimoErrore", now("dd/MM HH:mm") + " " + why).putLong("logUltimoErroreAlle", System.currentTimeMillis()).apply();
        writeState();
        // nuovo tentativo presto, senza aspettare i 30 minuti (viaggi brevi); con la chiave rifiutata no
        if (retries < RETRY_MS.length && bg != null && System.currentTimeMillis() >= keyRejectedUntil) {
            long wait = Math.max(RETRY_MS[retries++], Offline.waitMs());
            bg.removeCallbacks(retry);
            bg.postDelayed(retry, wait);
            Diag.put("log.riprova", "tra " + Math.max(1, wait / 60_000) + " min (tentativo " + retries + " di " + RETRY_MS.length + ")");
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
        bg.postDelayed(periodic, FIRST_DELAY_MS);
        writeState();
        // log rimasti in coda dalle accensioni prima (anche delle versioni vecchie): si rimandano subito, via ponte
        if (queued() > 0) bg.postDelayed(() -> flushQueueTry("all'accensione"), QUEUE_DELAY_MS);
        try {
            ctx.registerReceiver(screenOff, new android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF));
            screenOffOn = true;
        } catch (Throwable e) {
            Diag.error("log.spegnimento", e.toString());
        }
    }

    private boolean screenOffOn;
    private final android.content.BroadcastReceiver screenOff = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context c, android.content.Intent i) {
            Handler h = bg;
            if (h != null) h.post(LogUploader.this::snapshotToQueue);
        }
    };

    /** Schermo spento (auto spenta): il log va subito in coda e si prova a mandarlo; altrimenti parte all'accensione dopo. */
    private void snapshotToQueue() {
        if (!configured() || !enabled() || notCar()) return;
        String path = FOLDER + "/" + installId() + "/" + now("yyyy-MM-dd") + "/" + now("HH-mm-ss") + "-spegnimento.txt";
        enqueue(path, logBytes());
        Diag.put("log.invio", "via ponte · schermo spento: log messo in coda");
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

    /** Rimanda la coda; se un file non parte si annota l'errore (e si riprova più tardi). */
    private void flushQueueTry(String why) {
        if (!configured() || !enabled() || notCar()) return;
        int before = queued();
        if (before == 0 || !Offline.canTry() || !keyOk(false)) return;
        try {
            int sent = flushQueue();
            Diag.put("log.coda", why + ": rimandati " + sent + " di " + before);
            noteOk();
        } catch (Exception e) {
            String err = e instanceof RelayError ? "ponte " + ((RelayError) e).code : Offline.isNetError(e) ? "rete: " + Diag.netError(e) : String.valueOf(e.getMessage());
            Diag.put("log.coda", why + ": non rimandati (" + err + "), " + queued() + " in coda");
            noteError(err);
        }
    }

    public void stop() {
        if (bg != null) bg.removeCallbacksAndMessages(null);
        thread.quitSafely();   // 0.3.6: il thread non resta acceso dopo la chiusura della home (ne parte uno nuovo)
        if (screenOffOn) {
            screenOffOn = false;
            try {
                ctx.unregisterReceiver(screenOff);
            } catch (Throwable ignored) {
            }
        }
    }

    /** Invia subito un log (es. dopo "Salva log"). */
    public static void requestNow() {
        LogUploader u = instance;
        if (u != null && u.bg != null) u.bg.post(() -> u.uploadOnce(true));
    }

    /** Esito dell'invio di un'icona mancante (testi tradotti dalla home). */
    public static final int ICON_SENT = 0, ICON_QUEUED = 1, ICON_RETRY = 2, ICON_NOT_CONFIGURED = 3, ICON_ERROR = 4;

    public interface ReportListener {
        void complete(int result);
    }

    /**
     * 1.0.5 (PR #31 di sommistica): segnalazione di un'icona di manovra non riconosciuta. Il rapporto ha solo codice,
     * tipo di istruzione (categoria fissa, ManeuverReport) e distanza: niente vie né destinazioni. Si salva prima in
     * coda, poi si prova a inviarlo.
     */
    public static void reportMissingIcon(int code, int meters, String text, ReportListener listener) {
        LogUploader u = instance;
        Handler main = new Handler(android.os.Looper.getMainLooper());
        if (u == null || u.bg == null || !configured()) {
            main.post(() -> listener.complete(ICON_NOT_CONFIGURED));
            return;
        }
        final Date captured = new Date();
        final String report = ManeuverReport.body(code, meters, text);
        u.bg.post(() -> {
            int result;
            try {
                if (u.notCar()) throw new Exception("non è un'auto");
                String day = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(captured);
                String time = new SimpleDateFormat("HH-mm-ss-SSS", Locale.US).format(captured);
                String path = FOLDER + "/" + u.installId() + "/" + day + "/icona-mancante-" + time
                        + "-" + UUID.randomUUID().toString().substring(0, 8) + ".txt";
                byte[] bytes = report.getBytes("UTF-8");
                if (!u.enqueue(path, bytes)) throw new Exception("non salvato in coda");
                result = ICON_QUEUED;
                if (Offline.canTry() && u.keyOk(true)) {
                    try {
                        u.put(path, bytes);
                        //noinspection ResultOfMethodCallIgnored
                        new File(u.queueDir(), path.replace('/', '~')).delete();
                        Offline.ok();
                        result = ICON_SENT;
                        u.flushQueueQuiet();
                    } catch (Exception e) {
                        u.failed(path, e);
                        result = ICON_RETRY;
                        if (e instanceof RelayError && ((RelayError) e).code == 413) {
                            //noinspection ResultOfMethodCallIgnored
                            new File(u.queueDir(), path.replace('/', '~')).delete();
                            result = ICON_ERROR;
                        }
                    }
                }
            } catch (Exception e) {
                result = ICON_ERROR;
            }
            Diag.put("nav.iconaMancante", "codice " + code + " · " + ManeuverReport.instructionType(text) + " · esito " + result);
            final int r = result;
            main.post(() -> listener.complete(r));
        });
    }

    /**
     * 1.0.7 (diagnostica completa): invia un rapporto con il suo nome ("diagnostica-…") nella cartella del giorno. Prima
     * va in coda, poi si prova a inviarlo; senza rete parte da solo al prossimo invio. listener sul thread principale.
     */
    public static void sendReport(String prefix, String body, ReportListener listener) {
        LogUploader u = instance;
        Handler main = new Handler(android.os.Looper.getMainLooper());
        if (u == null || u.bg == null || !configured()) {
            main.post(() -> listener.complete(ICON_NOT_CONFIGURED));
            return;
        }
        final Date captured = new Date();
        u.bg.post(() -> {
            int result;
            try {
                if (u.notCar()) throw new Exception("non è un'auto");
                String day = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(captured);
                String time = new SimpleDateFormat("HH-mm-ss", Locale.US).format(captured);
                String path = FOLDER + "/" + u.installId() + "/" + day + "/" + time + "-" + prefix + ".txt";
                byte[] bytes = body.getBytes("UTF-8");
                // 700 kB al massimo (in Base64 resta sotto 1 MB per GitHub); si tiene l'inizio, dove c'è il rapporto
                if (bytes.length > REPORT_MAX_BYTES) bytes = Arrays.copyOfRange(bytes, 0, REPORT_MAX_BYTES);
                if (!u.enqueue(path, bytes)) throw new Exception("non salvato in coda");
                result = ICON_QUEUED;
                if (Offline.canTry() && u.keyOk(true)) {
                    try {
                        u.put(path, bytes);
                        //noinspection ResultOfMethodCallIgnored
                        new File(u.queueDir(), path.replace('/', '~')).delete();
                        Offline.ok();
                        result = ICON_SENT;
                        u.flushQueueQuiet();
                    } catch (Exception e) {
                        u.failed(path, e);
                        result = ICON_RETRY;
                        if (e instanceof RelayError && ((RelayError) e).code == 413) {
                            //noinspection ResultOfMethodCallIgnored
                            new File(u.queueDir(), path.replace('/', '~')).delete();
                            result = ICON_ERROR;
                        }
                    }
                }
            } catch (Exception e) {
                result = ICON_ERROR;
            }
            Diag.put("diagnostica.invio", result == ICON_SENT ? "inviata" : result == ICON_QUEUED || result == ICON_RETRY ? "in coda" : "non riuscita");
            final int r = result;
            main.post(() -> listener.complete(r));
        });
    }

    private final Runnable periodic = new Runnable() {
        @Override
        public void run() {
            uploadOnce();
            bg.postDelayed(this, PERIOD_MS);
        }
    };

    /** 1.0.7: true se in coda c'è un file il cui nome finisce con suffix (es. "-diagnostica.txt"). */
    static boolean reportQueued(Context c, String suffix) {
        File[] f = new File(c.getFilesDir(), "log-coda").listFiles();
        if (f != null) for (File x : f) if (x.getName().endsWith(suffix)) return true;
        return false;
    }

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
        uploadOnce(false);
    }

    private void uploadOnce(boolean manual) {
        if (!configured() || !enabled() || notCar()) return;
        Date at = new Date();
        String day = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(at);
        String time = new SimpleDateFormat("HH-mm-ss", Locale.US).format(at);
        byte[] data = logBytes();
        String path = FOLDER + "/" + installId() + "/" + day + "/" + time + ".txt";
        if (!Offline.canTry()) {
            // 1.0.0: senza rete il log va in coda senza tentare (pausa comune crescente, vedi rete.offline)
            status = "offline";
            Diag.put("log.invio", "via ponte · offline: in coda");
            enqueue(path, data);
            noteError("rete assente (pausa comune, prossimo tentativo tra " + Math.max(1, Offline.waitMs() / 60_000) + " min)");
            return;
        }
        if (!keyOk(manual)) {
            enqueue(path, data);
            writeState();
            return;
        }
        try {
            int code = put(path, data);
            Offline.ok();
            String hhmm = new SimpleDateFormat("HH:mm", Locale.US).format(new Date());
            status = "ok " + hhmm;
            Diag.put("log.invio", "via ponte · " + code + " · inviato alle " + hhmm + " → " + path);
            noteOk();
            try {
                int n = flushQueue();
                if (n > 0) Diag.put("log.coda", "rimandati " + n + " log in coda");
            } catch (Exception e) {
                Diag.put("log.coda", "non rimandati (" + e.getMessage() + "), " + queued() + " in coda");
            }
            writeState();
        } catch (Exception e) {
            failed(path, e);
            if (!(e instanceof RelayError && ((RelayError) e).code == 413)) enqueue(path, data);
            noteError(e instanceof RelayError ? "ponte " + ((RelayError) e).code : Offline.isNetError(e) ? "rete: " + Diag.netError(e) : String.valueOf(e.getMessage()));
        }
    }

    /**
     * Dopo un rifiuto della chiave (401/403) niente tentativi automatici per 30 minuti: il log resta in coda. Un invio
     * a mano ("Invia log", diagnostica) prova comunque.
     */
    private boolean keyOk(boolean manual) {
        if (manual || System.currentTimeMillis() >= keyRejectedUntil) return true;
        status = "chiave rifiutata";
        Diag.putQuiet("log.invio", "via ponte · chiave rifiutata poco fa: in coda, nuovo tentativo più tardi");
        return false;
    }

    /** Esito di un invio non riuscito: rete (pausa comune), chiave rifiutata, file troppo grande o altro errore. */
    private void failed(String path, Exception e) {
        if (e instanceof RelayError) {
            RelayError r = (RelayError) e;
            Offline.ok();   // il ponte ha risposto: la rete c'è
            if (r.keyRejected()) {
                keyRejectedUntil = System.currentTimeMillis() + PERIOD_MS;
                status = "chiave rifiutata";
                Diag.put("log.invio", "via ponte · " + r.code + ": chiave del ponte rifiutata, resta in coda (nuovo tentativo tra 30 min)");
            } else if (r.code == 413) {
                status = "troppo grande";
                Diag.put("log.invio", "via ponte · 413: file troppo grande, tolto dalla coda → " + path);
            } else {
                status = "errore " + r.code;
                Diag.put("log.invio", "via ponte · errore " + r.code + ", in coda");
            }
            return;
        }
        if (e instanceof IllegalArgumentException) {   // percorso che il ponte non accetterebbe: non è la rete
            status = "percorso non valido";
            Diag.put("log.invio", "via ponte · percorso non valido, non inviato → " + path);
            return;
        }
        status = "errore " + e.getMessage();
        if (Offline.failed("log", e)) Diag.put("log.invio", "via ponte · offline: in coda");
        else Diag.put("log.invio", "via ponte · non riuscito (" + Diag.netError(e) + "), in coda");
    }

    private boolean enqueue(String path, byte[] data) {
        File dir = queueDir();
        File[] old = dir.listFiles();
        if (old != null && old.length >= MAX_QUEUE) {
            Arrays.sort(old, (a, b) -> a.getName().compareTo(b.getName()));
            for (int i = 0; i <= old.length - MAX_QUEUE; i++) //noinspection ResultOfMethodCallIgnored
                old[i].delete();
        }
        try (OutputStream o = new FileOutputStream(new File(dir, path.replace('/', '~')))) {
            o.write(data);
            return true;
        } catch (Exception e) {
            Diag.error("log.coda", e.toString());
            return false;
        }
    }

    private void flushQueueQuiet() {
        try {
            flushQueue();
        } catch (Exception ignored) {
            // annotato da failed(); la coda riparte al prossimo invio
        }
    }

    /** Manda i log in coda, dal più vecchio; al primo errore si ferma e lo rilancia (riprova al prossimo invio). */
    private int flushQueue() throws Exception {
        File[] files = queueDir().listFiles();
        if (files == null) return 0;
        Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        int sent = 0;
        for (File f : files) {
            String path = f.getName().replace('~', '/');
            if (!validPath(path)) {
                // percorso che il ponte non accetterebbe mai (es. data scritta con cifre arabe dalle versioni vecchie):
                // si sistema la data, altrimenti si toglie dalla coda
                String fixed = fixPath(path);
                if (fixed == null) {
                    Diag.put("log.coda", "tolto dalla coda (percorso non valido): " + f.getName());
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                    continue;
                }
                path = fixed;
            }
            try {
                byte[] data = readAll(new FileInputStream(f));
                put(path, data);
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                sent++;
                DeepCheck.delivered(ctx, path);
            } catch (RelayError e) {
                if (e.code == 413) {
                    failed(path, e);
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                    continue;
                }
                failed(path, e);
                throw e;   // riprova al prossimo invio
            } catch (Exception e) {
                failed(path, e);
                throw e;   // riprova al prossimo invio
            }
        }
        return sent;
    }

    /** Cifre di qualsiasi alfabeto (arabo, persiano…) → 0-9; null se il percorso resta non valido. */
    static String fixPath(String path) {
        StringBuilder sb = new StringBuilder(path.length());
        for (int i = 0; i < path.length(); i++) {
            char ch = path.charAt(i);
            int d = Character.digit(ch, 10);
            sb.append(d >= 0 && (ch < '0' || ch > '9') ? (char) ('0' + d) : ch);
        }
        String out = sb.toString();
        return validPath(out) ? out : null;
    }

    /**
     * PUT <ponte>/repos/{repo}/contents/{path}: crea il file con il contenuto in base64. Restituisce 201 (o 200), oppure
     * 422 se il file c'è già (reinvio dalla coda di un log già arrivato: vale come inviato). Altrimenti RelayError.
     */
    private int put(String path, byte[] data) throws Exception {
        if (!validPath(path)) throw new IllegalArgumentException("percorso non accettato dal ponte");
        JSONObject body = new JSONObject();
        body.put("message", "log " + path);
        body.put("content", Base64.encodeToString(data, Base64.NO_WRAP));
        HttpURLConnection c = (HttpURLConnection) new URL(LogConfig.relay() + "/repos/" + REPO + "/contents/" + path).openConnection();
        try {
            c.setRequestMethod("PUT");
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setDoOutput(true);
            c.setInstanceFollowRedirects(false);   // la chiave non deve seguire un rinvio verso un altro indirizzo
            c.setRequestProperty("Authorization", "Bearer " + LogConfig.token());
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("User-Agent", "MG4Launcher");
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream o = c.getOutputStream()) {
                o.write(body.toString().getBytes("UTF-8"));
            }
            int code = c.getResponseCode();
            if (code == 422) {
                Diag.putQuiet("log.giàPresente", path);
                return 422;
            }
            if (code != 200 && code != 201) throw new RelayError(code);
            return code;
        } finally {
            c.disconnect();
        }
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
