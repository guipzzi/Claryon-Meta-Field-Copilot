package com.claryon.field.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.claryon.field.auth.SessaoDoAgente
import com.claryon.net.ConsultaRecebida
import com.claryon.net.HistoricoDoCanal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * **Quem consultou a minha posição** — a contrapartida do log de acesso.
 *
 * A `0017` criou `quem_me_consultou()` com esta justificativa: *"saber onde um
 * colega está é poder; um sistema que concede esse poder sem deixar rastro não tem
 * como distinguir a consulta legítima — apoio a caminho — da vigilância de um
 * agente sobre outro"*. E o desenho dela devolve o controle ao titular: ele vê quem
 * perguntou por ele, e a conformidade vira característica em vez de promessa.
 *
 * Só que a função ficou dias no banco **sem um único chamador Kotlin**. Um log de
 * auditoria que o auditado não pode ler não é transparência: é vigilância com um
 * passo a mais. Esta é a porta que faltava.
 *
 * ### Duas escolhas que parecem detalhe e não são
 *
 * **Carrega sob demanda, não em laço.** A tela de perfil não fica aberta, e sondar
 * periodicamente transformaria a própria consulta de transparência numa fonte de
 * tráfego. Uma leitura por abertura basta.
 *
 * **Lista vazia é uma afirmação, não um estado neutro.** "Ninguém consultou você"
 * é informação; um espaço em branco é ambiguidade entre "ninguém consultou" e "não
 * consegui perguntar". Por isso o [Estado] separa os dois — e a falha diz que
 * falhou, em vez de fingir ausência de consultas.
 */
class QuemMeConsultouViewModel(app: Application) : AndroidViewModel(app) {

    sealed interface Estado {
        data object Carregando : Estado
        data class Lista(val consultas: List<ConsultaRecebida>) : Estado
        data class Indisponivel(val motivo: String) : Estado
    }

    private val _estado = MutableStateFlow<Estado>(Estado.Carregando)
    val estado: StateFlow<Estado> = _estado.asStateFlow()

    fun carregar() {
        _estado.value = Estado.Carregando
        viewModelScope.launch(Dispatchers.IO) {
            val historico = HistoricoDoCanal(
                config = SessaoDoAgente.config,
                tokenDeSessao = { SessaoDoAgente.tokenValido(getApplication()) },
            )
            _estado.value = historico.quemMeConsultou().fold(
                onSuccess = { Estado.Lista(it) },
                onFailure = {
                    Log.w(TAG, "quem_me_consultou falhou", it)
                    Estado.Indisponivel("Não foi possível ler o registro agora.")
                },
            )
        }
    }

    private companion object {
        const val TAG = "ClaryonField"
    }
}
