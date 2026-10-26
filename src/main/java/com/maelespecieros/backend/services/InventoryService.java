package com.maelespecieros.backend.services;


import com.maelespecieros.backend.dto.response.MovimientoStockResponse;
import com.maelespecieros.backend.entities.Producto;



public interface InventoryService {






    MovimientoStockResponse ingresarStock(

            Producto producto,

            Integer cantidad,

            String motivo

    );





    MovimientoStockResponse descontarStock(

            Producto producto,

            Integer cantidad,

            String motivo

    );





    MovimientoStockResponse ajustePositivo(

            Producto producto,

            Integer cantidad,

            String motivo

    );





    MovimientoStockResponse ajusteNegativo(

            Producto producto,

            Integer cantidad,

            String motivo

    );



}