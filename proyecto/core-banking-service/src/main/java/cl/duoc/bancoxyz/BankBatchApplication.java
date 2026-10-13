package cl.duoc.bancoxyz;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// Inicia el servicio que ejecuta los procesos batch bancarios.
@SpringBootApplication
public class BankBatchApplication {
    public static void main(String[] args) {
        SpringApplication.run(BankBatchApplication.class, args);
    }
}
