package cl.duoc.bancoxyz.customer;

import cl.duoc.bancoxyz.customer.CustomerModels.CustomerError;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class CustomerExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<CustomerError> requestError(ResponseStatusException error) {
        return ResponseEntity.status(error.getStatusCode())
                .body(new CustomerError(error.getStatusCode().value(), error.getReason()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<CustomerError> invalidFormat(Exception error) {
        return ResponseEntity.badRequest().body(new CustomerError(400, "Los datos o el identificador no tienen un formato valido"));
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<CustomerError> persistenceError(DataAccessException error) {
        // La respuesta publica no incluye SQL, datos personales ni detalles de la conexion.
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new CustomerError(500, "No fue posible acceder a los datos del cliente"));
    }
}
