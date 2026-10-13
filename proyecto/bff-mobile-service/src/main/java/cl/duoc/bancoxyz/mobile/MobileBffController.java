package cl.duoc.bancoxyz.mobile;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.web.bind.annotation.*;

// Expone la informacion de cuentas adaptada para el canal mobile.
@RestController
@RequestMapping("/api/bff/mobile/accounts")
public class MobileBffController {
    private final MobileBankingClient core;

    public MobileBffController(MobileBankingClient core) {
        this.core = core;
    }

    @GetMapping("/{id}")
    public Account account(@PathVariable Integer id) {
        var a = core.account(id);
        return new Account(a.accountId(), a.accountName(), a.finalBalance(), a.accountType());
    }

    @GetMapping("/{id}/transactions")
    public List<MobileBankingClient.Movement> movements(@PathVariable Integer id) {
        return core.movements(id);
    }

    public record Account(Integer accountId, String accountName, BigDecimal finalBalance, String accountType) {
    }
}
