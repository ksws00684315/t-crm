package com.tcrm.admin.security.service;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.SecureRandom;
import java.util.random.RandomGenerator;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;

/**
 * Draws login captcha images with the JDK only (java.awt, headless), so no third-party library is
 * needed. The alphabet leaves out look-alike characters (0/O, 1/I/L) because answers are compared
 * case-insensitively.
 */
@Component
public class CaptchaImageGenerator {
  static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
  static final int LENGTH = 4;
  static final int WIDTH = 120;
  static final int HEIGHT = 40;
  private static final String[] FONTS = {Font.SANS_SERIF, Font.SERIF, Font.MONOSPACED};
  private final RandomGenerator random;

  public CaptchaImageGenerator() {
    this(new SecureRandom());
  }

  CaptchaImageGenerator(RandomGenerator random) {
    this.random = random;
  }

  public record Captcha(String answer, byte[] png) {}

  public Captcha generate() {
    StringBuilder answer = new StringBuilder(LENGTH);
    for (int i = 0; i < LENGTH; i++) {
      answer.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
    }
    return new Captcha(answer.toString(), draw(answer.toString()));
  }

  byte[] draw(String text) {
    BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = image.createGraphics();
    try {
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g.setRenderingHint(
          RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      // Light background on purpose: the image keeps the same look in the light and dark theme.
      g.setColor(new Color(244, 246, 250));
      g.fillRect(0, 0, WIDTH, HEIGHT);
      for (int i = 0; i < 40; i++) {
        g.setColor(color(150, 220));
        g.fillRect(random.nextInt(WIDTH), random.nextInt(HEIGHT), 2, 2);
      }
      int step = (WIDTH - 16) / text.length();
      for (int i = 0; i < text.length(); i++) {
        g.setColor(color(20, 110));
        g.setFont(new Font(FONTS[random.nextInt(FONTS.length)], Font.BOLD, 24 + random.nextInt(5)));
        AffineTransform saved = g.getTransform();
        int x = 8 + i * step + random.nextInt(4);
        int y = 29 + random.nextInt(6);
        g.rotate(Math.toRadians(random.nextInt(51) - 25), x + 8, y - 10);
        g.drawString(String.valueOf(text.charAt(i)), x, y);
        g.setTransform(saved);
      }
      for (int i = 0; i < 3; i++) {
        g.setColor(color(110, 200));
        g.setStroke(new BasicStroke(1f + random.nextFloat() * 0.8f));
        g.drawLine(
            random.nextInt(WIDTH / 3),
            random.nextInt(HEIGHT),
            WIDTH - random.nextInt(WIDTH / 3),
            random.nextInt(HEIGHT));
      }
    } finally {
      g.dispose();
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      ImageIO.write(image, "png", out);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return out.toByteArray();
  }

  private Color color(int min, int max) {
    return new Color(
        min + random.nextInt(max - min),
        min + random.nextInt(max - min),
        min + random.nextInt(max - min));
  }
}
