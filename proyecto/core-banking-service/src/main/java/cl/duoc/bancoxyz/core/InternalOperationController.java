package cl.duoc.bancoxyz.core;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import cl.duoc.bancoxyz.core.AccountOperationService.OperationRequest;
import cl.duoc.bancoxyz.core.AccountOperationService.OperationResult;
import cl.duoc.bancoxyz.core.AccountOperationService.OperationType;

@RestController
@RequestMapping("/api/core/internal/operations")
public class InternalOperationController {
    private final AccountOperationService operations;

    public InternalOperationController(AccountOperationService operations) {
        this.operations = operations;
    }

    @PostMapping("/debit")
    public OperationResult debit(@RequestBody AccountCommand command) {
        return operations.execute(new OperationRequest(command.operationId(), OperationType.DEBIT,
                command.accountId(), null, command.amount()));
    }

    @PostMapping("/credit")
    public OperationResult credit(@RequestBody AccountCommand command) {
        return operations.execute(new OperationRequest(command.operationId(), OperationType.CREDIT,
                null, command.accountId(), command.amount()));
    }

    @PostMapping("/transfer")
    public OperationResult transfer(@RequestBody TransferCommand command) {
        return operations.execute(new OperationRequest(command.operationId(), OperationType.TRANSFER,
                command.sourceAccountId(), command.targetAccountId(), command.amount()));
    }

    @GetMapping("/{operationId}")
    public OperationResult operation(@PathVariable UUID operationId) {
        return operations.operation(operationId);
    }

    public record AccountCommand(UUID operationId, Integer accountId, BigDecimal amount) {
    }

    public record TransferCommand(UUID operationId, Integer sourceAccountId, Integer targetAccountId,
            BigDecimal amount) {
    }
}
