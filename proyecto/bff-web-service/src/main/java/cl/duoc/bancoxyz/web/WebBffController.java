package cl.duoc.bancoxyz.web;

import java.math.*;
import org.springframework.web.bind.annotation.*;

// Expone la informacion de cuentas adaptada para el canal web.
@RestController
@RequestMapping("/api/bff/web/accounts")
public class WebBffController {
    private final WebBankingClient core;

    public WebBffController(WebBankingClient core) {
        this.core = core;
    }

    @GetMapping("/{id}")
    public Detail account(@PathVariable Integer id) {
        var a = core.account(id);
        return new Detail(a.accountId(), a.accountName(), a.accountType(), a.initialBalance(), a.interestRate(),
                a.initialBalance().multiply(a.interestRate()).setScale(2, RoundingMode.HALF_UP), a.finalBalance());
    }

    @GetMapping("/{id}/annual-statement")
    public WebBankingClient.AnnualStatement statement(@PathVariable Integer id) {
        return core.statement(id);
    }

    public record Detail(Integer accountId, String accountName, String accountType, BigDecimal initialBalance,
            BigDecimal interestRate, BigDecimal calculatedInterest, BigDecimal finalBalance) {
    }
}
