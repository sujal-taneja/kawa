package dev.kawa.job;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class JobExceptionHandler {

    @ExceptionHandler(JobNotFoundException.class)
    ProblemDetail jobNotFound(JobNotFoundException ex) {
        ProblemDetail problem = problem(HttpStatus.NOT_FOUND, "Job not found", ex);
        problem.setProperty("jobId", ex.jobId());
        return problem;
    }

    @ExceptionHandler(LeaseNotHeldException.class)
    ProblemDetail leaseNotHeld(LeaseNotHeldException ex) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "Lease not held", ex);
        problem.setProperty("jobId", ex.jobId());
        return problem;
    }

    @ExceptionHandler(InvalidJobRequestException.class)
    ProblemDetail invalidRequest(InvalidJobRequestException ex) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", ex);
    }

    private static ProblemDetail problem(HttpStatus status, String title, RuntimeException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        problem.setTitle(title);
        return problem;
    }
}
