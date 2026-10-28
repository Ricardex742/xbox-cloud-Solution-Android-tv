# TCL: controle na entrada tardia, High e baixa latência

## Evidências e limites

O usuário informou que High estava disponível e ofertado, mas não chegou a
registrar a linha de perfil negociado. Portanto, a ausência de High na oferta
não explica esse teste. O codec efetivamente usado precisa ser lido em RTCStats;
uma preferência aceita localmente não obriga o servidor a usá-la.

A conversa anexada contém exatamente o workflow do commit
`6d316e913b41166adb6bb5fd88b487a2a5adf8fa`. A execução correspondente,
[40208203955](https://github.com/Ricardex742/xbox-cloud-Solution-Android-tv/actions/runs/40208203955),
começou em **25/10/2026 às 12:42:22 BRT**, após a conversa das 12:36.
Isso identifica o código de referência; não constitui uma medição independente
dos 60 FPS relatados no EA Sports 26.

## Plano aplicado

1. Solicitar processamento mínimo da tela durante a sessão pela API pública do
   Android 11+, restaurando a preferência ao sair. Exibir suporte reportado pela
   tela separadamente do pedido. O app já está classificado como jogo no manifesto.
2. Oferecer High `64001f` e Constrained High `640c1f` quando o decodificador Android
   declara AVCProfileHigh. Hardware que declara somente Constrained High continua
   oferecendo apenas esse perfil. Preferir High comum quando o usuário escolhe
   High; preservar Baseline e outros codecs como alternativas de negociação.
3. Anunciar o controle virtual nativo no canal control sem aguardar teclas ou
   movimentos. Reafirmar o anúncio no HandshakeAck e no primeiro quadro. Enviar
   estado neutro ao ativar um caminho de input e selecionar somente canais abertos.
   Não enviar movimentos simultaneamente por dois protocolos.
4. Manter viva a WebView da sessão, liberar mensagens de sessão e reliableinput,
   encaminhar respostas binárias e impedir que o navegador remova o controle
   virtual gerenciado pelo app. Corrigir eventos duplicados e views binárias com
   offset. Devolver o foco ao catálogo.
5. Reutilizar as correções de keepalive e HTTP da branch TL042: geração de sessão,
   cancelamento, deduplicação, espera limitada por metadados, tratamento de HTTP
   não exitoso e TLS padrão. Sem importar o preset 720p/8 Mbps da Multilaser.
6. Mostrar perfis H.264 da oferta/resposta sem expor SDP completo, FPS de quadros
   recebidos, FPS decodificados e média de tempo de decodificação por intervalo.
   Manter o bitrate e a resolução selecionados pelo usuário.

## Jogo Mestre

[Window.setPreferMinimalPostProcessing](https://developer.android.com/reference/android/view/Window#setPreferMinimalPostProcessing(boolean))
solicita processamento mínimo quando a janela está visível. Em painel integrado,
o efeito depende do fabricante. A opção do sistema pode desabilitar esse recurso.
[Display.isMinimalPostProcessingSupported](https://developer.android.com/reference/android/view/Display#isMinimalPostProcessingSupported())
informa a capacidade, não comprova a ativação do menu proprietário Jogo Mestre.
Não há confirmação nesta auditoria de uma API pública TCL que force esse menu em
aplicativos internos. Não são usados root, ADB ou comandos privados do fabricante.

## High

A fábrica anterior anunciava apenas Constrained High, embora a seleção se
chamasse High. São perfis distintos na negociação SDP. A nova oferta vem das
capacidades reais do decodificador, antes de createOffer/setLocalDescription;
nenhum payload Baseline é renomeado para High. O mesmo decodificador inspecionado
continua sendo usado. A preferência não exclui Baseline e não garante que o
servidor escolha High. O efeito desta ampliação ainda exige teste na TV.

## Comparação com a referência de 60 FPS

O workflow de referência adicionava `max-fr=60` aos fmtp pelo JavaScript, alterava
perfis High/Main para Baseline e acrescentava mínimo de 4 Mbps e partida de até
12 Mbps. Também mantinha a WebView viva e liberava reliableinput/message. A nova
implementação recupera essas últimas propriedades sem restaurar mutações de SDP
depois da oferta nativa, piso de bitrate ou envio duplicado de controle.

`max-fr=60` é um limite, não uma ordem para o servidor renderizar 60 quadros.
Não foi encontrado um limitador local explícito de 30 FPS nesta branch. Isso não
exclui gargalos no decodificador, rede ou jogo. O tempo de decodificação mostrado
é uma média por quadro no intervalo, não a latência total da sessão.

## Validação

Testes JVM cobrem disponibilidade e troca dos canais sem eventos físicos,
reinício de sessão, perfis de hardware, extração dos perfis SDP e medidas por
intervalo. Testes Node executam o proxy real em um navegador simulado: mensagens
de sessão e reliableinput, exclusividade do input nativo, anúncio do navegador,
offset binário, listeners e keepalive limitado. CI executa ambas as suítes,
lint, build e assinatura/verificação dos três APKs ARM.

Na TV:

1. Abrir uma sessão e aguardar **60 segundos sem tocar no controle**. Testar A,
   direcional, analógicos e gatilhos. Repetir entrando e saindo de três jogos.
2. Selecionar High, encerrar e reabrir a sessão. Registrar “Perfis na oferta”,
   “Perfis na resposta”, “Perfil negociado” e o código na linha Codec. Os dois
   primeiros mostram possibilidades; a linha Codec indica o fluxo usado.
3. Conferir “Baixa latência da tela: solicitada” e suporte. O texto não confirma
   Jogo Mestre. Verificar o menu da própria TCL separadamente.
4. No EA Sports 26, comparar a mesma partida/câmera e uma cena semelhante por ao
   menos dois minutos, anotando resolução, bitrate, FPS recebido/decodificado,
   Render fps, descartes e tempo de decodificação. Repetir num jogo que oscilava.
5. Se High congelar ou piorar a fluidez, retornar a Baseline e reabrir a sessão.

Manter a WebView custa memória adicional; é necessário para a continuidade do
protocolo. Testes locais e CI não substituem a validação de hardware ou do xCloud.
Nenhum resultado desta mudança permite prometer 60 FPS em todos os jogos.
