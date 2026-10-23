package com.meterbook.identity.sso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.meterbook.identity.web.ApiException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class SigningCertificateTest {

  static String pem(String name) {
    try (InputStream in = SigningCertificateTest.class.getResourceAsStream("/certs/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.US_ASCII);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  private static final Clock NOW =
      Clock.fixed(Instant.parse("2026-10-24T00:00:00Z"), ZoneOffset.UTC);

  @Test
  void accepts_pem_and_returns_single_line_base64_der() {
    String normalised = SigningCertificate.normalise(pem("idp-valid.pem"), NOW);

    assertThat(normalised).doesNotContain("BEGIN", "\n", " ");
    assertThat(Base64.getDecoder().decode(normalised)).isNotEmpty();
  }

  @Test
  void accepts_bare_base64_der_too() {
    String once = SigningCertificate.normalise(pem("idp-valid.pem"), NOW);

    assertThat(SigningCertificate.normalise(once, NOW)).isEqualTo(once);
  }

  @Test
  void rejects_what_is_not_a_certificate() {
    assertThatThrownBy(() -> SigningCertificate.normalise("not base64 at all!", NOW))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("not PEM");
    assertThatThrownBy(
            () ->
                SigningCertificate.normalise(
                    Base64.getEncoder().encodeToString("hello".getBytes(StandardCharsets.UTF_8)),
                    NOW))
        .isInstanceOf(ApiException.class)
        .hasMessageContaining("not an X.509");
  }

  @Test
  void rejects_expired_and_not_yet_valid_certificates() {
    assertThatThrownBy(() -> SigningCertificate.normalise(pem("idp-expired.pem"), NOW))
        .isInstanceOf(ApiException.class)
        .satisfies(e -> assertThat(((ApiException) e).code()).isEqualTo("certificate_expired"));
    Clock longAgo = Clock.fixed(Instant.parse("2001-01-01T00:00:00Z"), ZoneOffset.UTC);
    assertThatThrownBy(() -> SigningCertificate.normalise(pem("idp-valid.pem"), longAgo))
        .satisfies(
            e -> assertThat(((ApiException) e).code()).isEqualTo("certificate_not_yet_valid"));
  }
}
