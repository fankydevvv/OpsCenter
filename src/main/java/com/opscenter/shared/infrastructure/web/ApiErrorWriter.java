package com.opscenter.shared.infrastructure.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletResponse;

import com.opscenter.shared.api.ApiError;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * Serialises an {@link ApiError} straight onto a servlet response.
 * <p>
 * Needed by the security handlers: a 401/403 raised inside the filter chain never reaches a
 * controller or {@code @RestControllerAdvice}, so the JSON has to be written by hand - with the
 * same Boot-configured {@link JsonMapper} the controllers use, to guarantee an identical shape.
 */
@Component
public class ApiErrorWriter {

    private final JsonMapper jsonMapper;

    public ApiErrorWriter(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public void write(HttpServletResponse response, ApiError error) throws IOException {
        response.setStatus(error.status());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(jsonMapper.writeValueAsString(error));
        response.getWriter().flush();
    }
}
