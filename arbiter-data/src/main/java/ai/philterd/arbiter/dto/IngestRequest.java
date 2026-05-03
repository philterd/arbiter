package ai.philterd.arbiter.dto;

import jakarta.validation.constraints.NotNull;

public record IngestRequest(@NotNull String name, @NotNull String text) {
}
