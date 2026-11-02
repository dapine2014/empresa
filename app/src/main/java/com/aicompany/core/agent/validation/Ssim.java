package com.aicompany.core.agent.validation;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/** Spec 2026-11-01 front con diseño visual: similitud estructural (SSIM) en grises, ventanas de 8x8. Puro, sin Spring. */
public final class Ssim {

    private static final int VENTANA = 8;
    private static final double C1 = Math.pow(0.01 * 255, 2);
    private static final double C2 = Math.pow(0.03 * 255, 2);

    private Ssim() { }

    /** Promedio del SSIM de las ventanas; si los tamaños difieren, {@code b} se escala al tamaño de {@code a}. */
    public static double score(BufferedImage a, BufferedImage b) {
        int w = a.getWidth(), h = a.getHeight();
        double[][] ga = gris(a, w, h);
        double[][] gb = gris(b, w, h);
        double suma = 0;
        int n = 0;
        for (int y = 0; y < h; y += VENTANA) {
            for (int x = 0; x < w; x += VENTANA) {
                suma += ventana(ga, gb, x, y, Math.min(x + VENTANA, w), Math.min(y + VENTANA, h));
                n++;
            }
        }
        return n == 0 ? 1.0 : suma / n;
    }

    private static double ventana(double[][] a, double[][] b, int x0, int y0, int x1, int y1) {
        double cnt = (x1 - x0) * (double) (y1 - y0);
        double ma = 0, mb = 0;
        for (int y = y0; y < y1; y++) for (int x = x0; x < x1; x++) { ma += a[y][x]; mb += b[y][x]; }
        ma /= cnt;
        mb /= cnt;
        double va = 0, vb = 0, cov = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                double da = a[y][x] - ma, db = b[y][x] - mb;
                va += da * da;
                vb += db * db;
                cov += da * db;
            }
        }
        va /= cnt;
        vb /= cnt;
        cov /= cnt;
        return ((2 * ma * mb + C1) * (2 * cov + C2)) / ((ma * ma + mb * mb + C1) * (va + vb + C2));
    }

    private static double[][] gris(BufferedImage src, int w, int h) {
        BufferedImage img = src;
        if (src.getWidth() != w || src.getHeight() != h) {
            img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, 0, 0, w, h, null);
            g.dispose();
        }
        double[][] out = new double[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int p = img.getRGB(x, y);
                out[y][x] = 0.299 * ((p >> 16) & 0xFF) + 0.587 * ((p >> 8) & 0xFF) + 0.114 * (p & 0xFF);
            }
        }
        return out;
    }
}
