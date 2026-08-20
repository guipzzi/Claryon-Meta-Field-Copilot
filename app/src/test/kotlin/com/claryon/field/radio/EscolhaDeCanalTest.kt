package com.claryon.field.radio

import com.claryon.net.GrupoFalado
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * **O canal deixou de ser um UUID escrito no código.**
 *
 * `CanalDoPiloto.ID` era o canal de todo mundo. Um agente de outra lotação que
 * instalasse o APK entrava no canal do piloto: ouvia e falava com uma guarnição
 * que não é a dele. O servidor recusaria a ESCRITA por RLS, mas o áudio do
 * Realtime e o mapa vinham do id que o cliente pedisse — e o nome na tela também
 * saía da constante, então nada denunciava.
 *
 * O ramo que conserta isso não é alcançável no aparelho de teste, porque lá o
 * agente pertence ao grupo do piloto. Por isso a decisão é uma função pura.
 */
class EscolhaDeCanalTest {

    private fun g(id: String, nome: String) =
        GrupoFalado(id = id, nome = nome, rotuloFalado = nome.lowercase())

    private val piloto = "22222222-0000-0000-0000-000000000001"

    @Test
    fun seOCanalCorrenteEDoAgente_ele_FICA() {
        val lista = listOf(g("outro", "GTA-9 Bravo"), g(piloto, "GTA-3 Alfa"))
        assertEquals(
            "trocar por trocar tiraria o agente de uma guarnição em que já operava",
            piloto,
            escolherCanal(piloto, lista).id,
        )
    }

    /** O nome vem do cadastro mesmo quando o id não muda: o da constante é chute. */
    @Test
    fun oNOME_vemSempreDoServidor() {
        val lista = listOf(g(piloto, "GTA-3 Alfa — 2º turno"))
        assertEquals("GTA-3 Alfa — 2º turno", escolherCanal(piloto, lista).nome)
    }

    /**
     * **O ramo que o aparelho não alcança, e o motivo desta classe existir.**
     */
    @Test
    fun seOCanalCorrenteNAO_EDoAgente_assumeOPrimeiroDELE() {
        val lista = listOf(g("meu-grupo", "GTA-9 Bravo"), g("outro", "GTA-7 Charlie"))
        val escolhido = escolherCanal(piloto, lista)
        assertEquals(
            "o agente ficou no canal do piloto — ouvindo uma guarnição que não é a dele",
            "meu-grupo",
            escolhido.id,
        )
        assertEquals("GTA-9 Bravo", escolhido.nome)
    }

    @Test
    fun umGrupoSo_eEle() {
        assertEquals("unico", escolherCanal(piloto, listOf(g("unico", "GTA-1"))).id)
    }
}
