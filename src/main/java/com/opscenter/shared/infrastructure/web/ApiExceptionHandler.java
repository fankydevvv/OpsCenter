package com.opscenter.shared.infrastructure.web;

import java.util.List;

import jakarta.validation.ConstraintViolationException;

import com.opscenter.shared.api.ApiError;
import com.opscenter.shared.api.FieldError;
import com.opscenter.shared.domain.DomainException;
import com.opscenter.shared.domain.ErrorCodes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.BindException;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Translates every exception escaping a controller into the contract body (04-API §2.3, §17; D-18).
 * <p>
 * Mapping (07-TC §22 requires each to carry {@code requestId} and a stable {@code code}):
 * <ul>
 *   <li>{@link DomainException} -> its own status/code (business modules decide)</li>
 *   <li>bean validation / unreadable body / bad parameter -> 400 {@code VALIDATION_FAILED} etc.</li>
 *   <li>{@link AuthenticationException} -> 401 {@code AUTH_UNAUTHENTICATED}</li>
 *   <li>{@link AccessDeniedException} (also thrown by {@code @PreAuthorize}) -> 403 {@code RBAC_PERMISSION_DENIED}</li>
 *   <li>unknown path -> 404, optimistic lock -> 409 {@code CONCURRENCY_VERSION_CONFLICT}</li>
 *   <li>database / broker unreachable -> 503 {@code DEPENDENCY_UNAVAILABLE}</li>
 *   <li>anything else -> 500 {@code INTERNAL_ERROR} with a generic message, full stack trace in the log only</li>
 * </ul>
 * <b>Logging rule (04-API §18, 05-DEPLOY §16 "never log a password or token"):</b> for a 4xx the
 * handler logs a summary it built itself - never {@code ex.getMessage()}. Spring's validation
 * exceptions embed the rejected value of every failing field in their message ("rejected value
 * [...]"), so logging that message for an invalid {@code POST /auth/login} would write the
 * submitted password into the application log. Only {@link DomainException} messages are logged
 * verbatim: the application composes them itself. 5xx are logged at ERROR with the stack trace.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ApiError> handleDomain(DomainException ex) {
        return respond(ex.status(), ex.code(), ex.getMessage(), List.of(), ex.getMessage(), ex);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        List<FieldError> fields = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> new FieldError(f.getField(), f.getDefaultMessage()))
                .toList();
        return validationFailed(fields, ex);
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiError> handleBind(BindException ex) {
        List<FieldError> fields = ex.getBindingResult().getFieldErrors().stream()
                .map(f -> new FieldError(f.getField(), f.getDefaultMessage()))
                .toList();
        return validationFailed(fields, ex);
    }

    /**
     * Method validation (Spring 6.1+): raised instead of {@link MethodArgumentNotValidException}
     * as soon as any parameter of the handler carries a constraint (for example a {@code @Size}
     * on a header). A {@code @Valid} body then arrives as {@link ParameterErrors}, whose field
     * errors name the JSON fields; plain parameters are reported under their parameter name.
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiError> handleHandlerMethodValidation(HandlerMethodValidationException ex) {
        List<FieldError> fields = ex.getParameterValidationResults().stream()
                .flatMap(r -> r instanceof ParameterErrors errors
                        ? errors.getFieldErrors().stream()
                                .map(f -> new FieldError(f.getField(), f.getDefaultMessage()))
                        : r.getResolvableErrors().stream()
                                .map(e -> new FieldError(r.getMethodParameter().getParameterName(), e.getDefaultMessage())))
                .toList();
        return validationFailed(fields, ex);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException ex) {
        List<FieldError> fields = ex.getConstraintViolations().stream()
                .map(v -> new FieldError(String.valueOf(v.getPropertyPath()), v.getMessage()))
                .toList();
        return validationFailed(fields, ex);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> handleMissingParameter(MissingServletRequestParameterException ex) {
        return validationFailed(List.of(new FieldError(ex.getParameterName(), "must be present")), ex);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiError> handleMissingHeader(MissingRequestHeaderException ex) {
        return validationFailed(List.of(new FieldError(ex.getHeaderName(), "header must be present")), ex);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String field = ex.getName();
        String expected = ex.getRequiredType() == null ? "valid value" : ex.getRequiredType().getSimpleName();
        return validationFailed(List.of(new FieldError(field, "must be a " + expected)), ex);
    }

    /** {@code sort=noSuchProperty} on a paginated endpoint is a client error, not a crash. */
    @ExceptionHandler(PropertyReferenceException.class)
    public ResponseEntity<ApiError> handlePropertyReference(PropertyReferenceException ex) {
        return validationFailed(List.of(new FieldError("sort", "unknown property '" + ex.getPropertyName() + "'")), ex);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex) {
        return respond(HttpStatus.BAD_REQUEST, ErrorCodes.REQUEST_MALFORMED, "Request body is missing or malformed",
                List.of(), "request body unreadable", ex);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthentication(AuthenticationException ex) {
        return respond(HttpStatus.UNAUTHORIZED, ErrorCodes.AUTH_UNAUTHENTICATED, "Authentication required",
                List.of(), ex.getClass().getSimpleName(), ex);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex) {
        return respond(HttpStatus.FORBIDDEN, ErrorCodes.RBAC_PERMISSION_DENIED,
                "You do not have permission to perform this action", List.of(), "access denied", ex);
    }

    @ExceptionHandler({OptimisticLockingFailureException.class, jakarta.persistence.OptimisticLockException.class})
    public ResponseEntity<ApiError> handleOptimisticLock(Exception ex) {
        return respond(HttpStatus.CONFLICT, ErrorCodes.CONCURRENCY_VERSION_CONFLICT,
                "Resource was modified by someone else. Reload and retry.", List.of(), "optimistic lock failed", ex);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException ex) {
        return respond(HttpStatus.CONFLICT, ErrorCodes.DATA_INTEGRITY_VIOLATION,
                "Request conflicts with existing data", List.of(), "data integrity violation", ex);
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiError> handleNoResource(Exception ex) {
        return respond(HttpStatus.NOT_FOUND, ErrorCodes.RESOURCE_NOT_FOUND, "No resource at this path", List.of(),
                "no handler", ex);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex) {
        return respond(HttpStatus.METHOD_NOT_ALLOWED, ErrorCodes.METHOD_NOT_ALLOWED,
                "HTTP method not supported for this path", List.of(), "method " + ex.getMethod(), ex);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> handleMediaType(HttpMediaTypeNotSupportedException ex) {
        return respond(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorCodes.UNSUPPORTED_MEDIA_TYPE,
                "Content type not supported; use application/json", List.of(),
                "content type " + ex.getContentType(), ex);
    }

    /**
     * Framework-raised HTTP errors ({@code ResponseStatusException} and friends). The code stays in
     * the {@code DOMAIN_REASON} shape of 04-API §17 instead of leaking a numeric {@code HTTP_xxx}.
     */
    @ExceptionHandler(ErrorResponseException.class)
    public ResponseEntity<ApiError> handleErrorResponse(ErrorResponseException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        String code;
        if (status == HttpStatus.NOT_FOUND) {
            code = ErrorCodes.RESOURCE_NOT_FOUND;
        }
        else if (status.is4xxClientError()) {
            code = ErrorCodes.REQUEST_INVALID;
        }
        else {
            code = ErrorCodes.INTERNAL_ERROR;
        }
        String message = ex.getBody().getDetail() == null ? status.getReasonPhrase() : ex.getBody().getDetail();
        return respond(status, code, message, List.of(), ex.getClass().getSimpleName(), ex);
    }

    /**
     * PostgreSQL or RabbitMQ cannot be reached: 503 tells the client (and a load balancer) that the
     * problem is transient and on our side, which a generic 500 would not (04-API §17, 07-TC §22).
     */
    @ExceptionHandler({CannotCreateTransactionException.class, DataAccessResourceFailureException.class,
            TransientDataAccessResourceException.class, AmqpConnectException.class})
    public ResponseEntity<ApiError> handleDependencyUnavailable(Exception ex) {
        return respond(HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.DEPENDENCY_UNAVAILABLE,
                "A backing service is temporarily unavailable. Please retry shortly.", List.of(),
                ex.getClass().getSimpleName(), ex);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCodes.INTERNAL_ERROR,
                "Unexpected error. Please retry or contact support with the request id.", List.of(),
                ex.getClass().getSimpleName(), ex);
    }

    private ResponseEntity<ApiError> validationFailed(List<FieldError> fields, Exception ex) {
        // Only field NAMES reach the log - the values may be a password (04-API §18).
        String summary = "validation failed on fields " + fields.stream().map(FieldError::field).toList();
        return respond(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_FAILED, "Request validation failed", fields,
                summary, ex);
    }

    /**
     * @param logDetail what to write to the log for this failure; deliberately separate from the
     *                  exception so no handler can log raw client input by accident
     */
    private ResponseEntity<ApiError> respond(HttpStatus status, String code, String message,
                                             List<FieldError> fieldErrors, String logDetail, Exception ex) {
        ApiError body = ApiErrors.of(status, code, message, fieldErrors);
        if (status.is5xxServerError()) {
            log.error("[{}] {} {}: {}", body.requestId(), status.value(), code, logDetail, ex);
        }
        else {
            log.warn("[{}] {} {}: {}", body.requestId(), status.value(), code, logDetail);
        }
        return ResponseEntity.status(status).body(body);
    }
}
