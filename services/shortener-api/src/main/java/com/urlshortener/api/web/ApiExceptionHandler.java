package com.urlshortener.api.web;

import com.urlshortener.api.service.UrlShortenerService.AliasTakenException;
import com.urlshortener.api.service.UrlShortenerService.NotFoundException;
import com.urlshortener.api.validation.UrlValidator.InvalidUrlException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import jakarta.validation.ConstraintViolationException;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import software.amazon.awssdk.core.exception.SdkException;

/** Maps domain and infrastructure failures to RFC 9457 problem details. */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
  private static final URI TYPE_BASE = URI.create("https://urlshortener.dev/problems/");

  @ExceptionHandler(NotFoundException.class)
  ProblemDetail notFound(NotFoundException e) {
    return problem(HttpStatus.NOT_FOUND, "not-found", e.getMessage());
  }

  @ExceptionHandler(AliasTakenException.class)
  ProblemDetail aliasTaken(AliasTakenException e) {
    return problem(HttpStatus.CONFLICT, "alias-taken", e.getMessage());
  }

  @ExceptionHandler({InvalidUrlException.class, IllegalArgumentException.class})
  ProblemDetail badRequest(RuntimeException e) {
    return problem(HttpStatus.BAD_REQUEST, "invalid-request", e.getMessage());
  }

  @ExceptionHandler(ConstraintViolationException.class)
  ProblemDetail constraintViolation(ConstraintViolationException e) {
    return problem(HttpStatus.BAD_REQUEST, "invalid-request", e.getMessage());
  }

  @ExceptionHandler(CallNotPermittedException.class)
  ResponseEntity<ProblemDetail> circuitOpen(CallNotPermittedException e) {
    log.warn("circuit breaker open: {}", e.getMessage());
    ProblemDetail pd =
        problem(
            HttpStatus.SERVICE_UNAVAILABLE,
            "dependency-unavailable",
            "the data store is temporarily unavailable; retry shortly");
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .header(HttpHeaders.RETRY_AFTER, "5")
        .body(pd);
  }

  @ExceptionHandler(SdkException.class)
  ResponseEntity<ProblemDetail> awsFailure(SdkException e) {
    log.error("AWS SDK failure", e);
    ProblemDetail pd =
        problem(
            HttpStatus.SERVICE_UNAVAILABLE,
            "dependency-unavailable",
            "the data store request failed; retry shortly");
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .header(HttpHeaders.RETRY_AFTER, "2")
        .body(pd);
  }

  @ExceptionHandler(Exception.class)
  ProblemDetail unexpected(Exception e) {
    log.error("unhandled exception", e);
    return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "unexpected server error");
  }

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    StringBuilder detail = new StringBuilder();
    ex.getBindingResult()
        .getFieldErrors()
        .forEach(
            fe ->
                detail
                    .append(detail.isEmpty() ? "" : "; ")
                    .append(fe.getField())
                    .append(' ')
                    .append(fe.getDefaultMessage()));
    ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "invalid-request", detail.toString());
    return ResponseEntity.badRequest().headers(headers).body(pd);
  }

  private static ProblemDetail problem(HttpStatus status, String type, String detail) {
    ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
    pd.setTitle(status.getReasonPhrase());
    pd.setType(TYPE_BASE.resolve(type));
    return pd;
  }
}
