package cl.duoc.bancoxyz.core;

import cl.duoc.bancoxyz.core.OperationalWithdrawalService.WithdrawalResult;
import cl.duoc.bancoxyz.events.WithdrawalRequestedEvent;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/core/internal/withdrawals")
public class InternalWithdrawalController {
    private final OperationalWithdrawalService withdrawals;

    public InternalWithdrawalController(OperationalWithdrawalService withdrawals) {
        this.withdrawals = withdrawals;
    }

    @PostMapping
    public WithdrawalResult process(@RequestBody WithdrawalRequestedEvent requested) {
        return withdrawals.process(requested);
    }

    @GetMapping("/{transactionId}")
    public WithdrawalResult result(@PathVariable UUID transactionId) {
        return withdrawals.result(transactionId);
    }
}
