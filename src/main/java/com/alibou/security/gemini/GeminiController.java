package com.alibou.security.gemini;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
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
        StreamingResponseBody stream = geminiService.streamQuery(request.getQuery());
        return ResponseEntity.ok(stream);
    }
}
