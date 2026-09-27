package com.opscenter.integration.application.alertmanager;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * The labels map of an alert must carry a non-blank {@code alertname} (FR-ALT-02: without a name
 * there is no fingerprint). The violation is reported on the path {@code alerts[i].labels.alertname}
 * so a client sees exactly which alert of the batch is wrong (TC-ALT-003).
 * <p>
 * Target is {@code FIELD} only: on a record component a wider target would also copy the annotation
 * to the accessor method and the constraint could be reported twice.
 */
@Documented
@Constraint(validatedBy = AlertNamePresentValidator.class)
@Target({ElementType.FIELD, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface AlertNamePresent {

    String message() default "must be present and not blank";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
