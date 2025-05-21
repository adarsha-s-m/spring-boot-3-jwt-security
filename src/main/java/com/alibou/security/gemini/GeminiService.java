package com.alibou.security.gemini;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper; // Standard Jackson ObjectMapper
import com.google.api.client.http.ByteArrayContent;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport; // Using standard Java HTTP transport
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.JsonObjectParser;
import com.google.api.client.json.jackson2.JacksonFactory; // For google-http-client-jackson2

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

@Service
@Slf4j
public class GeminiService {

    @Value("${GEMINI_API_KEY}")
    private String geminiApiKey;

    @Value("${GEMINI_MODEL_NAME}") // e.g., "gemini-pro"
    private String modelName;

    @Value("${GEMINI_API_BASE_URL:https://generativelanguage.googleapis.com}")
    private String geminiApiBaseUrl;

    private static final HttpTransport HTTP_TRANSPORT = new NetHttpTransport();
    private static final JsonFactory JSON_FACTORY = new JacksonFactory();
    private final ObjectMapper jacksonObjectMapper; // For request body generation and detailed parsing

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

        // Endpoint for streaming
        GenericUrl genericUrl = new GenericUrl(String.format("%s/v1beta/models/%s:streamGenerateContent",
                geminiApiBaseUrl, modelName));
        genericUrl.put("key", geminiApiKey); // API key as a query parameter

        HttpRequestFactory requestFactory = HTTP_TRANSPORT.createRequestFactory(
                (HttpRequest request) -> {
                    request.setParser(new JsonObjectParser(JSON_FACTORY));
                });

        HttpRequest httpRequest;
        try {
            ByteArrayContent content = new ByteArrayContent("application/json", requestBodyJson.getBytes(StandardCharsets.UTF_8));
            httpRequest = requestFactory.buildPostRequest(genericUrl, content);
            httpRequest.getHeaders().setContentType("application/json");
            // Set timeouts if necessary, e.g. httpRequest.setConnectTimeout(...)
        } catch (IOException e) {
            log.error("Error building HTTP request: {}", e.getMessage(), e);
            return outputStream -> writeErrorToStream(outputStream, "Error building request: " + e.getMessage());
        }

        return outputStream -> {
            HttpResponse httpResponse = null;
            InputStream responseStream = null;
            try {
                httpResponse = httpRequest.execute();

                if (!httpResponse.isSuccessStatusCode()) {
                    String errorBody = httpResponse.parseAsString();
                    log.error("Gemini API error. Status: {}, Body: {}", httpResponse.getStatusCode(), errorBody);
                    writeErrorToStream(outputStream, "Error from Gemini API: " + httpResponse.getStatusCode() + " - " + errorBody);
                    return;
                }

                responseStream = httpResponse.getContent();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(responseStream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.trim().isEmpty() || line.trim().equals("[") || line.trim().equals("]")) {
                            continue;
                        }
                        String jsonChunk = line.trim();
                        if (jsonChunk.endsWith(",")) {
                            jsonChunk = jsonChunk.substring(0, jsonChunk.length() - 1);
                        }
                        
                        try {
                            JsonNode rootNode = jacksonObjectMapper.readTree(jsonChunk);
                            if (rootNode.has("candidates")) {
                                JsonNode candidates = rootNode.get("candidates");
                                for (JsonNode candidate : candidates) {
                                    if (candidate.has("content") && candidate.get("content").has("parts")) {
                                        JsonNode parts = candidate.get("content").get("parts");
                                        for (JsonNode part : parts) {
                                            if (part.has("text")) {
                                                String text = part.get("text").asText();
                                                outputStream.write(text.getBytes(StandardCharsets.UTF_8));
                                                outputStream.flush();
                                            }
                                        }
                                    }
                                }
                            } else if (rootNode.has("error")) {
                                String errorMessage = rootNode.get("error").get("message").asText("Unknown error in stream");
                                log.error("Error in Gemini stream: {}", errorMessage);
                            }
                        } catch (JsonProcessingException e) {
                            log.warn("Could not parse JSON chunk from stream: '{}'. Error: {}", line, e.getMessage());
                        }
                    }
                }
            } catch (IOException e) {
                log.error("Error during Gemini API HTTP call or streaming: {}", e.getMessage(), e);
                writeErrorToStream(outputStream, "Error processing your request: " + e.getMessage());
            } finally {
                if (responseStream != null) {
                    try {
                        responseStream.close();
                    } catch (IOException e) {
                        log.error("Error closing response stream", e);
                    }
                }
                if (httpResponse != null) {
                    try {
                        httpResponse.disconnect(); // Important to release resources
                    } catch (IOException e) {
                        log.error("Error disconnecting HTTP response", e);
                    }
                }
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
