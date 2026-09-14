package com.aicompany.core.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * {@code systemEmail}/{@code mailPassword} son opcionales: permiten
 * actualizar solo el correo de alertas sin tener que retipear la clave del
 * sistema cada vez. {@code mailPassword} en blanco u omitida no borra la
 * clave ya guardada — ver {@code CompanyController.updateSettings}.
 */
public record SettingsCommand(
        @NotBlank @Email String alertEmail,
        @Email String systemEmail,
        String mailPassword
) {
}
