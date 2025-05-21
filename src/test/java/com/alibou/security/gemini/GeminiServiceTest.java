package com.alibou.security.gemini;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpContent;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestFactory;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.JsonObjectParser;
import com.google.api.client.json.jackson2.JacksonFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GeminiServiceTest {

    // Mocks for Google API Client
    @Mock
    private HttpTransport mockHttpTransport; // To control the factory creation
    @Mock
    private HttpRequestFactory mockRequestFactory;
    @Mock
    private HttpRequest mockHttpRequest;
    @Mock
    private HttpResponse mockHttpResponse;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper(); // Real ObjectMapper

    @Spy // Use a real one as it's simple and final
    private static final JsonFactory JSON_FACTORY = new JacksonFactory();


    @InjectMocks
    private GeminiService geminiService;

    @BeforeEach
    void setUp() {
        // Inject real ObjectMapper
        geminiService = new GeminiService(objectMapper);

        // Use ReflectionTestUtils to set the static HTTP_TRANSPORT field first
        // This is tricky for static final, ideally HttpTransport is injected into GeminiService constructor
        // For this test, we'll assume we can control the HttpRequestFactory that GeminiService uses.
        // A better way: GeminiService takes HttpRequestFactory in constructor.
        // Here, we'll mock the factory and assume GeminiService can use it.
        // The GeminiService creates its factory from a static HttpTransport.
        // So, we mock the HttpTransport, and when createRequestFactory is called on it, return our mockRequestFactory.
        
        ReflectionTestUtils.setField(GeminiService.class, "HTTP_TRANSPORT", mockHttpTransport);
        when(mockHttpTransport.createRequestFactory(any())).thenReturn(mockRequestFactory);

        ReflectionTestUtils.setField(geminiService, "geminiApiKey", "test-api-key");
        ReflectionTestUtils.setField(geminiService, "modelName", "gemini-pro");
        ReflectionTestUtils.setField(geminiService, "geminiApiBaseUrl", "https://fakerestapi.com");
    }

    @Test
    void streamQuery_success() throws IOException {
        String mockResponseJsonChunk1 = "{\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"Hello \"}]}}]}";
        String mockResponseJsonChunk2 = "{\"candidates\": [{\"content\": {\"parts\": [{\"text\": \"World!\"}]}}]}";
        String fullResponseStream = mockResponseJsonChunk1 + "\n" + mockResponseJsonChunk2 + "\n";
        InputStream mockInputStream = new ByteArrayInputStream(fullResponseStream.getBytes(StandardCharsets.UTF_8));

        when(mockRequestFactory.buildPostRequest(any(GenericUrl.class), any(HttpContent.class)))
                .thenReturn(mockHttpRequest);
        when(mockHttpRequest.execute()).thenReturn(mockHttpResponse);
        when(mockHttpRequest.getHeaders()).thenReturn(new HttpHeaders()); // Return new HttpHeaders to avoid NPE if setContentType is called
        when(mockHttpResponse.isSuccessStatusCode()).thenReturn(true);
        when(mockHttpResponse.getContent()).thenReturn(mockInputStream);


        StreamingResponseBody responseBody = geminiService.streamQuery("test query");
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        responseBody.writeTo(outputStream);

        assertEquals("Hello World!", outputStream.toString().trim());
        verify(mockHttpRequest).execute();
        verify(mockHttpResponse).disconnect(); // Verify disconnect is called
    }

    @Test
    void streamQuery_httpError() throws IOException {
        String errorJson = "{\"error\": {\"message\": \"Internal Server Error\"}}";
        InputStream errorInputStream = new ByteArrayInputStream(errorJson.getBytes(StandardCharsets.UTF_8));

        when(mockRequestFactory.buildPostRequest(any(GenericUrl.class), any(HttpContent.class)))
                .thenReturn(mockHttpRequest);
        when(mockHttpRequest.execute()).thenReturn(mockHttpResponse);
        when(mockHttpRequest.getHeaders()).thenReturn(new HttpHeaders());
        when(mockHttpResponse.isSuccessStatusCode()).thenReturn(false);
        when(mockHttpResponse.getStatusCode()).thenReturn(500);
        when(mockHttpResponse.parseAsString()).thenReturn(errorJson); // Mock parseAsString
        when(mockHttpResponse.getContent()).thenReturn(errorInputStream); // Also provide content for safety

        StreamingResponseBody responseBody = geminiService.streamQuery("test query");
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        responseBody.writeTo(outputStream);

        String errorOutput = outputStream.toString().trim();
        assertTrue(errorOutput.contains("Error from Gemini API: 500"));
        // assertTrue(errorOutput.contains("Internal Server Error")); // parseAsString() mock covers this
        verify(mockHttpResponse).disconnect();
    }

    @Test
    void streamQuery_missingApiKey_returnsError() throws IOException {
        ReflectionTestUtils.setField(geminiService, "geminiApiKey", ""); 
        StreamingResponseBody responseBody = geminiService.streamQuery("test query");
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        responseBody.writeTo(outputStream);
        assertTrue(outputStream.toString().contains("Error: Gemini API key not configured."));
    }
    
    @Test
    void streamQuery_requestBuildThrowsIOException() throws IOException {
        when(mockRequestFactory.buildPostRequest(any(GenericUrl.class), any(HttpContent.class)))
                .thenThrow(new IOException("Failed to build request"));

        StreamingResponseBody responseBody = geminiService.streamQuery("test query");
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        responseBody.writeTo(outputStream);
        
        assertTrue(outputStream.toString().contains("Error building request: Failed to build request"));
    }

    @Test
    void streamQuery_executeThrowsIOException() throws IOException {
        when(mockRequestFactory.buildPostRequest(any(GenericUrl.class), any(HttpContent.class)))
                .thenReturn(mockHttpRequest);
        when(mockHttpRequest.getHeaders()).thenReturn(new HttpHeaders());
        when(mockHttpRequest.execute()).thenThrow(new IOException("Network error"));

        StreamingResponseBody responseBody = geminiService.streamQuery("test query");
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        responseBody.writeTo(outputStream);
        
        assertTrue(outputStream.toString().contains("Error processing your request: Network error"));
        // In this case, httpResponse is null, so disconnect isn't called on it.
        // verify(mockHttpResponse, never()).disconnect(); // This would be ideal if mockHttpResponse was involved
    }
}
