#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <sys/sysinfo.h>
#include <string.h>
#include "whisper.h"
#include "ggml.h"

#define UNUSED(x) (void)(x)
#define TAG "JNI"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,     TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,     TAG, __VA_ARGS__)

static inline int min(int a, int b) {
    return (a < b) ? a : b;
}

static inline int max(int a, int b) {
    return (a > b) ? a : b;
}

struct input_stream_context {
    size_t offset;
    JNIEnv * env;
    jobject thiz;
    jobject input_stream;

    jmethodID mid_available;
    jmethodID mid_read;
};

size_t inputStreamRead(void * ctx, void * output, size_t read_size) {
    struct input_stream_context* is = (struct input_stream_context*)ctx;

    jint avail_size = (*is->env)->CallIntMethod(is->env, is->input_stream, is->mid_available);
    jint size_to_copy = read_size < avail_size ? (jint)read_size : avail_size;

    jbyteArray byte_array = (*is->env)->NewByteArray(is->env, size_to_copy);

    jint n_read = (*is->env)->CallIntMethod(is->env, is->input_stream, is->mid_read, byte_array, 0, size_to_copy);

    if (size_to_copy != read_size || size_to_copy != n_read) {
        LOGI("Insufficient Read: Req=%zu, ToCopy=%d, Available=%d", read_size, size_to_copy, n_read);
    }

    jbyte* byte_array_elements = (*is->env)->GetByteArrayElements(is->env, byte_array, NULL);
    memcpy(output, byte_array_elements, size_to_copy);
    (*is->env)->ReleaseByteArrayElements(is->env, byte_array, byte_array_elements, JNI_ABORT);

    (*is->env)->DeleteLocalRef(is->env, byte_array);

    is->offset += size_to_copy;

    return size_to_copy;
}
bool inputStreamEof(void * ctx) {
    struct input_stream_context* is = (struct input_stream_context*)ctx;

    jint result = (*is->env)->CallIntMethod(is->env, is->input_stream, is->mid_available);
    return result <= 0;
}
void inputStreamClose(void * ctx) {

}

JNIEXPORT jlong JNICALL
Java_com_whispercppdemo_whisper_WhisperLib_00024Companion_initContextFromInputStream(
        JNIEnv *env, jobject thiz, jobject input_stream) {
    UNUSED(thiz);

    struct whisper_context *context = NULL;
    struct whisper_model_loader loader = {};
    struct input_stream_context inp_ctx = {};

    inp_ctx.offset = 0;
    inp_ctx.env = env;
    inp_ctx.thiz = thiz;
    inp_ctx.input_stream = input_stream;

    jclass cls = (*env)->GetObjectClass(env, input_stream);
    inp_ctx.mid_available = (*env)->GetMethodID(env, cls, "available", "()I");
    inp_ctx.mid_read = (*env)->GetMethodID(env, cls, "read", "([BII)I");

    loader.context = &inp_ctx;
    loader.read = inputStreamRead;
    loader.eof = inputStreamEof;
    loader.close = inputStreamClose;

    loader.eof(loader.context);

    context = whisper_init(&loader);
    return (jlong) context;
}

static size_t asset_read(void *ctx, void *output, size_t read_size) {
    return AAsset_read((AAsset *) ctx, output, read_size);
}

static bool asset_is_eof(void *ctx) {
    return AAsset_getRemainingLength64((AAsset *) ctx) <= 0;
}

static void asset_close(void *ctx) {
    AAsset_close((AAsset *) ctx);
}

static struct whisper_context *whisper_init_from_asset(
        JNIEnv *env,
        jobject assetManager,
        const char *asset_path
) {
    LOGI("Loading model from asset '%s'\n", asset_path);
    AAssetManager *asset_manager = AAssetManager_fromJava(env, assetManager);
    AAsset *asset = AAssetManager_open(asset_manager, asset_path, AASSET_MODE_STREAMING);
    if (!asset) {
        LOGW("Failed to open '%s'\n", asset_path);
        return NULL;
    }

    whisper_model_loader loader = {
            .context = asset,
            .read = &asset_read,
            .eof = &asset_is_eof,
            .close = &asset_close
    };

    return whisper_init_with_params(&loader, whisper_context_default_params());
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_initContextFromAsset(
        JNIEnv *env, jobject thiz, jobject assetManager, jstring asset_path_str) {
    UNUSED(thiz);
    struct whisper_context *context = NULL;
    const char *asset_path_chars = (*env)->GetStringUTFChars(env, asset_path_str, NULL);
    context = whisper_init_from_asset(env, assetManager, asset_path_chars);
    (*env)->ReleaseStringUTFChars(env, asset_path_str, asset_path_chars);
    return (jlong) context;
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_initContext(
        JNIEnv *env, jobject thiz, jstring model_path_str) {
    UNUSED(thiz);
    struct whisper_context *context = NULL;
    const char *model_path_chars = (*env)->GetStringUTFChars(env, model_path_str, NULL);
    context = whisper_init_from_file_with_params(model_path_chars, whisper_context_default_params());
    (*env)->ReleaseStringUTFChars(env, model_path_str, model_path_chars);
    return (jlong) context;
}

JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_freeContext(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    whisper_free(context);
}

JNIEXPORT void JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_fullTranscribe(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint num_threads, jint audio_ctx,
        jstring initial_prompt, jfloatArray audio_data) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    jfloat *audio_data_arr = (*env)->GetFloatArrayElements(env, audio_data, NULL);
    const jsize audio_data_length = (*env)->GetArrayLength(env, audio_data);

    // The below adapted from the Objective-C iOS sample
    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_realtime = true;
    params.print_progress = false;
    params.print_timestamps = true;
    params.print_special = false;
    params.translate = false;
    // Português do Brasil, não inglês.
    //
    // Estava `"en"` sobre um modelo multilíngue recebendo áudio em português — o
    // Whisper transcrevia tentando casar fonemas de pt-BR com o vocabulário do
    // inglês, e o resultado é salada com aparência de transcrição. Num registro
    // operacional isso é pior que não transcrever: o texto existe, parece dado, e
    // não corresponde ao que foi dito.
    //
    // Fixo em vez de autodetecção: a detecção consome o primeiro segundo de áudio
    // decidindo o idioma, e a fala de rádio é curta demais para pagar isso — além
    // de a corporação ser brasileira, o que torna a dúvida desnecessária.
    params.language = "pt";
    params.n_threads = num_threads;

    // **A janela do encoder, encurtada para o tamanho da fala.**
    //
    // O Whisper processa SEMPRE 30 segundos: `whisper.cpp:3203` preenche o
    // restante com zeros ("pad 30 seconds of zeros at the end of audio"), e o
    // encoder roda sobre `n_audio_ctx = 1500` posições (`whisper.cpp:592`)
    // independentemente de a fala ter 2 s ou 30. Um comando de dois segundos
    // pagava a inferência inteira de trinta.
    //
    // `params.audio_ctx` sobrescreve isso; o máximo permitido é o do modelo e o
    // próprio whisper recusa valor maior (`whisper.cpp:6983-6987`). Zero = padrão.
    //
    // Medido no emulador antes desta mudança: 18 000 a 48 000 ms de STT para 2 s
    // de fala, contra uma meta de 2 000 ms para o ciclo TODO.
    //
    // Quem calcula o valor é o lado Kotlin, que sabe a duração — ver
    // `LibWhisper.transcribeData`. Aqui só se repassa, e zero preserva o
    // comportamento anterior para qualquer chamador que não queira opinar.
    if (audio_ctx > 0) {
        params.audio_ctx = audio_ctx;
    }
    params.offset_ms = 0;
    params.no_context = true;

    // **Enunciado único, e não é só desempenho.**
    //
    // Fala de rádio é uma frase curta, não um discurso. Deixar o whisper abrir
    // vários segmentos num comando de dois segundos gasta decodificação e abre a
    // porta para o laço de repetição — a alucinação clássica dele em áudio curto.
    params.single_segment = true;

    // **O léxico do domínio como viés, e o header confirma o campo.**
    //
    // `initial_prompt` (`whisper.h:527`) enfileira tokens no decoder e enviesa a
    // probabilidade de palavra. Não é lista de palavras obrigatórias — é prior.
    //
    // Por que isto importa AQUI: medido no aparelho, "guarnição" virou "nissan",
    // "agora nisso são" e "agora a inição". Quando as pistas espectrais de /s/ e
    // /ɐ̃w/ não chegam pelo HFP de 8 kHz, o modelo decide por probabilidade — e é
    // exatamente onde ter "guarnição" no prior pode ganhar de "nissan".
    //
    // **`no_context = true` NÃO anula isto.** Em `whisper.cpp:6937-6940` o
    // `prompt_past.clear()` roda ANTES do bloco 6961-6979 que empilha o prompt:
    // a ordem é limpa-depois-empilha. Verificado na fonte, não suposto — era a
    // dúvida óbvia e ela tem resposta.
    //
    // Curto de propósito: os tokens do prompt entram no KV cache e são
    // recomputados a cada iteração (há um `// TODO: do not recompute the prompt`
    // em `whisper.cpp:7123`). Uma dezena de palavras, as que o domínio exige.
    // **O prior do dominio, agora PARAMETRO e nao literal.**
    //
    // Enquanto era literal C, era impossivel medir quanto ele vale: o braco de
    // controle "com prompt contra sem prompt" nao existia, e o numero de WER do
    // projeto ficava sem como ser atribuido.
    //
    // E ha um defeito no proprio tokenizador que torna a medicao urgente: o regex
    // de whisper.cpp:3288 usa [[:alpha:]] sob locale "C", que NAO casa byte
    // multibyte. "guarnicao" com til e cedilha e partida nos acentos, e o prior
    // acaba enviesando bytes soltos que o decoder praticamente nunca emite.
    // Qual grafia funciona melhor — com acento, sem acento, ou prompt nenhum — e
    // pergunta empirica, e agora ela e respondivel.
    //
    // NULL ou string vazia = sem prompt. O whisper trata NULL como ausencia.
    const char *prompt_utf8 = NULL;
    if (initial_prompt != NULL) {
        prompt_utf8 = (*env)->GetStringUTFChars(env, initial_prompt, NULL);
        if (prompt_utf8 != NULL && prompt_utf8[0] != '\0') {
            params.initial_prompt = prompt_utf8;
        }
    }

    params.suppress_nst = true;

    whisper_reset_timings(context);

    LOGI("About to run whisper_full");
    if (whisper_full(context, params, audio_data_arr, audio_data_length) != 0) {
        LOGI("Failed to run the model");
    } else {
        whisper_print_timings(context);
    }
    if (prompt_utf8 != NULL) {
        (*env)->ReleaseStringUTFChars(env, initial_prompt, prompt_utf8);
    }
    (*env)->ReleaseFloatArrayElements(env, audio_data, audio_data_arr, JNI_ABORT);
}

JNIEXPORT jint JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTextSegmentCount(
        JNIEnv *env, jobject thiz, jlong context_ptr) {
    UNUSED(env);
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return whisper_full_n_segments(context);
}

/**
 * Probabilidade de que o segmento NAO seja fala.
 *
 * **Existia no artefato desde sempre e nunca foi ligada.** O whisper.cpp calcula
 * `state->no_speech_prob` e a usa internamente para decidir se emite o segmento
 * (whisper.cpp:7622-7640); `whisper_full_get_segment_no_speech_prob` (whisper.h:766)
 * a expoe. Faltava binding — e sem ele `Transcript.confidence` era sempre `null`,
 * o que fez a spec do gatilho registrar como risco aceito que "nao ha limiar de
 * confianca a ajustar". A afirmacao era falsa sobre o artefato.
 *
 * Para o portao da palavra de ativacao isto vale mais que qualquer ajuste de
 * decodificacao: RECUSAR por baixa confianca e recusa honesta. O criterio de hoje
 * e casamento de string sobre um texto que pode ter sido alucinado.
 */
JNIEXPORT jfloat JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getSegmentNoSpeechProb(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(thiz);
    UNUSED(env);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return whisper_full_get_segment_no_speech_prob(context, index);
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTextSegment(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    const char *text = whisper_full_get_segment_text(context, index);
    jstring string = (*env)->NewStringUTF(env, text);
    return string;
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTextSegmentT0(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return whisper_full_get_segment_t0(context, index);
}

JNIEXPORT jlong JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getTextSegmentT1(
        JNIEnv *env, jobject thiz, jlong context_ptr, jint index) {
    UNUSED(thiz);
    struct whisper_context *context = (struct whisper_context *) context_ptr;
    return whisper_full_get_segment_t1(context, index);
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_getSystemInfo(
        JNIEnv *env, jobject thiz
) {
    UNUSED(thiz);
    const char *sysinfo = whisper_print_system_info();
    jstring string = (*env)->NewStringUTF(env, sysinfo);
    return string;
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_benchMemcpy(JNIEnv *env, jobject thiz,
                                                                      jint n_threads) {
    UNUSED(thiz);
    const char *bench_ggml_memcpy = whisper_bench_memcpy_str(n_threads);
    jstring string = (*env)->NewStringUTF(env, bench_ggml_memcpy);
    return string;
}

JNIEXPORT jstring JNICALL
Java_com_whispercpp_whisper_WhisperLib_00024Companion_benchGgmlMulMat(JNIEnv *env, jobject thiz,
                                                                          jint n_threads) {
    UNUSED(thiz);
    const char *bench_ggml_mul_mat = whisper_bench_ggml_mul_mat_str(n_threads);
    jstring string = (*env)->NewStringUTF(env, bench_ggml_mul_mat);
    return string;
}
