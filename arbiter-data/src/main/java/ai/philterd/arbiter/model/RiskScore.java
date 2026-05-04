package ai.philterd.arbiter.model;

import java.util.List;
import java.util.Map;

public final class RiskScore {

    public static double compute(List<Span> spans,
                                 String originalText,
                                 Map<String, Integer> weightOverrides) {
        int wordCount = countWords(originalText);
        if (wordCount <= 0) return 0.0;
        if (spans == null || spans.isEmpty()) return 0.0;

        double weightedSum = 0.0;
        int unresolved = 0;
        for (Span s : spans) {
            int weight = PiiWeights.weightFor(s.getType(), weightOverrides);
            double confidence = clampConfidence(s.getConfidence());
            weightedSum += weight * (1.0 - confidence);
            if ("PENDING".equals(s.getStatus())) {
                unresolved++;
            }
        }

        double numerator = weightedSum + penalty(unresolved);
        double normalized = numerator / wordCount;
        return Math.min(1.0, normalized);
    }

    private static double penalty(int unresolvedCount) {
        return unresolvedCount;
    }

    private static double clampConfidence(double c) {
        if (Double.isNaN(c)) return 0.0;
        if (c < 0.0) return 0.0;
        if (c > 1.0) return 1.0;
        return c;
    }

    static int countWords(String text) {
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
