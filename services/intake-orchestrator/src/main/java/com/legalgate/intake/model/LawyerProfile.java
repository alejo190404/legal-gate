package com.legalgate.intake.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record LawyerProfile(
        String id,
        @NotBlank String displayName,
        @NotBlank @Email String email,
        String meetingUrl,
        Boolean active,
        @NotNull Integer defaultEventDurationMinutes,
        List<@Valid LawyerAvailabilityWindow> availabilityWindows
) {
    public LawyerProfile(
            String id,
            String displayName,
            String email,
            Boolean active,
            Integer defaultEventDurationMinutes,
            List<LawyerAvailabilityWindow> availabilityWindows
    ) {
        this(id, displayName, email, null, active, defaultEventDurationMinutes, availabilityWindows);
    }
}
