package com.aicompany.core.agent.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

class SsimTest {

    private static BufferedImage image(int w, int h, Color fondo, Color rect) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(fondo);
        g.fillRect(0, 0, w, h);
        if (rect != null) {
            g.setColor(rect);
            g.fillRect(8, 8, w / 2, h / 2);
        }
        g.dispose();
        return img;
    }

    @Test
    void identicalImagesScoreOne() {
        BufferedImage a = image(64, 64, Color.WHITE, Color.BLACK);
        assertEquals(1.0, Ssim.score(a, image(64, 64, Color.WHITE, Color.BLACK)), 1e-9);
    }

    @Test
    void differentImagesScoreBelowThreshold() {
        double s = Ssim.score(image(64, 64, Color.WHITE, Color.BLACK), image(64, 64, Color.BLACK, Color.WHITE));
        assertTrue(s < 0.5, "ssim=" + s);
    }

    @Test
    void differentSizeIsScaledToFirst() {
        double s = Ssim.score(image(64, 64, Color.WHITE, null), image(32, 32, Color.WHITE, null));
        assertEquals(1.0, s, 1e-9);
    }
}
