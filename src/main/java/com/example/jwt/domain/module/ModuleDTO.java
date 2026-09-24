package com.example.jwt.domain.module;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

/**
 * A module as the module_service returns it. Only the fields this service passes on are
 * mapped; the timestamps and anything added later are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ModuleDTO(UUID id, String code, String name, String description) {

}
