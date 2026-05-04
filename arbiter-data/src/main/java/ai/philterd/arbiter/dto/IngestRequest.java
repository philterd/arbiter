package ai.philterd.arbiter.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record IngestRequest(@NotBlank String name, @NotBlank String batchId, @NotNull String text) {
}
