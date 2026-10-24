package com.meterbook.identity.sso;

import com.meterbook.identity.web.ApiException;
import java.io.ByteArrayInputStream;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;

/**
 * An IdP's X.509 signing certificate, accepted as PEM or base64 DER and normalised to the
 * single-line base64 DER Keycloak expects. Malformed and expired certificates are rejected up
 * front, so a broken connection is reported to the admin who creates it rather than to every user
 * who signs in.
 */
final class SigningCertificate {

  private static final String PARAM = "saml.signing_certificate";

  private SigningCertificate() {}

  static String normalise(String input, Clock clock) {
    String base64 =
        input
            .replace("-----BEGIN CERTIFICATE-----", "")
            .replace("-----END CERTIFICATE-----", "")
            .replaceAll("\\s", "");
    byte[] der;
    try {
      der = Base64.getDecoder().decode(base64);
    } catch (IllegalArgumentException e) {
      throw invalid("certificate_malformed", "signing_certificate is not PEM or base64 DER");
    }
    X509Certificate certificate;
    try {
      certificate =
          (X509Certificate)
              CertificateFactory.getInstance("X.509")
                  .generateCertificate(new ByteArrayInputStream(der));
    } catch (CertificateException | ClassCastException e) {
      throw invalid("certificate_malformed", "signing_certificate is not an X.509 certificate");
    }
    Instant now = clock.instant();
    Instant notAfter = certificate.getNotAfter().toInstant();
    if (notAfter.isBefore(now)) {
      throw invalid("certificate_expired", "signing_certificate expired on " + notAfter);
    }
    if (certificate.getNotBefore().toInstant().isAfter(now)) {
      throw invalid("certificate_not_yet_valid", "signing_certificate is not valid yet");
    }
    return Base64.getEncoder().encodeToString(der);
  }

  private static ApiException invalid(String code, String message) {
    return ApiException.invalid(code, message, PARAM);
  }
}
