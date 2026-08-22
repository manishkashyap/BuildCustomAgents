package com.manish.customagents.contracts;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;

/**
 * A tenant licence code: non-blank, starting alphanumeric, up to 128 characters of letters,
 * numbers, dots, underscores or hyphens.
 *
 * <p>Composed rather than repeated so tightening the tenant format is a one-line change instead
 * of a hunt through every controller signature.
 */
@Documented
@NotBlank
@Pattern(
        regexp = LicenseCode.PATTERN,
        message = "must contain only letters, numbers, dots, underscores, or hyphens")
@Constraint(validatedBy = {})
@Target({FIELD, METHOD, PARAMETER, RECORD_COMPONENT, ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface LicenseCode {

    String PATTERN = "[A-Za-z0-9][A-Za-z0-9._-]{0,127}";

    String message() default "is not a valid licence code";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
