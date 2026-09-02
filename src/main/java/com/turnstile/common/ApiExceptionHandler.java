package com.turnstile.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(EventNotFoundException.class)
    public ProblemDetail onEventNotFound(EventNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(HoldNotFoundException.class)
    public ProblemDetail onHoldNotFound(HoldNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    /**
     * Losing the race against the expiry sweeper is a 409, not a 500. The caller
     * did nothing wrong - their hold simply died before their payment landed.
     */
    @ExceptionHandler(HoldNoLongerActiveException.class)
    public ProblemDetail onHoldNoLongerActive(HoldNoLongerActiveException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Hold no longer active");
        problem.setProperty("holdId", ex.holdId().toString());
        return problem;
    }

    @ExceptionHandler(SeatUnavailableException.class)
    public ProblemDetail onSeatUnavailable(SeatUnavailableException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Seat unavailable");
        problem.setProperty("seatId", ex.seatId().toString());
        return problem;
    }
}
