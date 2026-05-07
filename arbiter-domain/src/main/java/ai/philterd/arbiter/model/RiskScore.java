package ai.philterd.arbiter.model;

import java.util.List;
import java.util.Map;

public final class RiskScore {

    public static double compute(final List<Span> spans,
                                 final String originalText,
                                 final Map<String, Integer> weightOverrides) {
        final int wordCount = countWords(originalText);
        if (wordCount <= 0) return 0.0;
        if (spans == null || spans.isEmpty()) return 0.0;

        double weightedSum = 0.0;
        int unresolved = 0;
        for (Span s : spans) {
            final int weight = PiiWeights.weightFor(s.getType(), weightOverrides);
            final double confidence = clampConfidence(s.getConfidence());
            weightedSum += weight * (1.0 - confidence);
            if ("PENDING".equals(s.getStatus())) {
                unresolved++;
            }
        }

        final double numerator = weightedSum + penalty(unresolved);
        final double normalized = numerator / wordCount;
        return Math.min(1.0, normalized);
    }

    private static double penalty(final int unresolvedCount) {
        return unresolvedCount;
    }

    private static double clampConfidence(final double c) {
        if (Double.isNaN(c)) return 0.0;
        if (c < 0.0) return 0.0;
        if (c > 1.0) return 1.0;
        return c;
    }

    static int countWords(final String text) {
        if (text == null || text.isBlank()) return 0;
        int count = 0;
        boolean inWord = false;
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                if (inWord) {
                    count++;
                    inWord = false;
                }
            } else {
                inWord = true;
            }
        }
        if (inWord) count++;
        return count;
    }

    private RiskScore() {}
}
