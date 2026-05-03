package ai.philterd.arbiter.dto;

import jakarta.validation.constraints.NotNull;

public record SpanStatusRequest(@NotNull String status) {
}
