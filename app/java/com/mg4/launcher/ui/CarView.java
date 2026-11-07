package com.mg4.launcher.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import com.mg4.launcher.R;

/**
 * La MG4 ricavata dalla foto: carrozzeria ricolorata (ombre × colore + riflessi), tetto nero
 * opzionale, tutto il resto (vetri, gomme, plastiche, luci) originale. Cerchi a scelta (dalla foto: 17" Aero) disegnati
 * sopra quelli della foto, con la gomma originale intorno: 16" un po' più piccoli, 18" un po' più grandi.
 */
public final class CarView extends View {
    private static Bitmap base, shade, highlight, roofShade;
    private final Paint paintLayer = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint roofLayer = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint hlLayer = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint plain = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint floor = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF();
    private boolean blackRoof;
    private int glow, shadow;
    /** Cerchi (indice di {@link Prefs#WHEEL_RES}); quello della foto non si ridisegna. */
    private int wheel = Prefs.WHEEL_PHOTO;
    private static final android.util.SparseArray<Bitmap> RIMS = new android.util.SparseArray<>();
    /** Centri e raggio dei cerchi nella foto (car_base 763×287). */
    private static final float[][] WHEEL_CENTERS = {{144.3f, 220.2f}, {634.3f, 220.2f}};
    private static final float RIM_R = 43.6f;
    private final Paint rimPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint rimShade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rimDst = new RectF();
    /** 0.3.1: fari accesi sul disegno (anabbaglianti o luci di posizione); false = auto disegnata come prima. */
    private boolean lights;
    private final Paint lampPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Path lampPath = new android.graphics.Path();

    public CarView(Context c) {
        super(c);
        load(c);
        roofLayer.setColorFilter(new PorterDuffColorFilter(0xFF1A1B1E, PorterDuff.Mode.MULTIPLY));
        hlLayer.setAlpha(140);
    }

    private static synchronized void load(Context c) {
        if (base != null) return;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inScaled = false;
        base = BitmapFactory.decodeResource(c.getResources(), R.drawable.car_base, o);
        shade = BitmapFactory.decodeResource(c.getResources(), R.drawable.car_shade, o);
        highlight = BitmapFactory.decodeResource(c.getResources(), R.drawable.car_highlight, o);
        Bitmap roofMask = BitmapFactory.decodeResource(c.getResources(), R.drawable.car_roof, o);
        // ombre della vernice limitate alla zona del tetto
        roofShade = Bitmap.createBitmap(shade.getWidth(), shade.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(roofShade);
        cv.drawBitmap(shade, 0, 0, null);
        Paint p = new Paint();
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        cv.drawBitmap(roofMask, 0, 0, p);
    }

    public void setLook(int bodyColor, boolean blackRoof, int wheel, Theme t) {
        this.wheel = wheel;
        setLook(bodyColor, blackRoof, t);
    }

    private Bitmap rim(int idx) {
        synchronized (RIMS) {
            Bitmap b = RIMS.get(idx);
            if (b == null) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inScaled = false;
                b = BitmapFactory.decodeResource(getResources(), Prefs.WHEEL_RES[idx], o);
                if (b != null) RIMS.put(idx, b);
            }
            return b;
        }
    }

    public void setLook(int bodyColor, boolean blackRoof, Theme t) {
        paintLayer.setColorFilter(new PorterDuffColorFilter(bodyColor, PorterDuff.Mode.MULTIPLY));
        this.blackRoof = blackRoof;
        glow = t.glow;
        shadow = t.shadow;
        floor.setShader(null);
        invalidate();
    }

    /** Fari accesi (true) o disegno normale (false, anche quando lo stato non si legge). */
    public void setLights(boolean on) {
        if (on == lights) return;
        lights = on;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0 || base == null) return;
        float bw = base.getWidth(), bh = base.getHeight();
        float s = Math.min(w / bw, (h * 0.9f) / bh);
        float dw = bw * s, dh = bh * s;
        float left = (w - dw) / 2f, top = (h - dh) / 2f - h * 0.02f;
        dst.set(left, top, left + dw, top + dh);

        // luce sul "pavimento" e ombra sotto l'auto
        float cy = dst.bottom - dh * 0.035f;
        if (floor.getShader() == null) {
            floor.setShader(new RadialGradient(w / 2f, cy, dw * 0.55f, glow, glow & 0x00FFFFFF, Shader.TileMode.CLAMP));
        }
        c.save();
        c.scale(1f, 0.09f, w / 2f, cy);
        c.drawCircle(w / 2f, cy, dw * 0.55f, floor);
        c.restore();
        shadowPaint.setColor(shadow);
        c.drawOval(dst.left + dw * 0.06f, cy - dh * 0.025f, dst.right - dw * 0.06f, cy + dh * 0.025f, shadowPaint);

        c.drawBitmap(shade, null, dst, paintLayer);
        if (blackRoof) c.drawBitmap(roofShade, null, dst, roofLayer);
        c.drawBitmap(highlight, null, dst, hlLayer);
        c.drawBitmap(base, null, dst, plain);
        drawWheels(c, s, dst.left, dst.top);
        if (lights) drawLights(c, s, dst.left, dst.top);
    }

    /**
     * Fari della foto (car_base 763×287, muso a sinistra): proiettore anteriore acceso con un fascio morbido davanti
     * all'auto e fanale posteriore rosso. Solo gradienti (niente sfocature software), leggero per la RAM dell'auto.
     */
    private void drawLights(Canvas c, float s, float left, float top) {
        float hx = left + 62 * s, hy = top + 136 * s;
        // fascio davanti all'auto
        lampPath.reset();
        lampPath.moveTo(left + 26 * s, top + 146 * s);
        lampPath.lineTo(left - 150 * s, top + 118 * s);
        lampPath.lineTo(left - 150 * s, top + 228 * s);
        lampPath.close();
        lampPaint.setShader(new android.graphics.LinearGradient(left + 26 * s, 0, left - 150 * s, 0, 0x66FFF6DC, 0x00FFF6DC, Shader.TileMode.CLAMP));
        c.drawPath(lampPath, lampPaint);
        // alone del proiettore
        lampPaint.setShader(new RadialGradient(hx, hy, 58 * s, 0xCCFFF8E6, 0x00FFF8E6, Shader.TileMode.CLAMP));
        c.save();
        c.scale(1f, 0.45f, hx, hy);
        c.drawCircle(hx, hy, 58 * s, lampPaint);
        c.restore();
        // vetro del proiettore (forma della foto)
        lampPath.reset();
        lampPath.moveTo(left + 24 * s, top + 151 * s);
        lampPath.lineTo(left + 46 * s, top + 139 * s);
        lampPath.lineTo(left + 76 * s, top + 127 * s);
        lampPath.lineTo(left + 102 * s, top + 121 * s);
        lampPath.lineTo(left + 99 * s, top + 127 * s);
        lampPath.lineTo(left + 74 * s, top + 136 * s);
        lampPath.lineTo(left + 46 * s, top + 147 * s);
        lampPath.lineTo(left + 28 * s, top + 154 * s);
        lampPath.close();
        lampPaint.setShader(null);
        lampPaint.setColor(0xEEFFFBF0);
        c.drawPath(lampPath, lampPaint);
        // fanale posteriore
        float tx = left + 716 * s, ty = top + 92 * s;
        lampPaint.setShader(new RadialGradient(tx, ty, 34 * s, 0xAAFF2A2A, 0x00FF2A2A, Shader.TileMode.CLAMP));
        c.save();
        c.scale(1f, 0.7f, tx, ty);
        c.drawCircle(tx, ty, 34 * s, lampPaint);
        c.restore();
        lampPaint.setShader(null);
    }

    private void drawWheels(Canvas c, float s, float left, float top) {
        if (wheel == Prefs.WHEEL_PHOTO || wheel < 0 || wheel >= Prefs.WHEEL_RES.length) return;
        Bitmap b = rim(wheel);
        if (b == null) return;
        float r = RIM_R * Prefs.WHEEL_SCALE[wheel] * s;
        for (float[] w : WHEEL_CENTERS) {
            float cx = left + w[0] * s, cy = top + w[1] * s;
            rimDst.set(cx - r, cy - r, cx + r, cy + r);
            c.drawBitmap(b, null, rimDst, rimPaint);
            // ombra in basso come nella foto (luce dall'alto)
            rimShade.setShader(new android.graphics.LinearGradient(0, cy - r, 0, cy + r, 0x00000000, 0x55000000, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r, rimShade);
        }
    }
}
