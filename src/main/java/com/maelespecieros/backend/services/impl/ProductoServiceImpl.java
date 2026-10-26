package com.maelespecieros.backend.services.impl;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.maelespecieros.backend.dto.request.ProductoRequest;
import com.maelespecieros.backend.dto.response.ProductoResponse;
import com.maelespecieros.backend.entities.Categoria;
import com.maelespecieros.backend.entities.Producto;
import com.maelespecieros.backend.exceptions.ResourceNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import com.maelespecieros.backend.entities.HistorialPrecio;
import com.maelespecieros.backend.repositories.CategoriaRepository;
import com.maelespecieros.backend.repositories.HistorialPrecioRepository;
import com.maelespecieros.backend.repositories.ProductoRepository;
import com.maelespecieros.backend.services.BlockchainService;
import com.maelespecieros.backend.services.NumeradorService;
import com.maelespecieros.backend.services.ProductoService;

@Service
@Transactional
public class ProductoServiceImpl 
        implements ProductoService {

    private final ProductoRepository repository;

    private final CategoriaRepository categoriaRepository;

    private final NumeradorService numeradorService;

    private final BlockchainService blockchainService;

    private final HistorialPrecioRepository historialPrecioRepository;

    public ProductoServiceImpl(
            ProductoRepository repository,
            CategoriaRepository categoriaRepository,
            NumeradorService numeradorService,
            BlockchainService blockchainService,
            HistorialPrecioRepository historialPrecioRepository
    ){
        this.repository = repository;
        this.categoriaRepository = categoriaRepository;
        this.numeradorService = numeradorService;
        this.blockchainService = blockchainService;
        this.historialPrecioRepository = historialPrecioRepository;
    }


    private String getUsuarioAutenticado() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth != null && auth.getName() != null) ? auth.getName() : "SISTEMA";
    }

    @Override
    public ProductoResponse crear(

            ProductoRequest request

    ){

        Categoria categoria =

                categoriaRepository.findById(
                        request.categoriaId()
                )

                .orElseThrow(
                        () ->
                        new ResourceNotFoundException( // <-- CORREGIDO
                                "Categoría no encontrada."
                        )
                );


        Producto producto =
                new Producto();


        producto.setCodigoProducto(

                numeradorService.generarCodigoProducto(
                        categoria.getPrefijo()
                )

        );


        producto.setNombre(
                request.nombre()
        );


        producto.setDescripcion(
                request.descripcion()
        );


        producto.setModelo(
                request.modelo()
        );


        producto.setPrecioEfectivo(
                request.precioEfectivo()
        );


        producto.setCosto(
                request.costo()
        );

        // Al crear el producto se asigna la cantidad/stock inicial correspondiente
        producto.setStock(
                request.stock() != null ? request.stock() : 0
        );

        producto.setStockMinimo(
                request.stockMinimo()
        );

        producto.setCategoria(
                categoria
        );

        producto.setActivo(true);

        repository.save(producto);

        blockchainService.registrarBloque(
                getUsuarioAutenticado(),
                "CREAR_PRODUCTO",
                "Producto creado: " + producto.getCodigoProducto(),
                "PRODUCTO",
                producto.getCodigoProducto(),
                producto
        );


        return convertir(producto);

    }


    @Override
    @Transactional(readOnly = true)
    public Page<ProductoResponse> listar(Pageable pageable){

        return repository
                .findByActivoTrue(pageable)
                .map(this::convertir);

    }

    @Override
    @Transactional(readOnly = true)
    public Page<ProductoResponse> listarInactivos(Pageable pageable){

        return repository
                .findByActivoFalse(pageable)
                .map(this::convertir);

    }


    @Override
    @Transactional(readOnly = true)
    public ProductoResponse obtenerPorId(

            Long id

    ){

        Producto producto =

                repository.findById(id)

                .orElseThrow(

                        () ->
                        new ResourceNotFoundException( // <-- CORREGIDO
                                "Producto no encontrado."
                        )

                );


        return convertir(producto);

    }


    @Override
    @Transactional(readOnly = true)
    public ProductoResponse buscarPorCodigo(

            String codigo

    ){

        Producto producto =

                repository.findByCodigoProducto(codigo)

                .orElseThrow(

                        () ->
                        new ResourceNotFoundException( // <-- CORREGIDO
                                "Producto no encontrado."
                        )

                );


        return convertir(producto);

    }


    @Override
    @Transactional(readOnly = true)
    public Page<ProductoResponse> buscarPorNombre(

            String nombre,
            Pageable pageable

    ){

        return repository
                .buscarPorTermino(nombre, pageable)

                .map(this::convertir);

    }


    @Override
    @Transactional(readOnly = true)
    public Page<ProductoResponse> listarPorCategoria(

            Long categoriaId,
            Pageable pageable

    ){

        return repository

                .findByCategoriaIdAndActivoTrue(
                        categoriaId, pageable
                )

                .map(this::convertir);

    }


    @Override
    public ProductoResponse actualizar(

            Long id,

            ProductoRequest request

    ){

        Producto producto =

                repository.findById(id)

                .orElseThrow(

                        () ->
                        new ResourceNotFoundException( // <-- CORREGIDO
                                "Producto no encontrado."
                        )

                );

        Categoria categoria =
                categoriaRepository.findById(
                        request.categoriaId()
                )
                .orElseThrow(
                        () ->
                        new ResourceNotFoundException(
                                "Categoría no encontrada."
                        )
                );
        producto.setCategoria(categoria);

        producto.setNombre(
                request.nombre()
        );


        producto.setDescripcion(
                request.descripcion()
        );


        producto.setModelo(
                request.modelo()
        );


        producto.setStockMinimo(
                request.stockMinimo()
        );

        // La cantidad (stock) NO se puede cambiar desde la edición de producto; solo por Movimientos
        // El valor (precio) y costo SOLO lo pueden cambiar Administradores o Root
        org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        boolean isEmpleado = auth != null && auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_EMPLEADO"));

        if (!isEmpleado) {
            if (request.costo() != null) {
                producto.setCosto(request.costo());
            }

            BigDecimal precioAnterior = producto.getPrecioEfectivo();
            if (precioAnterior != null && request.precioEfectivo() != null && precioAnterior.compareTo(request.precioEfectivo()) != 0) {
                HistorialPrecio historial = new HistorialPrecio();
                historial.setProducto(producto);
                historial.setPrecioAnterior(precioAnterior);
                historial.setPrecioNuevo(request.precioEfectivo());
                historial.setFechaCambio(java.time.LocalDateTime.now());
                historial.setUsuarioResponsable(getUsuarioAutenticado());
                historialPrecioRepository.save(historial);
            }

            if (request.precioEfectivo() != null) {
                producto.setPrecioEfectivo(request.precioEfectivo());
            }
        }


        repository.save(producto);


        blockchainService.registrarBloque(
                getUsuarioAutenticado(),
                "ACTUALIZAR_PRODUCTO",
                "Producto actualizado: " + producto.getCodigoProducto(),
                "PRODUCTO",
                producto.getCodigoProducto(),
                producto
        );


        return convertir(producto);

    }


    @Override
    public void desactivar(

            Long id

    ){

        Producto producto =

                repository.findById(id)

                .orElseThrow(

                        () ->
                        new ResourceNotFoundException( // <-- CORREGIDO
                                "Producto no encontrado."
                        )

                );


        producto.setActivo(false);


        repository.save(producto);


        blockchainService.registrarBloque(
                getUsuarioAutenticado(),
                "DESACTIVAR_PRODUCTO",
                "Producto desactivado: " + producto.getCodigoProducto(),
                "PRODUCTO",
                producto.getCodigoProducto(),
                producto
        );

    }


    @Override
    public void activar(

            Long id

    ){

        Producto producto =

                repository.findById(id)

                .orElseThrow(

                        () ->
                        new ResourceNotFoundException( // <-- CORREGIDO
                                "Producto no encontrado."
                        )

                );


        producto.setActivo(true);


        repository.save(producto);


        blockchainService.registrarBloque(
                getUsuarioAutenticado(),
                "ACTIVAR_PRODUCTO",
                "Producto activado: " + producto.getCodigoProducto(),
                "PRODUCTO",
                producto.getCodigoProducto(),
                producto
        );

    }


    private ProductoResponse convertir(

            Producto producto

    ){

        return new ProductoResponse(

                producto.getId(),

                producto.getCodigoProducto(),

                producto.getNombre(),

                producto.getDescripcion(),

                producto.getModelo(),

                producto.getPrecioEfectivo(),

                producto.getCosto(),

                producto.getStock(),

                producto.getStockMinimo(),

                producto.getCategoria().getId(),

                producto.getCategoria().getNombre(),

                producto.getActivo()

        );

    }

    @Override
    public void aumentoMasivo(List<Long> ids, BigDecimal porcentaje) {
        if (ids == null || ids.isEmpty() || porcentaje == null || porcentaje.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        BigDecimal factorMultiplicador = BigDecimal.ONE.add(porcentaje.divide(new BigDecimal("100"), 4, java.math.RoundingMode.HALF_UP));

        List<Producto> productos = repository.findAllById(ids);
        
        for (Producto producto : productos) {
            BigDecimal precioActual = producto.getPrecioEfectivo();
            if (precioActual != null) {
                BigDecimal nuevoPrecio = precioActual.multiply(factorMultiplicador).setScale(2, java.math.RoundingMode.HALF_UP);
                producto.setPrecioEfectivo(nuevoPrecio);
                repository.save(producto);

                HistorialPrecio historial = new HistorialPrecio();
                historial.setProducto(producto);
                historial.setPrecioAnterior(precioActual);
                historial.setPrecioNuevo(nuevoPrecio);
                historial.setFechaCambio(java.time.LocalDateTime.now());
                historial.setUsuarioResponsable(getUsuarioAutenticado());
                historialPrecioRepository.save(historial);

                blockchainService.registrarBloque(
                        getUsuarioAutenticado(),
                        "AUMENTO_MASIVO",
                        "Precio aumentado un " + porcentaje + "% al producto: " + producto.getCodigoProducto(),
                        "PRODUCTO",
                        producto.getCodigoProducto(),
                        producto
                );
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<com.maelespecieros.backend.dto.response.HistorialPrecioResponse> obtenerHistorialPrecios(Long id) {
        return historialPrecioRepository.findByProductoIdOrderByFechaCambioDesc(id)
                .stream()
                .map(h -> new com.maelespecieros.backend.dto.response.HistorialPrecioResponse(
                        h.getId(),
                        h.getProducto().getId(),
                        h.getPrecioAnterior(),
                        h.getPrecioNuevo(),
                        h.getFechaCambio(),
                        h.getUsuarioResponsable()
                ))
                .collect(java.util.stream.Collectors.toList());
    }

}