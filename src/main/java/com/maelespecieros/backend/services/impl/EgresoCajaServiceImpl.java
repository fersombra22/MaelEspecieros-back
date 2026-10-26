package com.maelespecieros.backend.services.impl;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.maelespecieros.backend.dto.request.EgresoCajaRequest;
import com.maelespecieros.backend.dto.response.EgresoCajaResponse;
import com.maelespecieros.backend.entities.Caja;
import com.maelespecieros.backend.entities.EgresoCaja;
import com.maelespecieros.backend.entities.EstadoCaja;
import com.maelespecieros.backend.entities.FormaPago;
import com.maelespecieros.backend.entities.TipoMedioEgreso;
import com.maelespecieros.backend.entities.Usuario;
import com.maelespecieros.backend.exceptions.BusinessException;
import com.maelespecieros.backend.repositories.CajaRepository;
import com.maelespecieros.backend.repositories.EgresoCajaRepository;
import com.maelespecieros.backend.repositories.UsuarioRepository;
import com.maelespecieros.backend.repositories.VentaRepository;
import com.maelespecieros.backend.services.BlockchainService;
import com.maelespecieros.backend.services.EgresoCajaService;

import com.maelespecieros.backend.entities.Rol;

@Service
@Transactional
public class EgresoCajaServiceImpl implements EgresoCajaService {

    private static final Logger log = LoggerFactory.getLogger(EgresoCajaServiceImpl.class);

    private final EgresoCajaRepository egresoCajaRepository;
    private final CajaRepository cajaRepository;
    private final VentaRepository ventaRepository;
    private final UsuarioRepository usuarioRepository;
    private final BlockchainService blockchainService;

    public EgresoCajaServiceImpl(
            EgresoCajaRepository egresoCajaRepository,
            CajaRepository cajaRepository,
            VentaRepository ventaRepository,
            UsuarioRepository usuarioRepository,
            BlockchainService blockchainService
    ) {
        this.egresoCajaRepository = egresoCajaRepository;
        this.cajaRepository = cajaRepository;
        this.ventaRepository = ventaRepository;
        this.usuarioRepository = usuarioRepository;
        this.blockchainService = blockchainService;
    }

    private Usuario getUsuarioActual() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String username = (auth != null && auth.getName() != null) ? auth.getName() : null;

        if (username != null) {
            Optional<Usuario> usuarioOpt = usuarioRepository.findByUsername(username);
            if (usuarioOpt.isPresent()) {
                return usuarioOpt.get();
            }
        }
        return usuarioRepository.findAll().stream().findFirst()
                .orElseThrow(() -> new BusinessException("No existen usuarios registrados para asociar el egreso de caja."));
    }

    @Override
    public EgresoCajaResponse registrarEgreso(EgresoCajaRequest request) {
        // Control de Seguridad Estricto: Solo ADMIN y SUPER_ADMIN pueden retirar o egresar dinero
        Usuario usuario = getUsuarioActual();
        if (usuario.getRol() != Rol.SUPER_ADMIN && usuario.getRol() != Rol.ADMIN) {
            throw new BusinessException("Acceso denegado: Únicamente los usuarios con rol SUPER_ADMIN o ADMIN están autorizados para realizar egresos o retiros de dinero de la caja.");
        }

        Caja caja = cajaRepository.findFirstByEstadoOrderByFechaAperturaDesc(EstadoCaja.ABIERTA)
                .orElseThrow(() -> new BusinessException("No es posible registrar egresos porque la caja se encuentra actualmente cerrada. Realice la apertura de caja antes de operar."));

        if (request.monto() == null || request.monto().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("El monto del egreso debe ser superior a cero.");
        }

        LocalDateTime ahora = LocalDateTime.now();
        List<Object[]> ventasPorMetodo = ventaRepository.obtenerTotalesPorMetodoPagoEntreFechas(caja.getFechaApertura(), ahora);

        BigDecimal ventasEfectivo = BigDecimal.ZERO;
        BigDecimal ventasTransferencia = BigDecimal.ZERO;

        for (Object[] fila : ventasPorMetodo) {
            FormaPago forma = (FormaPago) fila[0];
            BigDecimal total = (BigDecimal) fila[1];
            if (forma != null && total != null) {
                if (forma == FormaPago.EFECTIVO) {
                    ventasEfectivo = ventasEfectivo.add(total);
                } else if (forma == FormaPago.TRANSFERENCIA) {
                    ventasTransferencia = ventasTransferencia.add(total);
                }
            }
        }

        BigDecimal egresosEfectivoPrevios = egresoCajaRepository.sumarPorCajaYTipoMedio(caja.getId(), TipoMedioEgreso.EFECTIVO);
        if (egresosEfectivoPrevios == null) egresosEfectivoPrevios = BigDecimal.ZERO;

        BigDecimal egresosTransferenciaPrevios = egresoCajaRepository.sumarPorCajaYTipoMedio(caja.getId(), TipoMedioEgreso.TRANSFERENCIA);
        if (egresosTransferenciaPrevios == null) egresosTransferenciaPrevios = BigDecimal.ZERO;

        BigDecimal fondoInicial = caja.getMontoInicial() != null ? caja.getMontoInicial() : BigDecimal.ZERO;

        if (request.tipoMedio() == TipoMedioEgreso.EFECTIVO) {
            // REGLA FUNDAMENTAL DE NEGOCIO: El fondo inicial de cambio es INTOCABLE.
            // Solo se puede retirar del efectivo neto generado por ventas en el turno actual.
            BigDecimal efectivoDisponible = ventasEfectivo.subtract(egresosEfectivoPrevios);
            if (efectivoDisponible.compareTo(BigDecimal.ZERO) < 0) {
                efectivoDisponible = BigDecimal.ZERO;
            }

            if (request.monto().compareTo(efectivoDisponible) > 0) {
                throw new BusinessException(
                        "Fondos de ventas en efectivo insuficientes. El disponible generado por ventas en este turno es $" +
                        efectivoDisponible.setScale(2, java.math.RoundingMode.HALF_UP) +
                        " (Ventas en efectivo: $" + ventasEfectivo.setScale(2, java.math.RoundingMode.HALF_UP) +
                        ", Egresos previos: $" + egresosEfectivoPrevios.setScale(2, java.math.RoundingMode.HALF_UP) +
                        "). El monto inicial de cambio ($" + fondoInicial.setScale(2, java.math.RoundingMode.HALF_UP) +
                        ") está protegido y no puede ser retirado bajo ninguna circunstancia porque debe permanecer como cambio en la gaveta."
                );
            }
        } else if (request.tipoMedio() == TipoMedioEgreso.TRANSFERENCIA) {
            // Solo se puede egresar por transferencia si ha ingresado dinero al banco por transferencias en este turno.
            BigDecimal transferenciaDisponible = ventasTransferencia.subtract(egresosTransferenciaPrevios);
            if (transferenciaDisponible.compareTo(BigDecimal.ZERO) < 0) {
                transferenciaDisponible = BigDecimal.ZERO;
            }

            if (request.monto().compareTo(transferenciaDisponible) > 0) {
                throw new BusinessException(
                        "Fondos bancarios insuficientes en el turno. Solo han ingresado al banco por transferencias $" +
                        ventasTransferencia.setScale(2, java.math.RoundingMode.HALF_UP) +
                        " (Egresos bancarios previos: $" + egresosTransferenciaPrevios.setScale(2, java.math.RoundingMode.HALF_UP) +
                        "). El saldo transferible disponible de este turno es $" +
                        transferenciaDisponible.setScale(2, java.math.RoundingMode.HALF_UP) +
                        " y se intentó egresar $" + request.monto().setScale(2, java.math.RoundingMode.HALF_UP) + "."
                );
            }
        }

        EgresoCaja egreso = new EgresoCaja();
        egreso.setCaja(caja);
        egreso.setUsuario(usuario);
        egreso.setMonto(request.monto());
        egreso.setTipoMedio(request.tipoMedio());
        egreso.setMotivo(request.motivo().trim());
        egreso.setComprobante(request.comprobante() != null ? request.comprobante().trim() : null);
        egreso.setFecha(ahora);
        egreso.firmarIntegridad();

        EgresoCaja guardado = egresoCajaRepository.save(egreso);

        // Registro de Auditoría Inmutable en Blockchain
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("egresoId", guardado.getId());
        payload.put("cajaId", caja.getId());
        payload.put("monto", guardado.getMonto());
        payload.put("tipoMedio", guardado.getTipoMedio().name());
        payload.put("motivo", guardado.getMotivo());
        payload.put("comprobante", guardado.getComprobante());
        payload.put("fecha", guardado.getFecha().toString());
        payload.put("cajero", usuario.getUsername());

        blockchainService.registrarBloque(
                usuario.getUsername(),
                "EGRESO_CAJA",
                "Egreso de caja #" + caja.getId() + " por $" + guardado.getMonto() + " (" + guardado.getTipoMedio() + ") - Motivo: " + guardado.getMotivo(),
                "CAJA",
                caja.getId().toString(),
                payload
        );

        log.info("Egreso de caja registrado exitosamente: ID {}, Monto ${}, Medio {}, Caja ID {}",
                guardado.getId(), guardado.getMonto(), guardado.getTipoMedio(), caja.getId());

        return toResponse(guardado);
    }

    @Override
    @Transactional(readOnly = true)
    public List<EgresoCajaResponse> listarEgresosCajaActual() {
        Optional<Caja> cajaOpt = cajaRepository.findFirstByEstadoOrderByFechaAperturaDesc(EstadoCaja.ABIERTA);
        if (cajaOpt.isEmpty()) {
            return List.of();
        }
        return egresoCajaRepository.findByCajaIdOrderByFechaDesc(cajaOpt.get().getId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<EgresoCajaResponse> listarEgresosPorCaja(Long cajaId) {
        return egresoCajaRepository.findByCajaIdOrderByFechaDesc(cajaId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private EgresoCajaResponse toResponse(EgresoCaja egreso) {
        return new EgresoCajaResponse(
                egreso.getId(),
                egreso.getCaja().getId(),
                egreso.getMonto(),
                egreso.getTipoMedio(),
                egreso.getMotivo(),
                egreso.getComprobante(),
                egreso.getFecha(),
                egreso.getUsuario().getId(),
                egreso.getUsuario().getNombre() != null ? egreso.getUsuario().getNombre() : egreso.getUsuario().getUsername()
        );
    }
}
