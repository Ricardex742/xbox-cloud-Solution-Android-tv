package com.world.cloudxsolution;

/** Main-thread startup state; ready is also read by the JavaScript bridge thread.
 * A timed-out/closed attempt must never open a catalog from a late native callback.
 */
final class StartupGate {
    enum Stage { IDLE, BINDING, INITIALIZING, SURFACE, READY, FAILED, CLOSED }
    private volatile Stage stage = Stage.IDLE;
    private int token;

    int begin() {
        if (stage == Stage.CLOSED) throw new IllegalStateException("Activity closed");
        stage = Stage.BINDING;
        return ++token;
    }
    int token() { return token; }
    Stage stage() { return stage; }
    boolean ready() { return stage == Stage.READY; }
    boolean accepts(int candidate) {
        return token == candidate && (stage == Stage.BINDING
                || stage == Stage.INITIALIZING || stage == Stage.SURFACE);
    }
    boolean advance(int candidate, Stage next) {
        if (!accepts(candidate) || next.ordinal() != stage.ordinal() + 1) return false;
        stage = next;
        return true;
    }
    void fail() { if (stage != Stage.CLOSED) stage = Stage.FAILED; }
    void close() { stage = Stage.CLOSED; ++token; }
    String stageLabel() {
        switch (stage) {
            case BINDING: return "conexão com o serviço de vídeo";
            case INITIALIZING: return "preparação de áudio e vídeo";
            case SURFACE: return "preparação da saída de vídeo";
            case READY: return "serviço de vídeo pronto";
            case FAILED: return "a inicialização não foi concluída";
            case CLOSED: return "aplicativo encerrado";
            default: return "aguardando inicialização";
        }
    }
}
