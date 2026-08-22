package com.claryon.field.local

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper

/**
 * **O pedaço do `LocationManager` que a coleta usa — e a costura que a torna
 * testável.**
 *
 * `ColetorDePosicao` carrega as decisões que importam (porta de qualidade, filtro
 * de deslocamento, batimento, o que fazer quando o POST falha) e não tinha **um
 * único teste JVM**, porque `LocationManager` não é instanciável fora do sistema:
 * o construtor é oculto, não está no `android.jar`, e não há como subclassificá-lo
 * num teste de unidade. O resultado era que o caminho de FALHA — o único que
 * interessa aqui — só existia no aparelho.
 *
 * A interface é deliberadamente estreita: quatro chamadas, todas mecanismo. Nada
 * de política atravessa esta fronteira.
 */
interface FonteDeCorrecoes {

    /** `FINE` **ou** `COARSE`. Sem nenhuma das duas não há coleta nenhuma. */
    fun temPermissao(): Boolean

    /** Só os provedores **ativos**: `getProviders(true)`. Desligado não conta. */
    fun provedoresAtivos(): List<String>

    /**
     * Assina as correções. Devolve `false` se o sistema recusou — e recusa
     * silenciosa aqui é a coleta inteira morta sem ninguém saber.
     *
     * O `minDistance` é **zero**, sempre. Ver o KDoc de [ColetorDePosicao]: com
     * ele preenchido, agente parado não recebia callback nenhum e o batimento era
     * código inalcançável.
     */
    fun assinar(provedor: String, intervaloMs: Long, ouvinte: LocationListener): Boolean

    fun cancelar(ouvinte: LocationListener)
}

/** A implementação de verdade, sobre o `LocationManager` da plataforma. */
class FonteDoSistema(private val context: Context) : FonteDeCorrecoes {

    private val lm: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    override fun temPermissao(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    override fun provedoresAtivos(): List<String> =
        runCatching { lm?.getProviders(true).orEmpty() }.getOrDefault(emptyList())

    override fun assinar(
        provedor: String,
        intervaloMs: Long,
        ouvinte: LocationListener,
    ): Boolean {
        val manager = lm ?: return false
        return runCatching {
            @Suppress("MissingPermission")
            manager.requestLocationUpdates(provedor, intervaloMs, 0f, ouvinte, Looper.getMainLooper())
            true
        }.getOrDefault(false)
    }

    override fun cancelar(ouvinte: LocationListener) {
        runCatching { lm?.removeUpdates(ouvinte) }
    }
}
