package com.neocat.catalog.api.http.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import java.util.List;

@Getter
@AllArgsConstructor
public class ServiceResponse {
    private final String name;

    private final List<String> instances;
}
