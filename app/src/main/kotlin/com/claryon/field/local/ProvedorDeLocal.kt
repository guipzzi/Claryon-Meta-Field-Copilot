package com.claryon.field.local

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.SystemClock
import com.claryon.agent.PoliticaDePosicao
import com.claryon.field.agent.Coordenada

/**
 * A posição própria — a que alimenta a consulta por voz e carimba o alerta.
 *
 * Usa o `LocationManager` da plataforma, e não o *fused provider* do Play
 * Services: seria uma dependência de terceiros a mais, com o agravante de que o
 * aparelho da corporação pode não ter os serviços do Google. Um copiloto que
 * perde a localização por causa da variante do Android é um copiloto que perde a
 * localização exatamente onde ele é mais necessário.
 *
 * A coleta contínua fica com o serviço em primeiro plano, sob o [PoliticaDePosicao].
 * Isto aqui é a leitura pontual: a última correção conhecida, **com validade**.
 */
class ProvedorDeLocal(private val context: Context) {

    private val lm: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    fun temPermissao(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * A última correção conhecida, ou `null`.
     *
     * `null` em três situações, e todas viram falha falada em vez de silêncio:
     * sem permissão, sem provedor, ou **correção velha demais**. A última é a que
     * importa: o `LocationManager` guarda a última posição indefinidamente, e ela
     * pode ser de ontem, do outro lado da cidade. Devolver isso como "onde estou"
     * calcularia a distância até o par a partir de um ponto onde o agente não
     * está — e o número sairia com cara de precisão.
     */
    fun ultimaPosicao(): Coordenada? {
        if (!temPermissao()) return null
        val manager = lm ?: return null

        val candidatas = PROVEDORES.mapNotNull { provedor ->
            runCatching {
                // `getLastKnownLocation` lança se o provedor não existir no
                // aparelho — e alguns aparelhos não têm NETWORK. Não pode derrubar
                // a leitura inteira por causa de um provedor ausente.
                @Suppress("MissingPermission")
                manager.getLastKnownLocation(provedor)
            }.getOrNull()
        }

        // A mais recente entre os provedores. `elapsedRealtimeNanos` e não
        // `time`: o relógio de parede pode ser ajustado (fuso, NTP, o próprio
        // usuário) e uma correção de 10 s pareceria de uma hora atrás — ou o
        // contrário, que é pior.
        val agora = SystemClock.elapsedRealtimeNanos()
        val melhor = candidatas.maxByOrNull { it.elapsedRealtimeNanos } ?: return null

        val idadeS = ((agora - melhor.elapsedRealtimeNanos) / 1_000_000_000L).toInt()
        if (PoliticaDePosicao.marcadorObsoleto(idadeS)) return null

        return Coordenada(
            latitude = melhor.latitude,
            longitude = melhor.longitude,
            precisaoM = if (melhor.hasAccuracy()) melhor.accuracy else PRECISAO_DESCONHECIDA,
            // `hasBearing()` é falso parado, e nesse caso o campo fica nulo em
            // vez de zero: zero é NORTE, uma afirmação, e o aparelho não afirmou
            // nada. Seta apontando para o norte porque o agente parou seria
            // exatamente o tipo de dado inventado que este projeto recusa.
            rumoGraus = if (melhor.hasBearing()) melhor.bearing else null,
        )
    }

    private companion object {
        val PROVEDORES = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)

        /**
         * Precisão ausente não vira zero. Zero seria lido como "exato", que é o
         * oposto do que "o aparelho não soube dizer" significa.
         */
        const val PRECISAO_DESCONHECIDA = Float.MAX_VALUE
    }
}
