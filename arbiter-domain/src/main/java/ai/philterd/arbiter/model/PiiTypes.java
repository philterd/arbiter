package ai.philterd.arbiter.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class PiiTypes {

    private static final Map<String, String> SPECIAL_LABELS = Map.ofEntries(
            Map.entry("ssn", "SSN"),
            Map.entry("vin", "VIN"),
            Map.entry("url", "URL"),
            Map.entry("id", "Identifier"),
            Map.entry("iban-code", "IBAN Code"),
            Map.entry("ip-address", "IP Address"),
            Map.entry("mac-address", "MAC Address"),
            Map.entry("pheye", "PHEye")
    );

    private static final List<String> VALUES = List.of(
            "age",
            "bank-routing-number",
            "bitcoin-address",
            "city",
            "county",
            "credit-card",
            "currency",
            "custom-dictionary",
            "date",
            "drivers-license-number",
            "email-address",
            "first-name",
            "hospital",
            "hospital-abbreviation",
            "iban-code",
            "id",
            "ip-address",
            "mac-address",
            "medical-condition",
            "other",
            "passport-number",
            "person",
            "pheye",
            "phone-number",
            "phone-number-extension",
            "physician-name",
            "section",
            "ssn",
            "state",
            "state-abbreviation",
            "street-address",
            "surname",
            "tracking-number",
            "url",
            "vin",
            "zip-code"
    );

    public static List<String> values() {
        return VALUES;
    }

    public static boolean isValid(final String value) {
        return value != null && VALUES.contains(value);
    }

    public static String labelFor(final String value) {
        if (value == null) return "";
        final String special = SPECIAL_LABELS.get(value);
        if (special != null) return special;
        final StringBuilder sb = new StringBuilder(value.length());
        boolean nextUpper = true;
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (c == '-' || c == '_') {
                sb.append(' ');
                nextUpper = true;
            } else if (nextUpper) {
                sb.append(Character.toUpperCase(c));
                nextUpper = false;
            } else {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    public static Map<String, String> labels() {
        final Map<String, String> map = new LinkedHashMap<>();
        VALUES.stream()
                .sorted((a, b) -> labelFor(a).toLowerCase(Locale.ROOT)
                        .compareTo(labelFor(b).toLowerCase(Locale.ROOT)))
                .forEach(v -> map.put(v, labelFor(v)));
        return Collections.unmodifiableMap(map);
    }

    private PiiTypes() {}
}
