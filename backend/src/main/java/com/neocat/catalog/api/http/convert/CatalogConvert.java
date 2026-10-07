package com.neocat.catalog.api.http.convert;

import com.neocat.catalog.api.http.dto.ServiceResponse;
import java.util.List;

public final class CatalogConvert {
    private CatalogConvert() {
    }

    public static ServiceResponse service(String name, List<String> instances) {
        return new ServiceResponse(name, List.copyOf(instances));
    }
}
