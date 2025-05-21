package com.alibou.security.gemini;

import com.google.auth.oauth2.GoogleCredentials;
// import com.google.auth.oauth2.AccessToken; // May not be needed if setApiKey is sufficient
import com.google.cloud.vertexai.VertexAI;
import com.google.cloud.vertexai.VertexAiOptions;
import com.google.cloud.vertexai.api.GenerateContentResponse;
import com.google.cloud.vertexai.generativeai.GenerativeModel;
import com.google.cloud.vertexai.generativeai.ResponseStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections; // Required for Collections.singletonList

@Service
@Slf4j
public class GeminiService {

    @Value("${GEMINI_API_KEY}")
    private String geminiApiKey;

    @Value("${GEMINI_PROJECT_ID}")
    private String projectId;

    @Value("${GEMINI_LOCATION}")
    private String location;

    @Value("${GEMINI_MODEL_NAME}")
    private String modelName;

    public StreamingResponseBody streamQuery(String userQuery) {
        // ... (parameter checks remain the same)
        if (geminiApiKey == null || geminiApiKey.isBlank()) {
            log.error("Gemini API key is not configured.");
            return outputStream -> {
                outputStream.write("Error: Gemini API key not configured.".getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
            };
        }
        if (projectId == null || projectId.isBlank()) {
            log.error("Gemini Project ID is not configured.");
            return outputStream -> {
                outputStream.write("Error: Gemini Project ID not configured.".getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
            };
        }
        if (location == null || location.isBlank()) {
            log.error("Gemini Location is not configured.");
            return outputStream -> {
                outputStream.write("Error: Gemini Location not configured.".getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
            };
        }
        if (modelName == null || modelName.isBlank()) {
            log.error("Gemini Model Name is not configured.");
            return outputStream -> {
                outputStream.write("Error: Gemini Model Name not configured.".getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
            };
        }

        return outputStream -> {
            try {
                GoogleCredentials credentials = GoogleCredentials.newBuilder()
                    .setApiKey(geminiApiKey)
                    // Optionally, if API requires specific scopes, though often not needed for API key auth
                    // .createScoped(Collections.singletonList("https://www.googleapis.com/auth/cloud-platform"))
                    .build();

                VertexAiOptions vertexAiOptions = VertexAiOptions.newBuilder()
                    .setProjectId(projectId)
                    .setLocation(location)
                    .setCredentials(credentials)
                    .build();

                try (VertexAI vertexAi = new VertexAI(vertexAiOptions)) {
                    GenerativeModel model = new GenerativeModel(modelName, vertexAi);
                    ResponseStream<GenerateContentResponse> responseStream = model.generateContentStream(userQuery);

                    for (GenerateContentResponse response : responseStream) {
                        response.getCandidatesList().forEach(candidate -> {
                            candidate.getContent().getPartsList().forEach(part -> {
                                if (part.hasText()) {
                                    try {
                                        outputStream.write(part.getText().getBytes(StandardCharsets.UTF_8));
                                        outputStream.flush();
                                    } catch (IOException e) {
                                        log.error("Error writing to output stream", e);
                                        throw new RuntimeException("Error writing to output stream", e);
                                    }
                                }
                            });
                        });
                    }
                }
            } catch (Exception e) {
                log.error("Error during Gemini API call or streaming", e);
                try {
                    outputStream.write(("Error: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
                    outputStream.flush();
                } catch (IOException ex) {
                    log.error("Error writing error message to output stream", ex);
                }
            } finally {
                try {
                    outputStream.close();
                } catch (IOException e) {
                    log.error("Error closing output stream", e);
                }
            }
        };
    }
}
