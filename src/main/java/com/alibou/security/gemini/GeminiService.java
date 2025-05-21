package com.alibou.security.gemini;

// Core Java and Spring
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

// Logging
import lombok.extern.slf4j.Slf4j;

// Google Cloud GAX and Auth libraries (assuming these classes exist)
import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.api.gax.rpc.FixedHeaderProvider;
import com.google.api.gax.rpc.HeaderProvider;
// Assuming a settings class exists, e.g., VertexAISettings
import com.google.cloud.vertexai.VertexAI; // Main client
import com.google.cloud.vertexai.VertexAISettings; // ASSUMED: Settings class for VertexAI
// Potentially, if VertexAISettings is not found, this might be GenerativeModelSettings or similar
// import com.google.cloud.vertexai.generativeai.GenerativeModelSettings;

// Vertex AI specific classes
import com.google.cloud.vertexai.api.GenerateContentResponse;
import com.google.cloud.vertexai.generativeai.GenerativeModel;
import com.google.cloud.vertexai.generativeai.ResponseStream;


@Service
@Slf4j
public class GeminiService {

    @Value("${GEMINI_API_KEY}")
    private String geminiApiKey;

    @Value("${GEMINI_PROJECT_ID}")
    private String projectId;

    @Value("${GEMINI_LOCATION}")
    private String location; // e.g., "us-central1"

    @Value("${GEMINI_MODEL_NAME}")
    private String modelName; // e.g., "gemini-pro"

    public StreamingResponseBody streamQuery(String userQuery) {
        // Parameter checks (API key, projectId, location, modelName)
        if (geminiApiKey == null || geminiApiKey.isBlank()) {
            // ... error handling ...
            return outputStream -> outputStream.write("Error: API key missing".getBytes());
        }
        if (projectId == null || projectId.isBlank()) {
            // ... error handling ...
            return outputStream -> outputStream.write("Error: Project ID missing".getBytes());
        }
        if (location == null || location.isBlank()) {
            // ... error handling ...
            return outputStream -> outputStream.write("Error: Location missing".getBytes());
        }
        if (modelName == null || modelName.isBlank()) {
            // ... error handling ...
            return outputStream -> outputStream.write("Error: Model name missing".getBytes());
        }

        return outputStream -> {
            try {
                String endpoint = String.format("%s-aiplatform.googleapis.com:443", location);

                Map<String, String> headers = new HashMap<>();
                headers.put("x-goog-api-key", geminiApiKey);
                HeaderProvider headerProvider = FixedHeaderProvider.create(headers);

                // Attempt to use VertexAISettings
                // This assumes VertexAISettings and its builder pattern exist.
                VertexAISettings.Builder settingsBuilder = VertexAISettings.newBuilder()
                        .setEndpoint(endpoint)
                        .setHeaderProvider(headerProvider)
                        .setCredentialsProvider(FixedCredentialsProvider.create(null));
                
                // If there's a specific transport, like "grpc" or "rest"
                // settingsBuilder.setTransportChannelProvider(
                //     VertexAISettings.defaultGrpcTransportProviderBuilder()
                //         .setHeaderProvider(headerProvider) // Some settings allow header provider per transport
                //         .build());
                // The above is an example if granular transport control is needed/available.

                VertexAISettings settings = settingsBuilder.build();

                try (VertexAI vertexAi = VertexAI.create(settings)) { // Assumes VertexAI.create(settings) exists
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
            } catch (NoClassDefFoundError | ClassNotFoundException e) {
                log.error("SDK class not found, likely VertexAISettings or related: " + e.getMessage(), e);
                try {
                    outputStream.write(("Error: A required SDK class was not found. This might indicate an issue with the SDK version or the specific classes used for configuration (e.g., VertexAISettings). Details: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
                    outputStream.flush();
                } catch (IOException ex) { /* ignore */ }
            } catch (Exception e) {
                log.error("Error during Gemini API call or streaming: " + e.getMessage(), e);
                try {
                    // Try to send a generic error message to the client
                    outputStream.write(("Error processing your request: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
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
