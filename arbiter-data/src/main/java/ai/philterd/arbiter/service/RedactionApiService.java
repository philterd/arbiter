package ai.philterd.arbiter.service;

import java.io.IOException;

public interface RedactionApiService {
    void processDocument(String documentId, String text) throws IOException;
    String finalizeRedaction(String documentId) throws IOException;
}
