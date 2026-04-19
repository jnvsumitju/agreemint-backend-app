package com.agreemint.admin;

import com.agreemint.domain.User;
import com.agreemint.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Boot-time idempotent promoter for the admin portal. Reads a comma-
 * separated list of emails from {@code agreemint.staff-emails}
 * (or the {@code AGREEMINT_STAFF_EMAILS} env var) and sets
 * {@code is_staff = true} on any user whose email matches. Emails with
 * no matching user are logged and ignored — the operator can register
 * the account first and restart.
 *
 * <p>This is the happy-path way to bootstrap the first staff member
 * without shelling into the DB. Safe to leave configured in prod; the
 * runner is idempotent and only flips the flag if it's currently false.
 */
@Component
@Order(0)
public class StaffBootstrapRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(StaffBootstrapRunner.class);

    private final UserRepository userRepo;
    private final String staffEmailsCsv;

    public StaffBootstrapRunner(
            UserRepository userRepo,
            @Value("${agreemint.staff-emails:}") String staffEmailsCsv) {
        this.userRepo = userRepo;
        this.staffEmailsCsv = staffEmailsCsv;
    }

    @Override
    public void run(String... args) {
        if (staffEmailsCsv == null || staffEmailsCsv.isBlank()) return;
        List<String> emails = Arrays.stream(staffEmailsCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toLowerCase)
                .toList();
        int promoted = 0;
        int alreadyStaff = 0;
        int missing = 0;
        for (String email : emails) {
            var maybe = userRepo.findByEmail(email);
            if (maybe.isEmpty()) {
                log.warn("[staff-bootstrap] No user found for '{}' — register that account first, then restart.", email);
                missing++;
                continue;
            }
            User u = maybe.get();
            if (u.isStaff()) {
                alreadyStaff++;
                continue;
            }
            u.setStaff(true);
            userRepo.save(u);
            log.info("[staff-bootstrap] Promoted '{}' to staff.", email);
            promoted++;
        }
        log.info("[staff-bootstrap] done — promoted={}, already_staff={}, missing={}",
                promoted, alreadyStaff, missing);
    }
}
