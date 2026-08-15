package com.claryon.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerPolicyTest {

    @Test
    fun standby_fechaHfp_semWakeWord_semCamera() {
        val p = PowerPolicy.perfil(ModoOperacao.STANDBY)
        assertFalse(p.hfpAberto)
        assertFalse(p.wakeWordAtiva)
        assertFalse(p.cameraPorPadrao)
    }

    @Test
    fun ativo_ouveMasNaoFilma() {
        val p = PowerPolicy.perfil(ModoOperacao.ATIVO)
        assertTrue(p.hfpAberto)
        assertTrue(p.wakeWordAtiva)
        assertFalse("câmera desligada por padrão no modo Ativo", p.cameraPorPadrao)
    }

    @Test
    fun ocorrencia_ligaTudo_eSuprimeInformativos() {
        val p = PowerPolicy.perfil(ModoOperacao.OCORRENCIA)
        assertTrue(p.hfpAberto)
        assertTrue(p.cameraPorPadrao)
        assertTrue("Modo Tático suprime nível 3", p.suprimeInformativos)
    }

    @Test
    fun tiposDeServico_declaramExatamenteOQueOModoUsa() {
        // LOCATION está em **todos** os modos, Standby incluso: o que muda com o
        // modo é a cadência e o provedor, não a existência da coleta. Um agente
        // que some do mapa em pausa parece em perigo.
        assertEquals(
            setOf(TipoServico.CONNECTED_DEVICE, TipoServico.LOCATION),
            PowerPolicy.tiposDeServico(ModoOperacao.STANDBY),
        )
        assertEquals(
            setOf(TipoServico.CONNECTED_DEVICE, TipoServico.LOCATION, TipoServico.MICROPHONE),
            PowerPolicy.tiposDeServico(ModoOperacao.ATIVO),
        )
        assertEquals(
            setOf(
                TipoServico.CONNECTED_DEVICE,
                TipoServico.LOCATION,
                TipoServico.MICROPHONE,
                TipoServico.CAMERA,
            ),
            PowerPolicy.tiposDeServico(ModoOperacao.OCORRENCIA),
        )
    }

    @Test
    fun aColetaDePosicaoNuncaDesliga() {
        // Regra de segurança antes de ser de energia, e por isso vale para os três
        // modos: a economia vem da cadência, não de desligar.
        for (modo in ModoOperacao.entries) {
            assertTrue(
                "$modo deixou de declarar LOCATION",
                TipoServico.LOCATION in PowerPolicy.tiposDeServico(modo),
            )
        }
    }
}

class ThermalGovernorTest {

    @Test
    fun nanNaoEZero_mantemOTetoEBloqueiaRajada() {
        assertEquals(
            "NaN = sem informação → mantém o teto vigente",
            PowerPolicy.FPS_PADRAO,
            ThermalGovernor.fpsPermitido(Float.NaN),
        )
        assertFalse("sem informação não autoriza rajada", ThermalGovernor.podeIniciarRajada(Float.NaN))
    }

    @Test
    fun frio_permiteTetoPadraoERajada() {
        assertEquals(PowerPolicy.FPS_PADRAO, ThermalGovernor.fpsPermitido(0.2f))
        assertTrue(ThermalGovernor.podeIniciarRajada(0.2f))
    }

    @Test
    fun quenteEEstourado_reduzEDesliga() {
        assertEquals(2, ThermalGovernor.fpsPermitido(0.9f))
        assertFalse(ThermalGovernor.podeIniciarRajada(0.9f))
        assertEquals("no limite → câmera desce", 0, ThermalGovernor.fpsPermitido(1.0f))
        assertEquals(0, ThermalGovernor.fpsPermitido(1.4f))
    }
}
