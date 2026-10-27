# Multilaser TL042: compatibilidade e início da sessão

Data: 27/10/2026. Base: ff69b8f, da branch fix/tv-video-quality. Esta implementação fica em fix/multilaser-tl042-session; main e a branch da TCL não recebem estas mudanças.

## Evidência disponível

O usuário informou Android 11, Wi-Fi de 5 GHz e falha em todos os jogos: carregamento prolongado seguido de “desconectado da sessão de jogos da nuvem”. Não temos log dessa TV, especificação confirmada de RAM/SoC, ABI, versão do Android System WebView ou medição da rede. Android 11 atende ao minSdk 29 do aplicativo. O nome do modelo não é usado para inventar capacidades de hardware.

Os defeitos abaixo existem no código, mas ainda não demonstramos qual deles causou o erro dessa TV. A compilação e os testes de protocolo não substituem uma sessão real no xCloud.

## Brainstorm e escolhas

| Opção | Por que considerar | Decisão |
|---|---|---|
| 720p, teto 8 Mbps, preferência Baseline | Reduz resolução decodificada e tráfego frente a 1080HQ/20 Mbps | Preset inicial; disponível em qualquer instalação pelo botão TL042 |
| Preservar a WebView da sessão | Mantém mensagens, negociação e tratamento de eventos após o handshake | Implementado; vídeo continua nativo e canvas auxiliar permanece 2x2/1 fps |
| Destruir a WebView para liberar RAM | Economiza RAM, mas também remove a lógica ativa da sessão | Removido do sucesso do keepalive; recursos são liberados ao encerrar |
| Eventos entregues uma vez e mensagens de sessão completas | Elimina respostas duplicadas e mensagens descartadas durante entrada no jogo | Implementado, mantendo o input de gameplay no pipeline nativo |
| Atualizar configuração ICE sem destruir a conexão | Preserva transceivers e canais criados | Implementado; roteamento fica com Android/WebRTC, incluindo mudanças de Wi-Fi |
| Permitir candidatos TCP | Pode dar uma alternativa quando a rede não permite um caminho UDP adequado | Habilitado como candidato; não força TCP nem promete que o servidor ofereça essa rota |
| Tempo limite para operações SDP | Interrompe promessas sem resposta nativa | 30 segundos por operação, sem limitar fila/boot do jogo no servidor |
| Forçar software, 1080HQ, 20+ Mbps, High ou filtros de nitidez | Pode elevar carga/latência em hardware ainda não medido | Não ativado no preset TL042 |
| Forçar 30 FPS | Só descartar quadros localmente não reduz necessariamente a carga do decoder/rede | Não implementado sem suporte negociado e validação |

## Correções aplicadas

1. O primeiro keepalive bem-sucedido destruía a WebView. Agora ele mantém a página ativa. O heartbeat é deduplicado, cancelado ao sair/recarregar/destruir a Activity e protegido contra respostas HTTP atrasadas que tentariam reagendar uma sessão encerrada. A espera por metadados no JS é limitada e tolera dados ainda indisponíveis.
2. O canal `message` descartava todo texto que não contivesse Handshake. Mensagens normais de sessão agora são encaminhadas. `input`, `unreliableinput` e `reliableinput` continuam reservados ao pipeline nativo. Não foram alteradas deadzones nem correções de gatilhos.
3. `onmessage` era chamado por dispatchEvent e novamente pelo encaminhador. O proxy também registrava listeners simultaneamente na lista e na propriedade on*. A entrega duplicada foi removida; open/close e connectionstatechange são encaminhados; views binárias preservam offset e comprimento.
4. A resposta nativa podia chegar antes de createAnswer registrar o resolver. Ela agora fica disponível até ser consumida. setRemoteDescription só resolve após confirmação do nativo. Erros SDP atravessam AIDL, e operações sem retorno rejeitam a promessa após 30 segundos.
5. O proxy WebRTC é instalado na injeção do script, sem esperar load+300 ms. O catálogo só abre depois da inicialização do serviço nativo, evitando chamadas descartadas na partida mais lenta.
6. `setConfiguration` recriava PeerConnection e perdia canais/transceivers. Agora atualiza a configuração existente. A fábrica nativa é reutilizada dentro do serviço. Eventos nativos de conexões encerradas são filtrados por geração/identidade de canal.
7. Removida a fixação manual do processo a uma rede, cujo callback só acompanhava Ethernet. Android/WebRTC passam a acompanhar a rota disponível; coleta contínua de candidatos e candidatos TCP ficam habilitados. Recuperação após mudança de rede ainda depende do servidor e deve ser testada.
8. Se a sessão anterior liberar a saída EGL, a criação da próxima solicita novamente a Surface. As trilhas e AudioContexts auxiliares do navegador são encerrados ao fechar a sessão. Se o processo da WebView terminar, o aplicativo oferece reabrir o catálogo, sem uma sequência automática de tentativas.
9. Erros HTTP do keepalive eram classificados como sucesso. Só 2xx contam como sucesso; 401/403/404/410 encerram a renovação auxiliar e mostram aviso. Falhas transitórias tentam novamente depois de 15 segundos, com uma requisição por vez. O cliente HTTP usa a validação TLS padrão do Android, em vez de aceitar qualquer certificado. Tokens de sessão não são incluídos no log de captura.
10. O preset usa 720p, teto de 8 Mbps e modo Estável. O dispositivo anunciado no pedido 720p também passa a ter dimensões 1280x720. Quando Build.MODEL identifica TL042, apenas preferências ainda ausentes recebem esse padrão; escolhas já salvas são preservadas. Há botão explícito para instalações existentes ou modelos que se identificam com nome genérico.

## Desempenho e limites

720p tem aproximadamente 44% dos pixels de 1080p; isso não significa uma redução proporcional garantida de CPU/RAM. Manter a WebView viva custa mais memória do que destruí-la. Os testes na TV precisam verificar tanto estabilidade de sessão quanto pressão de memória. Não foram adicionados filtros de vídeo nem cópia de frames para JavaScript.

Não foi prometido 60 FPS constante ou atraso zero. TCP, se negociado, pode aumentar latência. Um bitrate máximo não garante a taxa efetivamente transmitida. Não há fallback automático para High nem reinício automático da sessão em caso de erro.

## Roteiro de instalação e validação

1. Baixar o artefato CloudX-AndroidTV-ARM da execução desta branch. Se a ABI da TV não for conhecida, começar pelo APK Universal ARM; não inferir 32/64 bits apenas pelo modelo. O APK usa o mesmo applicationId e os mesmos secrets de assinatura do projeto.
2. Na TV, abrir Configurações do aplicativo → Usar perfil Multilaser TL042 (720p / 8 Mbps) → Aplicar. Encerrar a sessão antiga e iniciar outra. Instalações existentes não têm suas preferências substituídas silenciosamente.
3. Registrar versão do app/commit, Android System WebView e horário. Manter data/hora automáticas; atualizar a WebView pela loja da TV quando houver atualização disponível. Não desabilitar validação de certificados.
4. Testar três jogos diferentes, em três sessões consecutivas, incluindo o retorno ao catálogo. Registrar se a falha acontece antes de surgir vídeo, depois do primeiro quadro ou após aproximadamente um minuto.
5. No painel, confirmar Vídeo recebido e Superfície em 1280x720, codec/decoder negociados, FPS e perdas. O primeiro intervalo de bitrate aparece como medindo; aguardar a próxima atualização.
6. Permanecer ao menos 20 minutos em uma sessão. Verificar ausência de desconexão após o primeiro keepalive, queda de frames, aquecimento, áudio sem vídeo e encerramento do processo do navegador.
7. Testar uma interrupção breve do Wi-Fi e o retorno. Se a sessão não recuperar, registrar a etapa/erro e iniciar outra; não assumir que todo servidor suporta recuperação ICE contínua.
8. Conferir rapidamente gatilhos, repouso do analógico esquerdo e resposta da câmera direita. Os arquivos dessas correções permanecem iguais aos da base.
9. Só após estabilizar o preset comparar 720p/10 Mbps. 1080p é um experimento posterior, sem mudar bitrate, codec e resolução simultaneamente.

Para diagnóstico por ADB, quando disponível: `adb logcat -s XboxFramesMain WebRtcReceiver StreamingService WebRtcBridge RTC-Codec`. Remover tokens/URLs de sessão antes de compartilhar logs. O diagnóstico precisa do erro e do momento, não das credenciais.

## Verificação automatizada

`node --test tests/session-bridge.test.cjs` exercita o código real do proxy com navegador e bridge simulados: mensagens pós-handshake, duplicação, open, conexão, confirmação remota, resposta antecipada, falhas e timeout, encerramento de recursos, keepalive, reconfiguração, bytes e dimensões 720p. Não simula o servidor nem MediaCodec.

`./gradlew --no-daemon --no-configuration-cache --max-workers=2 testDebugUnitTest lintDebug assembleDebug assembleRelease` verifica Java/AIDL/recursos, regressões anteriores e identificação do modelo. O workflow desta branch também executa os testes Node antes de assinar/publicar APKs.

## Resultado local desta implementação

- 15 testes Java e 14 testes Node aprovados, zero falhas.
- testDebugUnitTest, lintDebug, assembleDebug e assembleRelease concluídos com JDK17/Gradle8.13.
- Lint: zero erros e 93 avisos; sem nova supressão/baseline.
- JavaScript passou na checagem de sintaxe; git diff --check aprovado.
- Os arquivos de gatilhos, deadzones e pacotes de controle permanecem iguais à base ff69b8f.
- Sessões reais, RAM, Wi-Fi, decodificador e estabilidade na TL042 ainda precisam da validação física descrita acima.
