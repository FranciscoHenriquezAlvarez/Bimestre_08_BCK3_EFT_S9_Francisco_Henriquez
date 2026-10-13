package cl.duoc.bancoxyz.customer;

import cl.duoc.bancoxyz.customer.CustomerModels.*;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/customers")
public class CustomerController {
    private final CustomerService customers;

    public CustomerController(CustomerService customers) {
        this.customers = customers;
    }

    @PostMapping
    public ResponseEntity<Customer> create(@RequestBody CreateCustomerRequest requested) {
        Customer customer = customers.create(requested);
        return ResponseEntity.created(URI.create("/api/customers/" + customer.customerId())).body(customer);
    }

    @GetMapping("/{id}")
    public Customer find(@PathVariable Integer id) {
        return customers.find(id);
    }

    @PutMapping("/{id}")
    public Customer updateInformation(@PathVariable Integer id, @RequestBody PersonalInformation requested) {
        return customers.updateInformation(id, requested);
    }

    @GetMapping("/{id}/profile")
    public CustomerProfile profile(@PathVariable Integer id) {
        return customers.profile(id);
    }

    @PutMapping("/{id}/profile")
    public CustomerProfile updateProfile(@PathVariable Integer id, @RequestBody ProfileRequest requested) {
        return customers.updateProfile(id, requested);
    }
}
