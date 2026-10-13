package cl.duoc.bancoxyz.payment;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import cl.duoc.bancoxyz.payment.CoreOperationClient.CoreRequestException;
import cl.duoc.bancoxyz.payment.PaymentModels.AccountCommand;
import cl.duoc.bancoxyz.payment.PaymentModels.TransferCommand;
import cl.duoc.bancoxyz.payment.PaymentModels.OperationResult;
import cl.duoc.bancoxyz.payment.PaymentModels.OperationError;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {
    private final PaymentService payments;

    public PaymentController(PaymentService payments) {
        this.payments = payments;
    }

    @PostMapping
    public OperationResult payment(@RequestBody AccountCommand command) {
        return payments.payment(command);
    }

    @PostMapping("/deposits")
    public OperationResult deposit(@RequestBody AccountCommand command) {
        return payments.deposit(command);
    }

    @PostMapping("/transfers")
    public OperationResult transfer(@RequestBody TransferCommand command) {
        return payments.transfer(command);
    }

    @GetMapping("/operations/{operationId}")
    public OperationResult operation(@PathVariable UUID operationId) {
        return payments.operation(operationId);
    }

    @ExceptionHandler(CoreRequestException.class)
    public ResponseEntity<OperationError> coreError(CoreRequestException error) {
        return ResponseEntity.status(error.status()).body(error.result());
    }
}
