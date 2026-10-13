package cl.duoc.bancoxyz.core;

import cl.duoc.bancoxyz.core.kafka.AsyncWithdrawalService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

// Expone consultas bancarias y el estado de retiros asincronos.
@RestController
@RequestMapping("/api/core/accounts")
public class CoreBankingController {
  private final BankingService service;
  private final AsyncWithdrawalService asyncWithdrawals;

  public CoreBankingController(BankingService service, AsyncWithdrawalService asyncWithdrawals) {
    this.service = service;
    this.asyncWithdrawals = asyncWithdrawals;
  }

  @GetMapping("/{id}")
  public BankingService.Account account(@PathVariable Integer id) {
    return service.account(id);
  }

  @GetMapping("/{id}/annual-statement")
  public BankingService.WebStatement statement(@PathVariable Integer id) {
    return service.annualStatement(id);
  }

  @GetMapping("/{id}/movements/latest")
  public List<BankingService.Movement> latest(@PathVariable Integer id) {
    return service.latestMovements(id);
  }

  @GetMapping("/{id}/balance")
  public Balance balance(@PathVariable Integer id) {
    return new Balance(id, service.account(id).finalBalance());
  }

  @GetMapping("/async-withdrawals/{transactionId}")
  public AsyncWithdrawalService.TransactionStatus asyncWithdrawalStatus(@PathVariable UUID transactionId) {
    AsyncWithdrawalService.TransactionStatus status = asyncWithdrawals.asyncWithdrawalStatus(transactionId);
    if (status == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Transaccion no encontrada");
    }
    return status;
  }

  public record Balance(Integer accountId, BigDecimal balance) {
  }
}
