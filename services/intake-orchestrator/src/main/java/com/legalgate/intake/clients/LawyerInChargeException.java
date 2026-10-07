package com.legalgate.intake.clients;

import java.util.List;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** A lawyer cannot leave while still responsible for a Client or assigned open work; this says what to reassign. */
public class LawyerInChargeException extends ResponseStatusException {

    public record Blocker(String lawyerId, String lawyerName, List<String> clients, List<String> activities) {
    }

    private final List<Blocker> blockers;

    public LawyerInChargeException(List<Blocker> blockers) {
        super(HttpStatus.CONFLICT, "lawyer_still_in_charge");
        this.blockers = List.copyOf(blockers);
    }

    public List<Blocker> blockers() {
        return blockers;
    }

    @Override
    public String getMessage() {
        return blockers.stream().map(blocker -> blocker.lawyerName() + " sigue a cargo de: "
                        + String.join("; ", blocker.clients().stream().map(name -> "cliente " + name).toList())
                        + (blocker.clients().isEmpty() || blocker.activities().isEmpty() ? "" : "; ")
                        + String.join("; ", blocker.activities().stream().map(name -> "actividad " + name).toList())
                        + ".")
                .collect(Collectors.joining(" ")) + " Reasígnalos antes de desactivarlo o quitarlo.";
    }
}
