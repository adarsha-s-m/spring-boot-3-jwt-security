package com.alibou.security.gemini;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/v1/gemini")
@RequiredArgsConstructor
public class GeminiController {

    private final GeminiService geminiService;

    @PostMapping("/query")
    public ResponseEntity<StreamingResponseBody> queryGemini(
            @RequestBody GeminiRequest request
    ) {
        StreamingResponseBody originalStream = geminiService.streamQuery(request.getQuery());
        // Get current SecurityContext from the request thread
        SecurityContext securityContext = SecurityContextHolder.getContext();
        // Wrap the original StreamingResponseBody to propagate the context
        StreamingResponseBody wrappedStream = outputStream -> {
            // Set the SecurityContext for the current thread
            SecurityContextHolder.setContext(securityContext);
            try {
                originalStream.writeTo(outputStream);
            } finally {
                // Clear the SecurityContext after writing
                SecurityContextHolder.clearContext();
            }
        };
        return ResponseEntity.ok(wrappedStream);

    }
}
