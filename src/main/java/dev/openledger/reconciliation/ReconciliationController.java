package dev.openledger.reconciliation;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/reconciliation")
public class ReconciliationController {

    private final ReconciliationService service;

    public ReconciliationController(ReconciliationService service) {
        this.service = service;
    }

    /** Run reconciliation now. Returns 200 if clean, 409 if any drift was found. */
    @PostMapping("/run")
    public ResponseEntity<ReconciliationReport> run() {
        ReconciliationReport report = service.run();
        return report.clean() ? ResponseEntity.ok(report) : ResponseEntity.status(409).body(report);
    }

    @GetMapping("/reports/latest")
    public ResponseEntity<ReconciliationReport> latest() {
        ReconciliationReport report = service.lastReport();
        return report == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(report);
    }
}
