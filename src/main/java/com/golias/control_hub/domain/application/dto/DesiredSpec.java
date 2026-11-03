package com.golias.control_hub.domain.application.dto;

/** The declared spec. A null field is not managed and never reports drift. */
public record DesiredSpec(String image, Integer replicas) {
}
