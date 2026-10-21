package com.urlshortener.api.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.urlshortener.api.config.AppProperties;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UrlValidatorTest {

  private UrlValidator validator;

  @BeforeEach
  void setUp() {
    AppProperties props =
        new AppProperties(
            "http://sho.rt",
            List.of("k"),
            null,
            null,
            null,
            null,
            null,
            new AppProperties.Url(2048, List.of("localhost", "169.254.169.254")));
    validator = new UrlValidator(props);
  }

  @Test
  void normalisesSchemeHostAndDefaultPort() {
    assertThat(validator.validateAndNormalize("HTTPS://Example.COM:443/Path?q=1#frag"))
        .isEqualTo("https://example.com/Path?q=1#frag");
    assertThat(validator.validateAndNormalize("http://example.com"))
        .isEqualTo("http://example.com/");
    assertThat(validator.validateAndNormalize("http://example.com:8080/x"))
        .isEqualTo("http://example.com:8080/x");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "ftp://example.com/file",
        "javascript:alert(1)",
        "example.com/no-scheme",
        "http://user:pass@example.com/",
        "http://localhost/admin",
        "http://127.0.0.1/",
        "http://10.0.0.5/",
        "http://172.20.1.1/",
        "http://192.168.1.1/",
        "http://169.254.169.254/latest/meta-data/",
        "http://[::1]/",
        "http://service.internal/",
        "http://sho.rt/abc123",
        "",
        "   "
      })
  void rejectsUnsafeUrls(String url) {
    assertThatThrownBy(() -> validator.validateAndNormalize(url))
        .isInstanceOf(UrlValidator.InvalidUrlException.class);
  }

  @Test
  void rejectsOverlongUrls() {
    String url = "https://example.com/" + "a".repeat(2048);
    assertThatThrownBy(() -> validator.validateAndNormalize(url))
        .isInstanceOf(UrlValidator.InvalidUrlException.class)
        .hasMessageContaining("exceeds");
  }

  @Test
  void acceptsPublicUrls() {
    assertThat(validator.validateAndNormalize("https://aws.amazon.com/dynamodb/"))
        .isEqualTo("https://aws.amazon.com/dynamodb/");
    assertThat(validator.validateAndNormalize("http://8.8.8.8/")).isEqualTo("http://8.8.8.8/");
  }
}
