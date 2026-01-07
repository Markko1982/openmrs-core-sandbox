package org.openmrs.validator;

import org.apache.commons.lang3.StringUtils;
import org.openmrs.PatientIdentifier;
import org.openmrs.PatientIdentifierType;
import org.openmrs.PatientIdentifierType.LocationBehavior;
import org.openmrs.PatientIdentifierType.UniquenessBehavior;
import org.openmrs.annotation.Handler;
import org.openmrs.api.BlankIdentifierException;
import org.openmrs.api.IdentifierNotUniqueException;
import org.openmrs.api.InvalidCheckDigitException;
import org.openmrs.api.InvalidIdentifierFormatException;
import org.openmrs.api.PatientIdentifierException;
import org.openmrs.api.context.Context;
import org.openmrs.patient.IdentifierValidator;
import org.openmrs.patient.UnallowedIdentifierException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;

@Handler(supports = { PatientIdentifier.class }, order = 50)
public class PatientIdentifierValidator implements Validator {

	private static final Logger log = LoggerFactory.getLogger(PatientIdentifierValidator.class);

	@Override
	public boolean supports(Class<?> c) {
		return PatientIdentifier.class.isAssignableFrom(c);
	}

	@Override
	public void validate(Object obj, Errors errors) {
		PatientIdentifier pi = (PatientIdentifier) obj;
		try {
			validateIdentifier(pi);
			ValidateUtil.validateFieldLengths(errors, obj.getClass(), "identifier", "voidReason");
		} catch (Exception e) {
			errors.reject(e.getMessage());
		}
	}

	/**
	 * Core validation entry point
	 */
	public static void validateIdentifier(PatientIdentifier pi) throws PatientIdentifierException {

		if (pi == null) {
			throw new BlankIdentifierException("PatientIdentifier.error.null");
		}

		// ===== CPF CUSTOM VALIDATION (ANTES DO FLUXO PADRÃO) =====
		if (!pi.getVoided() && isCpfIdentifier(pi)) {

			String original = pi.getIdentifier();
			String normalized = normalizeCpf(original);

			if (!isValidCpf(normalized)) {
				throw new PatientIdentifierException("Invalid CPF: " + original, pi);
			}

			// Normaliza para evitar duplicidade mascarado vs não mascarado
			pi.setIdentifier(normalized);
		}
		// ========================================================

		if (!pi.getVoided()) {

			validateIdentifier(pi.getIdentifier(), pi.getIdentifierType());

			LocationBehavior lb = pi.getIdentifierType().getLocationBehavior();
			if (pi.getLocation() == null && (lb == null || lb == LocationBehavior.REQUIRED)) {
				throw new PatientIdentifierException(
						Context.getMessageSourceService().getMessage(
								"PatientIdentifier.location.null",
								new Object[] { pi.getIdentifier() },
								Context.getLocale()));
			}

			if (pi.getIdentifierType().getUniquenessBehavior() != UniquenessBehavior.NON_UNIQUE
					&& Context.getPatientService().isIdentifierInUseByAnotherPatient(pi)) {

				throw new IdentifierNotUniqueException(
						Context.getMessageSourceService().getMessage(
								"PatientIdentifier.error.notUniqueWithParameter",
								new Object[] { pi.getIdentifier() },
								Context.getLocale()),
						pi);
			}
		}
	}

	/**
	 * Default OpenMRS validation
	 */
	public static void validateIdentifier(String identifier, PatientIdentifierType pit)
			throws PatientIdentifierException {

		log.debug("Checking identifier: {} for type: {}", identifier, pit);

		if (pit == null) {
			throw new BlankIdentifierException("PatientIdentifierType.null");
		}
		if (StringUtils.isBlank(identifier)) {
			throw new BlankIdentifierException("PatientIdentifier.error.nullOrBlank");
		}

		checkIdentifierAgainstFormat(identifier, pit.getFormat(), pit.getFormatDescription());

		if (pit.hasValidator()) {
			IdentifierValidator validator = Context.getPatientService().getIdentifierValidator(pit.getValidator());
			checkIdentifierAgainstValidator(identifier, validator);
		}
	}

	// =====================================================================
	// ======================== CPF HELPERS =================================
	// =====================================================================

	private static boolean isCpfIdentifier(PatientIdentifier pi) {
		if (pi.getIdentifierType() == null) {
			return false;
		}
		String name = pi.getIdentifierType().getName();
		return "CPF".equalsIgnoreCase(StringUtils.trimToNull(name));
	}

	private static String normalizeCpf(String rawCpf) {
		if (rawCpf == null) {
			return null;
		}
		return rawCpf.replaceAll("\\D", "");
	}

	private static boolean isValidCpf(String cpf) {

		if (StringUtils.isBlank(cpf) || cpf.length() != 11) {
			return false;
		}

		// elimina CPFs com todos os dígitos iguais
		if (cpf.chars().distinct().count() == 1) {
			return false;
		}

		int[] d = cpf.chars().map(c -> c - '0').toArray();

		int sum1 = 0;
		for (int i = 0; i < 9; i++) {
			sum1 += d[i] * (10 - i);
		}
		int dv1 = 11 - (sum1 % 11);
		if (dv1 >= 10)
			dv1 = 0;
		if (dv1 != d[9])
			return false;

		int sum2 = 0;
		for (int i = 0; i < 10; i++) {
			sum2 += d[i] * (11 - i);
		}
		int dv2 = 11 - (sum2 % 11);
		if (dv2 >= 10)
			dv2 = 0;

		return dv2 == d[10];
	}

	// =====================================================================

	public static void checkIdentifierAgainstFormat(String identifier, String format, String formatDescription)
			throws PatientIdentifierException {

		if (StringUtils.isBlank(format)) {
			return;
		}

		if (!identifier.matches(format)) {
			throw new InvalidIdentifierFormatException(
					getMessage("PatientIdentifier.error.invalidFormat",
							identifier,
							StringUtils.defaultIfBlank(formatDescription, format)));
		}
	}

	public static void checkIdentifierAgainstValidator(String identifier, IdentifierValidator validator)
			throws PatientIdentifierException {

		if (validator == null) {
			return;
		}

		try {
			if (!validator.isValid(identifier)) {
				throw new InvalidCheckDigitException(
						getMessage("PatientIdentifier.error.checkDigitWithParameter", identifier));
			}
		} catch (UnallowedIdentifierException e) {
			throw new InvalidCheckDigitException(
					getMessage("PatientIdentifier.error.unallowedIdentifier",
							identifier, validator.getName()));
		}
	}

	private static String getMessage(String key, String... args) {
		return Context.getMessageSourceService().getMessage(key, args, Context.getLocale());
	}
}
