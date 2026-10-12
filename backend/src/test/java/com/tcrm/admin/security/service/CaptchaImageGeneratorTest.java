package com.tcrm.admin.security.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class CaptchaImageGeneratorTest {
  private final CaptchaImageGenerator generator = new CaptchaImageGenerator();

  @Test
  void shouldUseFourUnambiguousCharacters_whenGenerating() {
    for (int i = 0; i < 200; i++) {
      String answer = generator.generate().answer();
      assertThat(answer).hasSize(CaptchaImageGenerator.LENGTH);
      assertThat(answer.chars()).allMatch(c -> CaptchaImageGenerator.ALPHABET.indexOf(c) >= 0);
      assertThat(answer).doesNotContainPattern("[01ILO]");
    }
  }

  @Test
  void shouldProducePngOfFixedSize_whenGenerating() throws Exception {
    var image = ImageIO.read(new ByteArrayInputStream(generator.generate().png()));
    assertThat(image).isNotNull();
    assertThat(image.getWidth()).isEqualTo(CaptchaImageGenerator.WIDTH);
    assertThat(image.getHeight()).isEqualTo(CaptchaImageGenerator.HEIGHT);
    // Small enough to inline as a data URL.
    assertThat(generator.generate().png().length).isLessThan(16 * 1024);
  }

  @Test
  void shouldVaryAnswers_whenGeneratingRepeatedly() {
    Set<String> answers = new HashSet<>();
    for (int i = 0; i < 50; i++) {
      answers.add(generator.generate().answer());
    }
    assertThat(answers).hasSizeGreaterThan(40);
  }

  @Test
  void shouldDrawDifferentPixels_forDifferentTexts() {
    var fixed = new CaptchaImageGenerator(new Random(1));
    var same = new CaptchaImageGenerator(new Random(1));
    assertThat(fixed.draw("AB7K")).isEqualTo(same.draw("AB7K"));
    assertThat(new CaptchaImageGenerator(new Random(1)).draw("XY23"))
        .isNotEqualTo(fixed.draw("AB7K"));
  }
}
