package com.maelespecieros.backend.dto.request;

import com.maelespecieros.backend.entities.TipoMovimiento;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record MovimientoStockRequest(

        @NotNull
        Long productoId,

        @NotNull
        TipoMovimiento tipoMovimiento,

        @NotNull
        @Min(value = 1, message = "La cantidad debe ser mayor a 0")
        Integer cantidad,

        @Size(max = 250)
        String motivo

) {
}