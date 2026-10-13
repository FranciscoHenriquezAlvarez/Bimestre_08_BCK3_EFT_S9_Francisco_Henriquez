package cl.duoc.bancoxyz.core;

import cl.duoc.bancoxyz.core.AccountManagementModels.*;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/core/accounts")
public class AccountManagementController {
    private final AccountManagementService service;

    public AccountManagementController(AccountManagementService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ManagedAccount> open(@RequestBody OpenAccountRequest request, @AuthenticationPrincipal Jwt token) {
        ManagedAccount created = service.open(request, token.getTokenValue());
        return ResponseEntity.created(URI.create("/api/core/accounts/" + created.accountId())).body(created);
    }

    @PutMapping("/{id}")
    public ManagedAccount update(@PathVariable Integer id, @RequestBody UpdateAccountRequest request) {
        return service.update(id, request);
    }

    @PostMapping("/{id}/close")
    public ManagedAccount close(@PathVariable Integer id) {
        return service.close(id);
    }
}
