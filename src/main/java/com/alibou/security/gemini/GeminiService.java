package com.alibou.security.gemini;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.JsonObjectParser;
import com.google.api.client.json.gson.GsonFactory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

@Service
@Slf4j
public class GeminiService {

    @Value("${GEMINI_API_KEY}")
    private String geminiApiKey;

    @Value("${GEMINI_MODEL_NAME}") // e.g., "gemini-pro" or "gemini-1.5-flash-latest"
    private String modelName;

    @Value("${GEMINI_API_BASE_URL:https://generativelanguage.googleapis.com}")
    private String geminiApiBaseUrl;

    private static final HttpTransport HTTP_TRANSPORT = new NetHttpTransport();
    // This GoogleJsonFactory is for the HttpRequest's internal parser,
    // often used for parsing initial error responses from the HTTP client itself,
    // not for our main stream processing.
    private static final JsonFactory GOOGLE_JSON_FACTORY = new GsonFactory();
    private final ObjectMapper jacksonObjectMapper; // Jackson's ObjectMapper for our main parsing logic

    public GeminiService(ObjectMapper jacksonObjectMapper) {
        this.jacksonObjectMapper = jacksonObjectMapper;
    }

    public StreamingResponseBody streamQuery(String userQuery) {
        if (geminiApiKey == null || geminiApiKey.isBlank()) {
            log.error("Gemini API key is not configured.");
            return outputStream -> writeErrorToStream(outputStream, "Error: Gemini API key not configured.");
        }
        if (modelName == null || modelName.isBlank()) {
            log.error("Gemini Model Name is not configured.");
            return outputStream -> writeErrorToStream(outputStream, "Error: Gemini Model Name not configured.");
        }

        String requestBodyJson;
        try {
            JsonNode partsNode = jacksonObjectMapper.createArrayNode().add(jacksonObjectMapper.createObjectNode().put("text", userQuery));
            JsonNode contentsNode = jacksonObjectMapper.createArrayNode().add(jacksonObjectMapper.createObjectNode().set("parts", partsNode));
            requestBodyJson = jacksonObjectMapper.writeValueAsString(jacksonObjectMapper.createObjectNode().set("contents", contentsNode));
        } catch (JsonProcessingException e) {
            log.error("Error creating JSON request body: {}", e.getMessage(), e);
            return outputStream -> writeErrorToStream(outputStream, "Error creating request: " + e.getMessage());
        }

        // Endpoint for streaming - NOT using alt=sse
        GenericUrl genericUrl = new GenericUrl(String.format("%s/v1beta/models/%s:streamGenerateContent",
            geminiApiBaseUrl, modelName));
        genericUrl.put("key", geminiApiKey);

        HttpRequestFactory requestFactory = HTTP_TRANSPORT.createRequestFactory(
            request -> {
                request.setParser(new JsonObjectParser(GOOGLE_JSON_FACTORY));
                // Optional: Set timeouts
                // request.setConnectTimeout(15000);
                // request.setReadTimeout(60000); // Longer read timeout for streams
            });

        HttpRequest httpRequest;
        try {
            ByteArrayContent content = new ByteArrayContent("application/json", requestBodyJson.getBytes(StandardCharsets.UTF_8));
            httpRequest = requestFactory.buildPostRequest(genericUrl, content);
            httpRequest.getHeaders().setContentType("application/json");
        } catch (IOException e) {
            log.error("Error building HTTP request: {}", e.getMessage(), e);
            return outputStream -> writeErrorToStream(outputStream, "Error building request: " + e.getMessage());
        }

        return outputStream -> {
            HttpResponse httpResponse = null;
            InputStream responseStream = null; // Must be declared here for visibility in finally
            try {
                httpResponse = httpRequest.execute();

                if (!httpResponse.isSuccessStatusCode()) {
                    String errorBody = httpResponse.parseAsString(); // Best effort to get error details
                    log.error("Gemini API error. Status: {}, Body: {}", httpResponse.getStatusCode(), errorBody);
                    writeErrorToStream(outputStream, "Error from Gemini API: " + httpResponse.getStatusCode() + " - " + errorBody);
                    return;
                }

                responseStream = httpResponse.getContent();

                // Use Jackson's streaming JsonParser for robust parsing
                try (JsonParser parser = jacksonObjectMapper.getFactory().createParser(responseStream)) {
                    // The stream should be a JSON array: '['
                    JsonToken firstToken = parser.nextToken();
                    if (firstToken != JsonToken.START_ARRAY) {
                        String actualContentStart = "N/A";
                        if (parser.hasCurrentToken() && parser.currentToken() != null) {
                            actualContentStart = parser.getText() != null ? parser.getText().substring(0, Math.min(parser.getText().length(), 100)) : "Token has no text";
                        } else if (firstToken != null) {
                            actualContentStart = "First token was " + firstToken.toString() + " but no text available.";
                        }
                        log.error("Gemini stream did not start with a JSON array as expected. First token: {}. Actual content preview: '{}'", firstToken, actualContentStart);
                        writeErrorToStream(outputStream, "Error: Unexpected stream format (expected JSON array start).");
                        return;
                    }

                    // Iterate through the JSON objects within the array
                    // parser.nextToken() advances to the next token.
                    // If it's START_OBJECT, we are at the beginning of a new element in the array.
                    while (parser.nextToken() == JsonToken.START_OBJECT) {
                        // Let ObjectMapper read the entire current JSON object from the stream
                        JsonNode rootNode = jacksonObjectMapper.readTree(parser);
                        // log.debug("Parsed object from stream: {}", rootNode.toString()); // Uncomment for verbose debugging

                        if (rootNode.has("candidates")) {
                            JsonNode candidates = rootNode.get("candidates");
                            if (candidates != null && candidates.isArray()) {
                                for (JsonNode candidate : candidates) {
                                    if (candidate != null && candidate.has("content") && candidate.get("content").has("parts")) {
                                        JsonNode parts = candidate.get("content").get("parts");
                                        if (parts != null && parts.isArray()) {
                                            for (JsonNode part : parts) {
                                                if (part != null && part.has("text")) {
                                                    String text = part.get("text").asText();
                                                    outputStream.write(text.getBytes(StandardCharsets.UTF_8));
                                                    outputStream.flush(); // Send chunk immediately
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } else if (rootNode.has("error")) {
                            String errorMessage = "API Error object in stream";
                            if (rootNode.get("error").has("message")) {
                                errorMessage += ": " + rootNode.get("error").get("message").asText("Unknown error");
                            }
                            log.error("Error object received in Gemini stream data: {}", errorMessage);
                            // writeErrorToStream(outputStream, "Error from AI: " + errorMessage); // Optional
                        } else if (rootNode.has("usageMetadata")) {
                            // log.debug("Received usageMetadata object: {}", rootNode.toString()); // Usually at the end
                        } else {
                            log.warn("Received unexpected JSON object structure in stream: {}", rootNode.toString());
                        }
                    }

                    // After the loop, the current token should be END_ARRAY if the stream was well-formed.
                    if (parser.currentToken() != JsonToken.END_ARRAY) {
                        log.warn("Gemini stream did not end with a JSON END_ARRAY token as expected. Current token: {}", parser.currentToken());
                    }
                }

            } catch (JsonProcessingException e) { // Catches errors from jacksonObjectMapper.readTree or parser setup
                log.error("Error parsing JSON stream from Gemini: {}", e.getMessage(), e);
                writeErrorToStream(outputStream, "Error parsing response: " + e.getMessage());
            } catch (IOException e) { // Catches errors from httpResponse.execute(), .getContent(), or stream I/O
                log.error("Error during Gemini API HTTP call or streaming: {}", e.getMessage(), e);
                writeErrorToStream(outputStream, "Error processing your request: " + e.getMessage());
            } finally {
                // responseStream is closed by the try-with-resources block for JsonParser
                // httpResponse.disconnect() is not typically needed for NetHttpTransport after stream is consumed/closed.
                if (outputStream != null) {
                    try {
                        outputStream.close(); // Ensure the client output stream is closed
                    } catch (IOException e) {
                        log.error("Error closing output stream to client", e);
                    }
                }
            }
        };
    }

    private void writeErrorToStream(OutputStream outputStream, String errorMessage) {
        try {
            String finalMessage = errorMessage.startsWith("Error:") ? errorMessage : "Error: " + errorMessage;
            outputStream.write(finalMessage.getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
        } catch (IOException ex) {
            // Log this error, as we can't propagate it further to the client at this point if this write fails.
            log.error("Critical error: Failed to write error message to output stream", ex);
        }
    }
}