package ai.gitoracle.orchestrator.controller;

import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Turns validation and binding failures into RFC 7807 problem details, and
 * everything unexpected into an opaque 500 carrying a correlation id.
 *
 * <h2>Why this extends ResponseEntityExceptionHandler</h2>
 * It must. A bare {@code @RestControllerAdvice} with an
 * {@code @ExceptionHandler(Exception.class)} catch-all also swallows Spring's
 * own control-flow exceptions — {@code NoResourceFoundException} for an unknown
 * path and {@code HttpRequestMethodNotSupportedException} for a wrong verb —
 * and answers both with 500. Confirmed live during development: {@code GET
 * /api/v1/no-such-endpoint} returned 500 instead of 404, and {@code DELETE
 * /api/v1/jobs} returned 500 instead of 405. That is worse than the problem
 * this class was added to solve: every 404 becomes a logged stack trace with a
 * correlation id, and monitoring cannot distinguish a client hitting a typo
 * from the server actually breaking.
 *
 * <p>Extending {@link ResponseEntityExceptionHandler} keeps Spring's handling
 * of those standard exceptions (correct status, no stack trace) and leaves the
 * catch-all below to fire only for genuinely unexpected failures.
 *
 * <h2>What the client is told</h2>
 * The rule is: <b>tell the client what it did wrong, never what the server is
 * made of.</b>
 *
 * <p>Validation failures are the client's own input reflected back — field
 * names and the constraint that failed. That is safe and actionable.
 *
 * <p>Everything else gets a generic message plus a {@code correlationId} that is
 * logged server-side with the real stack trace. Controllers in this module
 * previously did the opposite: {@code ResponseEntity.internalServerError().body(
 * Map.of("error", e.getMessage()))} appears throughout CommitController and
 * DashboardController, handing the caller raw exception text — JDBC URLs and SQL
 * fragments from a DataAccessException, internal hostnames and ports from a
 * ResourceAccessException, class names and paths from anything else. That is the
 * M6 finding, closed here for any exception that reaches this handler.
 *
 * <p>It does not retroactively fix the local catch blocks that build their own
 * {@code Map.of("error", e.getMessage())} response — those never reach a
 * handler. They are called out in the H6/H7 commit as remaining work rather than
 * rewritten wholesale here, since each needs its own judgement about what the
 * caller legitimately needs to see (test-runner's test output, for one, is the
 * product and must keep flowing).
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** A request body that failed {@code @Valid}. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        // TreeMap so field order is stable across requests — an unstable error
        // body makes client-side tests flaky for no reason.
        Map<String, String> fieldErrors = new TreeMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
            fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        ex.getBindingResult().getGlobalErrors().forEach(error ->
            fieldErrors.putIfAbsent(error.getObjectName(), error.getDefaultMessage()));

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Validation failed");
        problem.setDetail("One or more fields are invalid.");
        problem.setProperty("errors", fieldErrors);
        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * Malformed JSON, or a value of the wrong type for its field.
     *
     * <p>The exception message is deliberately not echoed: Jackson's text
     * includes the target Java class and the reference chain
     * ("ai.gitoracle.orchestrator.dto.Requests$TriggerFix[\"repoUrl\"]"), which
     * describes the server's internals rather than the client's mistake.
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        log.warn("Rejected unreadable request body: {}", ex.getMessage());

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Malformed request body");
        problem.setDetail("The request body could not be parsed as valid JSON matching this endpoint's schema.");
        return ResponseEntity.badRequest().body(problem);
    }

    /**
     * A constraint on a path variable or request parameter. Not covered by the
     * superclass, so it stays an explicit handler.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail onConstraintViolation(ConstraintViolationException ex) {
        Map<String, String> violations = new TreeMap<>();
        ex.getConstraintViolations().forEach(violation ->
            violations.putIfAbsent(violation.getPropertyPath().toString(), violation.getMessage()));

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Validation failed");
        problem.setDetail("One or more parameters are invalid.");
        problem.setProperty("errors", violations);
        return problem;
    }

    /**
     * e.g. a path variable declared as UUID that isn't one. More specific than
     * the superclass's TypeMismatchException handling, so this wins — the point
     * is to name the offending parameter.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail onTypeMismatch(MethodArgumentTypeMismatchException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Invalid parameter");
        problem.setDetail("A parameter has the wrong format.");
        Map<String, String> errors = new LinkedHashMap<>();
        errors.put(ex.getName(), "is not a valid value for this parameter");
        problem.setProperty("errors", errors);
        return problem;
    }

    /**
     * Genuinely unexpected failures only. Spring's own routing and negotiation
     * exceptions are handled by the superclass and never reach here — see the
     * class javadoc for why that matters.
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail onUnexpected(Exception ex) {
        String correlationId = UUID.randomUUID().toString();
        log.error("Unhandled exception [correlationId={}]", correlationId, ex);

        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setTitle("Internal server error");
        problem.setDetail("The request could not be completed. Quote the correlationId when reporting this.");
        problem.setProperty("correlationId", correlationId);
        return problem;
    }
}
