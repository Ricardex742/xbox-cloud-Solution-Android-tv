package com.world.cloudxsolution;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.os.Build;

/** Reads only this attempt's :stream exit metadata; never logs traces, URLs or tokens. */
final class StartupDiagnostics {
    static String read(Context context, long attemptStartedAt) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return "Este Android não fornece histórico de encerramento do processo.";
        }
        try {
            ActivityManager manager = context.getSystemService(ActivityManager.class);
            if (manager == null) return "Histórico de encerramento indisponível.";
            String process = context.getPackageName() + ":stream";
            for (ApplicationExitInfo exit : manager.getHistoricalProcessExitReasons(context.getPackageName(), 0, 16)) {
                String report = describeExit(process, attemptStartedAt, exit.getProcessName(),
                        exit.getTimestamp(), exit.getReason(), exit.getStatus());
                if (report != null) return report;
            }
            return "O Android não registrou encerramento do processo de vídeo nesta tentativa. A causa ainda não está identificada.";
        } catch (RuntimeException e) {
            return "O Android não permitiu consultar o histórico de encerramento.";
        }
    }

    static String describeExit(String expectedProcess, long since, String process,
                               long timestamp, int reason, int status) {
        if (!expectedProcess.equals(process) || timestamp < since) return null;
        String cause;
        switch (reason) {
            case ApplicationExitInfo.REASON_CRASH: cause = "falha Java"; break;
            case ApplicationExitInfo.REASON_CRASH_NATIVE: cause = "falha nativa"; break;
            case ApplicationExitInfo.REASON_ANR: cause = "processo sem resposta (ANR)"; break;
            case ApplicationExitInfo.REASON_LOW_MEMORY: cause = "falta de memória"; break;
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: cause = "falha na inicialização do processo"; break;
            case ApplicationExitInfo.REASON_USER_REQUESTED: cause = "encerramento solicitado pelo usuário ou sistema"; break;
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE: cause = "alteração de permissões"; break;
            default: cause = "motivo " + reason;
        }
        return "O Android registrou encerramento do processo de vídeo: " + cause + " (status " + status + ").";
    }
}
