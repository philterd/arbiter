package ai.philterd.arbiter.dto;

/**
 * Patch payload for a span. {@code reason} is required only when the change overturns
 * a prior approval set by a different reviewer (status moving out of APPROVED); it is
 * recorded on the audit log entry for that change.
 */
public record SpanUpdateRequest(String status, String type, String reason) {
    public SpanUpdateRequest(final String status, final String type) {
        this(status, type, null);
    }
}
