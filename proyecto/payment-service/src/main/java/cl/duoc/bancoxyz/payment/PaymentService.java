package cl.duoc.bancoxyz.payment;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import cl.duoc.bancoxyz.payment.PaymentModels.AccountCommand;
import cl.duoc.bancoxyz.payment.PaymentModels.TransferCommand;
import cl.duoc.bancoxyz.payment.PaymentModels.OperationResult;

@Service
public class PaymentService {
    private final CoreOperationClient core;

    public PaymentService(CoreOperationClient core) {
        this.core = core;
    }

    public OperationResult payment(AccountCommand command) {
        validate(command.operationId(), command.amount(), command.accountId());
        return core.execute(command.operationId(), "debit", "DEBIT", command);
    }

    public OperationResult deposit(AccountCommand command) {
        validate(command.operationId(), command.amount(), command.accountId());
        return core.execute(command.operationId(), "credit", "CREDIT", command);
    }

    public OperationResult transfer(TransferCommand command) {
        validate(command.operationId(), command.amount(), command.sourceAccountId(), command.targetAccountId());
        return core.execute(command.operationId(), "transfer", "TRANSFER", command);
    }

    public OperationResult operation(UUID operationId) {
        return core.operation(operationId);
    }

    private void validate(UUID id, BigDecimal amount, Integer... accounts) {
        if (id == null || amount == null || Arrays.stream(accounts).anyMatch(Objects::isNull)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La solicitud esta incompleta");
        }
        // Core valida montos, estado de cuentas y saldos dentro de su transaccion local.
    }
}
