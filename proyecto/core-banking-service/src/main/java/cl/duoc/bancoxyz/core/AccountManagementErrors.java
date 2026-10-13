package cl.duoc.bancoxyz.core;

import cl.duoc.bancoxyz.core.AccountManagementModels.AccountError;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes = AccountManagementController.class)
public class AccountManagementErrors {
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<AccountError> status(ResponseStatusException error) {
        int status = error.getStatusCode().value();
        return ResponseEntity.status(status).body(new AccountError(status, error.getReason()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<AccountError> malformed(Exception error) {
        return ResponseEntity.badRequest().body(new AccountError(400, "Solicitud administrativa invalida"));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<AccountError> persistence(DataAccessException error) {
        return ResponseEntity.internalServerError().body(new AccountError(500, "No fue posible completar la operacion de cuenta"));
    }
}
