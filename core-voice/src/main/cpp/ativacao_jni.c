/**
 * Extrator de características da palavra de ativação: mel + embedding, em ONNX.
 *
 * ## Por que este arquivo existe, em vez de uma dependência
 *
 * A conclusão inicial era que detectar a palavra de ativação por rede treinada
 * custaria `onnxruntime-android` — mais 15 a 25 MB no APK. `javap` e `nm` no
 * artefato disseram outra coisa: o AAR do sherpa-onnx **já embarca**
 * `libonnxruntime.so` (21 MB em arm64-v8a), e essa biblioteca **já exporta**
 * `OrtGetApiBase`, que é a porta de entrada da API C:
 *
 * ```
 * $ nm -D libonnxruntime.so | grep OrtGetApiBase
 * 00000000005b350c T OrtGetApiBase@@VERS_1.27.1
 * ```
 *
 * O que faltava não era o motor — era um chamador. O sherpa expõe só a API dele
 * em Java (`KeywordSpotter`, `OfflineTts`), nunca uma sessão ONNX genérica. Este
 * arquivo é essa ponte: `dlopen` na biblioteca que já vai no APK, `dlsym` no
 * ponto de entrada, e duas sessões. **Custo de APK: zero.** O cabeçalho é MIT e
 * é só cabeçalho — não há binário novo, e por isso não há link novo tampouco.
 *
 * `dlopen` por nome em vez de link em tempo de compilação porque a `.so` chega
 * pelo AAR, não pelo nosso build: pedi-la ao ligador exigiria extrair o AAR no
 * CMake e amarrar o build a um caminho de artefato. Em tempo de execução ela já
 * está no diretório de bibliotecas do processo, e o carregador a encontra.
 *
 * ## O que a cadeia faz
 *
 * ```
 * 16000 amostras (1,0 s)  →  mel [1,1,97,32]  →  /10 + 2  →  três janelas de 76
 *                        →  embedding [3,1,1,96]  →  288 floats
 * ```
 *
 * A escala `/10 + 2` e o passo de 8 quadros entre janelas são do openWakeWord, e
 * são exatamente os que a bancada em Python usou para treinar a cabeça. Divergir
 * aqui daria um detector que funciona no laptop e não no aparelho — o defeito
 * mais caro possível, porque só aparece no fim.
 *
 * Nomes de entrada e saída conferidos no próprio modelo, não escritos de memória:
 * `melspectrogram.onnx` expõe `input`/`output`; `embedding_model.onnx` expõe
 * `input_1`/`conv2d_19`.
 *
 * A cabeça de classificação **não** está aqui: ela é um produto escalar de 288
 * floats e vive em Kotlin. Ver `DetectorDeAtivacao`.
 */
#include <jni.h>
#include <dlfcn.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#include "onnx/onnxruntime_c_api.h"

#define LOG_TAG "ClaryonAtivacao"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

#define AMOSTRAS 16000  /* 1,0 s a 16 kHz */
#define MEL_BINS 32
#define JANELA 76       /* quadros de mel que o embedding consome */
#define HOP 8           /* quadros entre janelas → 80 ms */
#define PILHA 3         /* janelas empilhadas por decisão */
#define EMB_DIM 96
#define SAIDA (PILHA * EMB_DIM)

/**
 * Descarta um `OrtStatus*` que não muda o rumo, sem vazar.
 *
 * A API C devolve status em quase tudo, inclusive em ajustes que não têm plano B
 * — se `SetIntraOpNumThreads` falhar, seguimos com o padrão. Ignorar o retorno,
 * porém, vaza o objeto e apaga o motivo; então descartamos de propósito e com o
 * motivo no log.
 */
#define DESCARTAR(c, expr, oque)                                              \
    do {                                                                      \
        OrtStatus *_s = (expr);                                               \
        if (_s) {                                                             \
            LOGE("%s: %s", (oque), (c)->api->GetErrorMessage(_s));            \
            (c)->api->ReleaseStatus(_s);                                      \
        }                                                                     \
    } while (0)

typedef struct {
    const OrtApi *api;
    OrtEnv *env;
    OrtSessionOptions *opcoes;
    OrtSession *mel;
    OrtSession *emb;
    OrtMemoryInfo *memoria;
} Contexto;

/** A `.so` do sherpa já está no processo; só falta alcançar a API dela. */
static const OrtApi *carregar_api(void) {
    void *lib = dlopen("libonnxruntime.so", RTLD_LAZY | RTLD_LOCAL);
    if (!lib) {
        LOGE("dlopen(libonnxruntime.so) falhou: %s", dlerror());
        return NULL;
    }
    const OrtApiBase *(*base)(void) = (const OrtApiBase *(*)(void)) dlsym(lib, "OrtGetApiBase");
    if (!base) {
        LOGE("dlsym(OrtGetApiBase) falhou: %s", dlerror());
        return NULL;
    }
    return base()->GetApi(ORT_API_VERSION);
}

static void destruir(Contexto *c) {
    if (!c) return;
    if (c->api) {
        if (c->memoria) c->api->ReleaseMemoryInfo(c->memoria);
        if (c->emb) c->api->ReleaseSession(c->emb);
        if (c->mel) c->api->ReleaseSession(c->mel);
        if (c->opcoes) c->api->ReleaseSessionOptions(c->opcoes);
        if (c->env) c->api->ReleaseEnv(c->env);
    }
    free(c);
}

JNIEXPORT jlong JNICALL
Java_com_claryon_voice_DetectorDeAtivacao_nativeCriar(
        JNIEnv *env, jobject thiz, jbyteArray melModelo, jbyteArray embModelo) {
    (void) thiz;
    Contexto *c = calloc(1, sizeof(Contexto));
    if (!c) return 0;

    c->api = carregar_api();
    if (!c->api) { destruir(c); return 0; }

    OrtStatus *st = c->api->CreateEnv(ORT_LOGGING_LEVEL_ERROR, "claryon", &c->env);
    if (st) { LOGE("CreateEnv: %s", c->api->GetErrorMessage(st)); c->api->ReleaseStatus(st); destruir(c); return 0; }

    st = c->api->CreateSessionOptions(&c->opcoes);
    if (st) { c->api->ReleaseStatus(st); destruir(c); return 0; }
    /* Uma linha só: o detector roda contínuo e não pode disputar núcleo com o
     * PTT, que tem prioridade dura de 120 ms até o primeiro quadro. */
    DESCARTAR(c, c->api->SetIntraOpNumThreads(c->opcoes, 1), "SetIntraOpNumThreads");
    DESCARTAR(c, c->api->SetSessionGraphOptimizationLevel(c->opcoes, ORT_ENABLE_ALL),
              "SetSessionGraphOptimizationLevel");

    jsize melTam = (*env)->GetArrayLength(env, melModelo);
    jsize embTam = (*env)->GetArrayLength(env, embModelo);
    jbyte *melBuf = (*env)->GetByteArrayElements(env, melModelo, NULL);
    jbyte *embBuf = (*env)->GetByteArrayElements(env, embModelo, NULL);

    st = c->api->CreateSessionFromArray(c->env, melBuf, (size_t) melTam, c->opcoes, &c->mel);
    if (!st) st = c->api->CreateSessionFromArray(c->env, embBuf, (size_t) embTam, c->opcoes, &c->emb);

    (*env)->ReleaseByteArrayElements(env, melModelo, melBuf, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, embModelo, embBuf, JNI_ABORT);

    if (st) {
        LOGE("CreateSession: %s", c->api->GetErrorMessage(st));
        c->api->ReleaseStatus(st);
        destruir(c);
        return 0;
    }

    st = c->api->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &c->memoria);
    if (st) { c->api->ReleaseStatus(st); destruir(c); return 0; }

    LOGI("detector de ativação pronto (onnxruntime da .so do sherpa, API %d)", ORT_API_VERSION);
    return (jlong) (intptr_t) c;
}

JNIEXPORT void JNICALL
Java_com_claryon_voice_DetectorDeAtivacao_nativeDestruir(JNIEnv *env, jobject thiz, jlong ptr) {
    (void) env; (void) thiz;
    destruir((Contexto *) (intptr_t) ptr);
}

/**
 * Um segundo de áudio entra, 288 floats saem. Devolve 0 em caso de falha, e a
 * falha é sempre silenciosa para o chamador — o detector não pode derrubar o
 * processo do rádio por causa de um quadro ruim.
 */
JNIEXPORT jint JNICALL
Java_com_claryon_voice_DetectorDeAtivacao_nativeEmbutir(
        JNIEnv *env, jobject thiz, jlong ptr, jfloatArray pcm, jfloatArray saida) {
    (void) thiz;
    Contexto *c = (Contexto *) (intptr_t) ptr;
    if (!c || (*env)->GetArrayLength(env, pcm) != AMOSTRAS) return 0;

    jfloat *entrada = (*env)->GetFloatArrayElements(env, pcm, NULL);
    int ok = 0;
    OrtValue *tMel = NULL, *tMelSaida = NULL, *tEmb = NULL, *tEmbSaida = NULL;
    float *janelas = NULL;

    const int64_t formaMel[2] = {1, AMOSTRAS};
    const char *entradaMel[] = {"input"};
    const char *saidaMel[] = {"output"};
    OrtStatus *st = c->api->CreateTensorWithDataAsOrtValue(
            c->memoria, entrada, sizeof(float) * AMOSTRAS, formaMel, 2,
            ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &tMel);
    if (st) goto fim;

    st = c->api->Run(c->mel, NULL, entradaMel, (const OrtValue *const *) &tMel, 1,
                     saidaMel, 1, &tMelSaida);
    if (st) goto fim;

    float *mel = NULL;
    st = c->api->GetTensorMutableData(tMelSaida, (void **) &mel);
    if (st) goto fim;

    /* [1,1,quadros,32] — quantos quadros vieram de fato, sem supor. */
    OrtTensorTypeAndShapeInfo *info = NULL;
    st = c->api->GetTensorTypeAndShape(tMelSaida, &info);
    if (st) goto fim;
    size_t dims = 0;
    DESCARTAR(c, c->api->GetDimensionsCount(info, &dims), "GetDimensionsCount");
    int64_t forma[4] = {0, 0, 0, 0};
    DESCARTAR(c, c->api->GetDimensions(info, forma, dims < 4 ? dims : 4), "GetDimensions");
    c->api->ReleaseTensorTypeAndShapeInfo(info);
    int quadros = (int) forma[2];
    if (quadros < JANELA + HOP * (PILHA - 1)) goto fim;

    /* Escala do openWakeWord e recorte das três janelas, na mesma ordem do treino. */
    janelas = malloc(sizeof(float) * PILHA * JANELA * MEL_BINS);
    if (!janelas) goto fim;
    for (int p = 0; p < PILHA; p++) {
        for (int q = 0; q < JANELA; q++) {
            const float *origem = mel + ((size_t) (p * HOP + q)) * MEL_BINS;
            float *destino = janelas + ((size_t) (p * JANELA + q)) * MEL_BINS;
            for (int b = 0; b < MEL_BINS; b++) destino[b] = origem[b] / 10.0f + 2.0f;
        }
    }

    const int64_t formaEmb[4] = {PILHA, JANELA, MEL_BINS, 1};
    const char *entradaEmb[] = {"input_1"};
    const char *saidaEmb[] = {"conv2d_19"};
    st = c->api->CreateTensorWithDataAsOrtValue(
            c->memoria, janelas, sizeof(float) * PILHA * JANELA * MEL_BINS, formaEmb, 4,
            ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &tEmb);
    if (st) goto fim;

    st = c->api->Run(c->emb, NULL, entradaEmb, (const OrtValue *const *) &tEmb, 1,
                     saidaEmb, 1, &tEmbSaida);
    if (st) goto fim;

    float *vetor = NULL;
    st = c->api->GetTensorMutableData(tEmbSaida, (void **) &vetor);
    if (st) goto fim;

    (*env)->SetFloatArrayRegion(env, saida, 0, SAIDA, vetor);
    ok = 1;

fim:
    if (st) {
        LOGE("embutir: %s", c->api->GetErrorMessage(st));
        c->api->ReleaseStatus(st);
    }
    free(janelas);
    if (tEmbSaida) c->api->ReleaseValue(tEmbSaida);
    if (tEmb) c->api->ReleaseValue(tEmb);
    if (tMelSaida) c->api->ReleaseValue(tMelSaida);
    if (tMel) c->api->ReleaseValue(tMel);
    (*env)->ReleaseFloatArrayElements(env, pcm, entrada, JNI_ABORT);
    return ok;
}
