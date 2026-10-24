package com.meterbook.identity.scim;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The filters SCIM clients use to find a resource before creating it: {@code <attribute> eq
 * "<value>"} (RFC 7644 §3.4.2.2). Anything else is rejected as {@code invalidFilter} rather than
 * silently ignored, which would return every resource.
 */
record ScimFilter(String attribute, String value) {

  private static final Pattern EQ =
      Pattern.compile("^\\s*([A-Za-z][A-Za-z0-9.]*)\\s+(?i:eq)\\s+\"((?:[^\"\\\\]|\\\\.)*)\"\\s*$");

  static Optional<ScimFilter> parse(String filter, Set<String> attributes) {
    if (filter == null || filter.isBlank()) {
      return Optional.empty();
    }
    Matcher m = EQ.matcher(filter);
    if (!m.matches()) {
      throw ScimException.invalidFilter("only '<attribute> eq \"<value>\"' filters are supported");
    }
    String attribute = m.group(1);
    String canonical =
        attributes.stream()
            .filter(a -> a.equalsIgnoreCase(attribute))
            .findFirst()
            .orElseThrow(
                () ->
                    ScimException.invalidFilter(
                        "cannot filter on " + attribute.toLowerCase(Locale.ROOT)));
    return Optional.of(new ScimFilter(canonical, m.group(2).replaceAll("\\\\(.)", "$1")));
  }
}
