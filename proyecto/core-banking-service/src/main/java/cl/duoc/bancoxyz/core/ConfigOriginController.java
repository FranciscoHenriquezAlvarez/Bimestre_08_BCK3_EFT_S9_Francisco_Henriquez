package cl.duoc.bancoxyz.core;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Permite verificar que la configuracion se obtiene desde config-repo.
@RestController
@RequestMapping("/api/config")
public class ConfigOriginController {
    private final String configOrigin;

    public ConfigOriginController(@Value("${bank.config-origin}") String configOrigin) {
        this.configOrigin = configOrigin;
    }

    @GetMapping("/origin")
    public OriginResponse origin() {
        return new OriginResponse(configOrigin);
    }

    public record OriginResponse(String configOrigin) {
    }
}
