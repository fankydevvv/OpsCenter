package com.opscenter.integration.application.alertmanager;

import java.util.Map;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** Implements {@link AlertNamePresent}: reports the error one level deeper, on {@code labels.alertname}. */
public class AlertNamePresentValidator implements ConstraintValidator<AlertNamePresent, Map<String, String>> {

    public static final String ALERTNAME = "alertname";

    @Override
    public boolean isValid(Map<String, String> labels, ConstraintValidatorContext context) {
        if (labels == null) {
            return true; // @NotNull reports that case
        }
        String name = labels.get(ALERTNAME);
        if (name != null && !name.isBlank()) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                .addPropertyNode(ALERTNAME)
                .addConstraintViolation();
        return false;
    }
}
