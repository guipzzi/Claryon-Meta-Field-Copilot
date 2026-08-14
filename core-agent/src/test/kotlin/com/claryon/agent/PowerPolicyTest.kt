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
        assertEquals(
            setOf(TipoServico.CONNECTED_DEVICE),
            PowerPolicy.tiposDeServico(ModoOperacao.STANDBY),
        )
        assertEquals(
            setOf(TipoServico.CONNECTED_DEVICE, TipoServico.MICROPHONE),
            PowerPolicy.tiposDeServico(ModoOperacao.ATIVO),
        )
        assertEquals(
            setOf(TipoServico.CONNECTED_DEVICE, TipoServico.MICROPHONE, TipoServico.CAMERA),
            PowerPolicy.tiposDeServico(ModoOperacao.OCORRENCIA),
        )
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
