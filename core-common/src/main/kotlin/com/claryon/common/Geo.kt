package com.claryon.common

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geometria esférica — distância e rumo entre dois pontos.
 *
 * Está em `core-common` porque três módulos precisam dela e **nenhum pode
 * depender do outro**: o mapa desenha, a consulta por voz fala e o fan-out do
 * alerta escolhe quem recebe. Uma implementação, três consumidores.
 *
 * Pura: nenhuma dependência de Android, testável na JVM.
 */
object Geo {

    /** Raio médio da Terra. */
    private const val RAIO_M = 6_371_008.8

    /**
     * Distância em metros pela fórmula de haversine.
     *
     * Haversine e não a lei dos cossenos: em distâncias curtas — que são
     * **todas** as que importam aqui, guarnição na mesma área — a lei dos
     * cossenos perde precisão por cancelamento em ponto flutuante. Errar 200 m
     * numa distância de 300 m muda a decisão do agente.
     */
    fun distanciaM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return 2 * RAIO_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /**
     * Rumo inicial de 1 para 2, em graus de 0 (norte) a 360, sentido horário.
     *
     * É o rumo **geográfico**, não o magnético: é o que casa com coordenadas de
     * GPS. Converter para magnético exigiria a declinação local, que muda com a
     * posição e com o ano — e o agente ouve "nordeste", não um número de bússola.
     */
    fun rumoGraus(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val f1 = Math.toRadians(lat1)
        val f2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(f2)
        val x = cos(f1) * sin(f2) - sin(f1) * cos(f2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /**
     * `true` se o deslocamento entre dois pontos supera [limiarM].
     *
     * Usado como gatilho de publicação: agente parado não deve gastar rádio nem
     * bateria reafirmando a mesma posição. Compara com a distância real, não com
     * a diferença de coordenadas — um grau de longitude vale 111 km no equador e
     * 0 no polo, e o piloto roda em Goiânia, a 16° de latitude.
     */
    fun deslocouMaisQue(
        limiarM: Float,
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double,
    ): Boolean = distanciaM(lat1, lon1, lat2, lon2) > abs(limiarM)
}
