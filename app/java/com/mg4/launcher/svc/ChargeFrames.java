package com.mg4.launcher.svc;

import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 1.0.4: fotogrammi delle clip di ricarica, estratti una volta sola dagli MP4 e salvati come JPEG accanto alle clip
 * (cartella "fotogrammi/&lt;nome clip&gt;/0001.jpg…", più il file "pronto" con il numero di fotogrammi).
 * <p>
 * Perché: sull'auto la riproduzione con MediaPlayer su TextureView restava ferma sul primo fotogramma (schermo nero,
 * fase "aggancio" bloccata, log del 15/11). Con i fotogrammi già pronti l'animazione è disegnata da una vista
 * normale, senza decoder video a schermo: niente superfici nere e gli errori si vedono nel log.
 * <p>
 * 1.0.6: l'estrazione usa MediaCodec verso la memoria (YUV → JPEG); se non va, MediaMetadataRetriever un fotogramma
 * alla volta. In un thread di lavoro.
 * <p>
 * 0.3.5: le clip sono BT.709 "TV" (16-235), il JPEG di YuvImage invece si legge come BT.601 pieno (0-255): senza
 * conversione lo sfondo di notte usciva 222327 invece di 15171B e il bianco ebebeb (riquadro attorno all'auto, auto
 * slavata). Dopo toNv21 i valori si convertono (ColorConv). Il file "pronto" ora dice "&lt;numero&gt; colori2": i fotogrammi
 * fatti prima, con i colori sbagliati, non valgono più e si rifanno da soli al prossimo cavo o alla prossima simulazione.
 * <p> Circa 290 fotogrammi per tema, 25–30 MB in tutto per un colore. Log "ricarica.fotogrammi".
 */
public final class ChargeFrames {
    private static final String DONE = "pronto", DIR = "fotogrammi";
    /** Marcatore dei fotogrammi con i colori giusti (0.3.5). */
    private static final String COLORS_MARK = "colori2";
    private static final int JPEG_Q = 88;

    private ChargeFrames() {}

    /** Cartella dei fotogrammi di una clip. */
    public static File dir(File clip) {
        String n = clip.getName();
        int dot = n.lastIndexOf('.');
        return new File(new File(clip.getParentFile(), DIR), dot > 0 ? n.substring(0, dot) : n);
    }

    /** Fotogrammi pronti della clip (in ordine), oppure null se non ancora estratti. */
    public static File[] frames(File clip) {
        if (clip == null) return null;
        File d = dir(clip);
        File done = new File(d, DONE);
        if (!done.isFile()) return null;
        int n;
        try {
            // "<numero> colori2": senza il marcatore sono fotogrammi vecchi (colori sbagliati) e si rifanno
            String[] p = new String(read(done)).trim().split("\\s+");
            if (p.length < 2 || !COLORS_MARK.equals(p[1])) return null;
            n = Integer.parseInt(p[0]);
        } catch (Exception e) {
            return null;
        }
        if (n <= 0) return null;
        File[] out = new File[n];
        for (int i = 0; i < n; i++) {
            out[i] = new File(d, String.format(java.util.Locale.ROOT, "%04d.jpg", i + 1));
            if (!out[i].isFile()) return null;
        }
        return out;
    }

    /** true se tutte le clip della lista hanno i fotogrammi pronti. */
    public static boolean ready(File... clips) {
        for (File c : clips) if (c != null && frames(c) == null) return false;
        return true;
    }

    private static final Object LOCK = new Object();

    /**
     * Estrae i fotogrammi delle clip che non li hanno ancora. Da un thread di lavoro (blocca). Restituisce null se tutto
     * è andato bene, altrimenti il motivo.
     */
    public static String build(File... clips) {
        synchronized (LOCK) {
            long t0 = android.os.SystemClock.elapsedRealtime();
            int total = 0, made = 0;
            for (File c : clips) {
                if (c == null || frames(c) != null) continue;
                String err = buildOne(c);
                if (err != null) {
                    Diag.put("ricarica.fotogrammi", "errore " + c.getName() + ": " + err);
                    return err;
                }
                made++;
                File[] f = frames(c);
                total += f == null ? 0 : f.length;
            }
            if (made > 0) Diag.put("ricarica.fotogrammi", "pronti: " + made + " clip, " + total + " fotogrammi in "
                    + (android.os.SystemClock.elapsedRealtime() - t0) / 1000 + " s · " + lastNote);
            return null;
        }
    }

    private static String buildOne(File clip) {
        File d = dir(clip);
        ChargePack.deleteTree(d);
        if (!d.mkdirs() && !d.isDirectory()) return "cartella non creata";
        long t0 = android.os.SystemClock.elapsedRealtime();
        // 1.0.6: prima MediaCodec verso la memoria (niente Surface), YUV → JPEG con YuvImage. Il metodo "a blocchi" di
        // MediaMetadataRetriever falliva sempre sulle auto ("No frames from retriever") ed è stato tolto.
        String codecErr;
        try {
            codecErr = buildWithCodec(clip, d);
        } catch (Throwable e) {
            codecErr = e.getClass().getSimpleName() + (e.getMessage() == null ? "" : " " + e.getMessage());
        }
        if (codecErr == null) {
            lastNote = "MediaCodec · " + lastWritten + " fotogrammi in " + (android.os.SystemClock.elapsedRealtime() - t0) / 1000 + " s · 0 ripetuti · " + lastColors;
            return writeDone(d, lastWritten);
        }
        ChargePack.deleteTree(d);
        if (!d.mkdirs() && !d.isDirectory()) return "cartella non creata";
        String err = buildWithRetriever(clip, d);
        if (err == null) lastNote = lastNote + " (MediaCodec: " + codecErr + ")";
        return err;
    }

    private static int lastWritten;

    private static String writeDone(File d, int written) {
        if (written <= 0) return "nessun fotogramma";
        try (FileOutputStream fo = new FileOutputStream(new File(d, DONE))) {
            fo.write((written + " " + COLORS_MARK).getBytes());
            return null;
        } catch (Exception e) {
            return "file pronto non scritto";
        }
    }

    /** Decodifica con MediaCodec in memoria; null se ha scritto tutti i fotogrammi, altrimenti il motivo. */
    private static String buildWithCodec(File clip, File d) throws Exception {
        android.media.MediaExtractor ex = new android.media.MediaExtractor();
        android.media.MediaCodec codec = null;
        lastWritten = 0;
        try {
            ex.setDataSource(clip.getAbsolutePath());
            int track = -1;
            android.media.MediaFormat fmt = null;
            for (int i = 0; i < ex.getTrackCount(); i++) {
                android.media.MediaFormat f = ex.getTrackFormat(i);
                String m = f.getString(android.media.MediaFormat.KEY_MIME);
                if (m != null && m.startsWith("video/")) {
                    track = i;
                    fmt = f;
                    break;
                }
            }
            if (track < 0) return "nessuna traccia video";
            ex.selectTrack(track);
            String mime = fmt.getString(android.media.MediaFormat.KEY_MIME);
            fmt.setInteger(android.media.MediaFormat.KEY_COLOR_FORMAT,
                    android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            codec = android.media.MediaCodec.createDecoderByType(mime);
            codec.configure(fmt, null, null, 0);
            codec.start();
            ColorConv conv = null;
            android.media.MediaFormat outFmt = null;
            android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
            boolean inEos = false;
            int written = 0, idle = 0;
            byte[] nv21 = null;
            while (true) {
                if (!inEos) {
                    int in = codec.dequeueInputBuffer(10_000);
                    if (in >= 0) {
                        java.nio.ByteBuffer buf = codec.getInputBuffer(in);
                        int n = buf == null ? -1 : ex.readSampleData(buf, 0);
                        if (n < 0) {
                            codec.queueInputBuffer(in, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inEos = true;
                        } else {
                            codec.queueInputBuffer(in, 0, n, ex.getSampleTime(), 0);
                            ex.advance();
                        }
                    }
                }
                int out = codec.dequeueOutputBuffer(info, 10_000);
                if (out >= 0) {
                    idle = 0;
                    if (info.size > 0) {
                        android.media.Image img = codec.getOutputImage(out);
                        if (img == null) {
                            codec.releaseOutputBuffer(out, false);
                            return "getOutputImage nullo";
                        }
                        try {
                            if (conv == null) {
                                // gamma e standard dal formato d'uscita del decoder (se mancano: quelli della traccia,
                                // poi BT.709 TV)
                                if (outFmt == null) outFmt = codec.getOutputFormat();
                                conv = ColorConv.of(outFmt, fmt);
                                lastColors = conv.note;
                            }
                            android.graphics.Rect crop = img.getCropRect();
                            int w = crop.width() & ~1, h = crop.height() & ~1;
                            if (nv21 == null || nv21.length != w * h * 3 / 2) nv21 = new byte[w * h * 3 / 2];
                            toNv21(img, crop, w, h, nv21);
                            conv.apply(nv21, w, h);
                            written++;
                            File o = new File(d, String.format(java.util.Locale.ROOT, "%04d.jpg", written));
                            try (FileOutputStream fo = new FileOutputStream(o)) {
                                if (!new android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, w, h, null)
                                        .compressToJpeg(new android.graphics.Rect(0, 0, w, h), JPEG_Q, fo))
                                    return "fotogramma " + written + " non salvato";
                            }
                        } finally {
                            img.close();
                        }
                    }
                    codec.releaseOutputBuffer(out, false);
                    if ((info.flags & android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
                } else if (out == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    outFmt = codec.getOutputFormat();
                } else if (out == android.media.MediaCodec.INFO_TRY_AGAIN_LATER && inEos && ++idle > 300) {
                    break;   // 3 s senza uscite dopo la fine dell'ingresso
                }
            }
            lastWritten = written;
            // controllo: devono esserci (quasi) tutti i fotogrammi attesi
            int expected = -1;
            try {
                MediaMetadataRetriever r = new MediaMetadataRetriever();
                r.setDataSource(clip.getAbsolutePath());
                expected = parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT));
                r.release();
            } catch (Throwable ignored) {
            }
            if (written == 0) return "nessun fotogramma decodificato";
            if (expected > 0 && written < expected) return "decodificati " + written + " fotogrammi su " + expected;
            return null;
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Throwable ignored) {
                }
                codec.release();
            }
            ex.release();
        }
    }

    private static String lastColors = "";

    /**
     * 0.3.5: conversione dei valori YUV della clip in YUV BT.601 pieno (0-255), quello con cui si legge il JPEG di
     * YuvImage. Matrice × 1024 calcolata da standard e gamma; per BT.709 TV:
     * Y' = (1192·(Y−16) + 118·(U−128) + 229·(V−128)) / 1024, U' = 128 + (1154·(U−128) − 129·(V−128)) / 1024,
     * V' = 128 + (−84·(U−128) + 1146·(V−128)) / 1024, limitati a 0-255. U' e V' dipendono solo dal blocco 2×2: si
     * trasforma sul posto. BT.601 pieno = nessuna conversione.
     */
    static final class ColorConv {
        final boolean identity;
        final int yOff, yy, yu, yv, uu, uv, vu, vv;
        final String note;

        private ColorConv(double kr, double kb, boolean full, String note) {
            double kg = 1 - kr - kb;
            double[][] col = new double[3][];
            for (int j = 0; j < 3; j++) {
                double y = j == 0 ? 1 : 0, u = j == 1 ? 1 : 0, v = j == 2 ? 1 : 0;
                double yn = full ? y / 255 : y / 219, cb = full ? u / 255 : u / 224, cr = full ? v / 255 : v / 224;
                double r = yn + 2 * (1 - kr) * cr, b = yn + 2 * (1 - kb) * cb, g = (yn - kr * r - kb * b) / kg;
                double yt = 0.299 * r + 0.587 * g + 0.114 * b;
                col[j] = new double[]{255 * yt, 255 * (b - yt) / (2 * (1 - 0.114)), 255 * (r - yt) / (2 * (1 - 0.299))};
            }
            yy = (int) Math.round(1024 * col[0][0]);
            yu = (int) Math.round(1024 * col[1][0]);
            yv = (int) Math.round(1024 * col[2][0]);
            uu = (int) Math.round(1024 * col[1][1]);
            uv = (int) Math.round(1024 * col[2][1]);
            vu = (int) Math.round(1024 * col[1][2]);
            vv = (int) Math.round(1024 * col[2][2]);
            yOff = full ? 0 : 16;
            identity = full && kr == 0.299 && kb == 0.114;
            this.note = note;
        }

        static ColorConv of(android.media.MediaFormat out, android.media.MediaFormat track) {
            int range = key(out, track, android.media.MediaFormat.KEY_COLOR_RANGE);
            int std = key(out, track, android.media.MediaFormat.KEY_COLOR_STANDARD);
            boolean full = range == android.media.MediaFormat.COLOR_RANGE_FULL;
            boolean is601 = std == android.media.MediaFormat.COLOR_STANDARD_BT601_PAL || std == android.media.MediaFormat.COLOR_STANDARD_BT601_NTSC;
            boolean is2020 = std == android.media.MediaFormat.COLOR_STANDARD_BT2020;
            String rn = range < 0 ? "TV (non indicata)" : full ? "piena" : "TV";
            String sn = std < 0 ? "BT.709 (non indicato)" : is601 ? "BT.601" : is2020 ? "BT.2020" : "BT.709";
            if (full && is601) return new ColorConv(0.299, 0.114, true, "colori BT.601 pieni: nessuna conversione");
            double kr = is601 ? 0.299 : is2020 ? 0.2627 : 0.2126, kb = is601 ? 0.114 : is2020 ? 0.0593 : 0.0722;
            return new ColorConv(kr, kb, full, "colori " + sn + " gamma " + rn + " convertiti in BT.601 pieni");
        }

        private static int key(android.media.MediaFormat a, android.media.MediaFormat b, String k) {
            try {
                if (a != null && a.containsKey(k)) return a.getInteger(k);
            } catch (Throwable ignored) {
            }
            try {
                if (b != null && b.containsKey(k)) return b.getInteger(k);
            } catch (Throwable ignored) {
            }
            return -1;
        }

        /** Converte sul posto un fotogramma NV21 w×h (w e h pari). */
        void apply(byte[] nv21, int w, int h) {
            if (identity) return;
            int cBase = w * h;
            for (int r = 0; r < h; r += 2) {
                for (int c = 0; c < w; c += 2) {
                    int ci = cBase + (r / 2) * w + c;      // NV21: V poi U
                    int dv = (nv21[ci] & 0xff) - 128, du = (nv21[ci + 1] & 0xff) - 128;
                    int ych = yu * du + yv * dv;
                    int y0 = r * w + c, y1 = y0 + w;
                    nv21[y0] = clamp((yy * ((nv21[y0] & 0xff) - yOff) + ych + 512) >> 10);
                    nv21[y0 + 1] = clamp((yy * ((nv21[y0 + 1] & 0xff) - yOff) + ych + 512) >> 10);
                    nv21[y1] = clamp((yy * ((nv21[y1] & 0xff) - yOff) + ych + 512) >> 10);
                    nv21[y1 + 1] = clamp((yy * ((nv21[y1 + 1] & 0xff) - yOff) + ych + 512) >> 10);
                    nv21[ci + 1] = clamp(128 + ((uu * du + uv * dv + 512) >> 10));
                    nv21[ci] = clamp(128 + ((vu * du + vv * dv + 512) >> 10));
                }
            }
        }

        private static byte clamp(int v) {
            return (byte) (v < 0 ? 0 : v > 255 ? 255 : v);
        }
    }

    /** YUV_420_888 (qualsiasi disposizione dei piani) → NV21 w×h dal riquadro crop. */
    private static void toNv21(android.media.Image img, android.graphics.Rect crop, int w, int h, byte[] out) {
        android.media.Image.Plane[] pl = img.getPlanes();
        // Y
        java.nio.ByteBuffer yb = pl[0].getBuffer();
        int yRow = pl[0].getRowStride(), yPix = pl[0].getPixelStride();
        int pos = 0;
        for (int r = 0; r < h; r++) {
            int base = (crop.top + r) * yRow + crop.left * yPix;
            if (yPix == 1) {
                yb.position(base);
                yb.get(out, pos, w);
                pos += w;
            } else {
                for (int c = 0; c < w; c++) out[pos++] = yb.get(base + c * yPix);
            }
        }
        // V e U alternati (NV21 = VU)
        java.nio.ByteBuffer ub = pl[1].getBuffer(), vb = pl[2].getBuffer();
        int uRow = pl[1].getRowStride(), uPix = pl[1].getPixelStride();
        int vRow = pl[2].getRowStride(), vPix = pl[2].getPixelStride();
        int cw = w / 2, chh = h / 2, ct = crop.top / 2, cl = crop.left / 2;
        for (int r = 0; r < chh; r++) {
            for (int c = 0; c < cw; c++) {
                out[pos++] = vb.get((ct + r) * vRow + (cl + c) * vPix);
                out[pos++] = ub.get((ct + r) * uRow + (cl + c) * uPix);
            }
        }
    }

    /** Metodo di riserva: MediaMetadataRetriever un fotogramma alla volta (per indice, poi per tempo). */
    private static String buildWithRetriever(File clip, File d) {
        long t0 = android.os.SystemClock.elapsedRealtime();
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(clip.getAbsolutePath());
            int n = parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT));
            if (n <= 0) {
                long ms = parse(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                n = (int) Math.round(ms * 30 / 1000.0);
            }
            if (n <= 0) return "numero di fotogrammi sconosciuto";
            int written = 0, missing = 0;
            File prev = null;
            for (int idx = 0; idx < n; idx++) {
                Bitmap b = null;
                try {
                    b = r.getFrameAtIndex(idx);
                } catch (Throwable ignored) {
                }
                if (b == null) {
                    try {
                        b = r.getFrameAtTime(idx * 1_000_000L / 30 + 16_000, MediaMetadataRetriever.OPTION_CLOSEST);
                    } catch (Throwable ignored) {
                    }
                }
                written++;
                File out = new File(d, String.format(java.util.Locale.ROOT, "%04d.jpg", written));
                if (b == null) {
                    // fotogramma illeggibile (succede verso la fine delle clip): si ripete il precedente
                    missing++;
                    if (prev == null || !copy(prev, out)) return "fotogramma " + written + " non letto";
                    continue;
                }
                try (FileOutputStream fo = new FileOutputStream(out)) {
                    if (!b.compress(Bitmap.CompressFormat.JPEG, JPEG_Q, fo)) return "fotogramma " + written + " non salvato";
                } finally {
                    b.recycle();
                }
                prev = out;
            }
            if (missing * 10 > n) return missing + " fotogrammi su " + n + " non letti";
            lastNote = "uno alla volta · " + written + " fotogrammi in " + (android.os.SystemClock.elapsedRealtime() - t0) / 1000
                    + " s · " + missing + " ripetuti · colori dal sistema (RGB, nessuna conversione)";
            return writeDone(d, written);
        } catch (Throwable e) {
            return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
        } finally {
            try {
                r.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private static String lastNote = "";

    private static boolean copy(File from, File to) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(from); FileOutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[64 * 1024];
            int k;
            while ((k = in.read(buf)) > 0) out.write(buf, 0, k);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static int parse(String s) {
        try {
            return s == null ? -1 : Integer.parseInt(s.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private static byte[] read(File f) throws java.io.IOException {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] b = new byte[(int) Math.min(64, f.length())];
            int n = in.read(b);
            return n <= 0 ? new byte[0] : java.util.Arrays.copyOf(b, n);
        }
    }
}
