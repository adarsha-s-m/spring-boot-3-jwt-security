package com.alibou.security.gemini;

import com.google.cloud.generativeai.v1.Content;
import com.google.cloud.generativeai.v1.GenerateContentRequest;
import com.google.cloud.generativeai.v1.GenerateContentResponse;
import com.google.cloud.generativeai.v1.GenerativeModel; // This class might be from the new SDK too
import com.google.cloud.generativeai.v1.Part;
import com.google.cloud.generativeai.v1.PredictionServiceClient;
import com.google.cloud.generativeai.v1.PredictionServiceSettings;
import com.google.cloud.generativeai.v1.StreamGenerateContentRequest; // Specific request for streaming
import com.google.cloud.generativeai.v1.StreamGenerateContentResponse; // Specific response for streaming

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Iterator;

@Service
@Slf4j
public class GeminiService {

    @Value("${GEMINI_API_KEY}")
    private String geminiApiKey;

    // Project ID and Location are not typically used with google-generativeai SDK when using API key
    // @Value("${GEMINI_PROJECT_ID}")
    // private String projectId;
    // @Value("${GEMINI_LOCATION}")
    // private String location;

    @Value("${GEMINI_MODEL_NAME}") // e.g., "gemini-pro" or "gemini-1.5-pro-latest"
    private String modelName;


    public StreamingResponseBody streamQuery(String userQuery) {
        if (geminiApiKey == null || geminiApiKey.isBlank()) {
            log.error("Gemini API key is not configured.");
            return outputStream -> {
                outputStream.write("Error: Gemini API key not configured.".getBytes(StandardCharsets.UTF_8));
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
            PredictionServiceSettings settings = null;
            try {
                settings = PredictionServiceSettings.newBuilder()
                        .setApiKey(geminiApiKey)
                        .build();
            } catch (IOException e) {
                log.error("Error building PredictionServiceSettings: " + e.getMessage(), e);
                writeErrorToStream(outputStream, "Error configuring Gemini client: " + e.getMessage());
                return;
            }

            try (PredictionServiceClient predictionServiceClient = PredictionServiceClient.create(settings)) {
                String fullModelName = String.format("models/%s", modelName);

                Content content = Content.newBuilder()
                        .addParts(Part.newBuilder().setText(userQuery))
                        .build();
                
                // Create a GenerateContentRequest for streaming
                // Note: The user's sample used generateContent(model, Collections.singletonList(content)) for unary.
                // For streaming, the method is often streamGenerateContent(request) or similar.
                // The `com.google.cloud.generativeai.v1.GenerativeModel` class from this SDK
                // is actually the preferred way to call for streaming.

                com.google.cloud.generativeai.v1.GenerativeModel generativeAiModel =
                    new com.google.cloud.generativeai.v1.GenerativeModel(fullModelName, predictionServiceClient);

                // The method on GenerativeModel for streaming is generateContentStream
                Iterator<GenerateContentResponse> responseIterator = generativeAiModel.generateContentStream(content);

                while (responseIterator.hasNext()) {
                    GenerateContentResponse response = responseIterator.next();
                    if (response.getCandidatesCount() > 0) {
                        // Process parts from the first candidate
                        response.getCandidates(0).getContent().getPartsList().forEach(part -> {
                            if (part.hasText()) {
                                try {
                                    outputStream.write(part.getText().getBytes(StandardCharsets.UTF_8));
                                    outputStream.flush();
                                } catch (IOException e) {
                                    log.error("Error writing to output stream", e);
                                    // Hard to recover here, rethrow to stop processing
                                    throw new RuntimeException("Error writing to output stream", e);
                                }
                            }
                        });
                    }
                }
            } catch (Exception e) {
                log.error("Error during Gemini API call or streaming: " + e.getMessage(), e);
                // Attempt to write an error message to the client's stream
                writeErrorToStream(outputStream, "Error processing your request: " + e.getMessage());
            } finally {
                try {
                    outputStream.close();
                } catch (IOException e) {
                    log.error("Error closing output stream", e);
                }
            }
        };
    }

    private void writeErrorToStream(OutputStream outputStream, String errorMessage) {
        try {
            outputStream.write(errorMessage.getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
        } catch (IOException ex) {
            log.error("Error writing error message to output stream", ex);
        }
    }
}
