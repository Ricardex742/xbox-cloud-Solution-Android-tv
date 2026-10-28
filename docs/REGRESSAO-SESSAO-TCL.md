# Correção após o teste de 28/10/2026

O vídeo enviado pelo usuário mostra a build anterior em 1920x1080 com H.264
Baseline, apesar de High disponível e ofertado. A resposta contém `42e02a`;
RTCStats exibe `42e01f`. Os trechos legíveis mostram aproximadamente 39–40 FPS,
0,6–0,8 Mbps, média de decodificação de 33 ms/quadro, descartes de decodificação
e perdas de pacotes. O renderizador informa zero descartes, mas isso não cobre
as perdas anteriores à renderização. O vídeo termina com tela escura e menu
do app sem uma sessão ativa. O controle sem resposta é relatado pelo usuário.

Não é possível atribuir os travamentos ao uso de High: esse perfil não foi
selecionado nessa sessão. Também não é possível inferir a capacidade máxima da
rede pelo bitrate baixo de uma tela de título. As correções abaixo tratam
defeitos identificados no código; o desempenho final exige novo teste na TCL.

## Falhas corrigidas

- **Saída:** a build anterior fechava WebRTC e mostrava novamente a mesma WebView
  com a página do jogo. Agora a saída desativa a ponte da página, fecha a sessão,
  esconde a superfície, destrói a página do jogo e carrega um catálogo novo.
  Não restaura o histórico da sessão encerrada e não apaga os cookies do login.
- **Configuração:** `setConfiguration` chamava o caminho de criação, descartando
  a conexão e zerando a rota de input. Agora usa `PeerConnection.setConfiguration`
  na conexão existente, sem recriar transceptores nem canais.
- **Canais:** a UI recebe um retrato dos canais nativos, inclusive no primeiro
  quadro. Assim a seleção de input não depende exclusivamente de um evento OPEN
  observado no momento exato. O retrato não redispara onopen no JavaScript.
- **Registro do controle:** o anúncio é reafirmado depois do envio de
  `authorizationRequest`, no mesmo canal ordenado. Os anúncios anteriores podiam
  ocorrer antes da autorização do protocolo. Não é uma confirmação independente
  de que o servidor aceitou o controle; a validação física ainda é necessária.
  Após o registro, o estado atual é reenviado mesmo sem eventos físicos, sem
  liberar botões pressionados ou modificar os contadores de transição.
- **Instalação da ponte:** o proxy é instalado imediatamente, de forma idempotente.
  WebViews compatíveis usam `DOCUMENT_START_SCRIPT`, antes dos scripts do site.
  Versões sem esse recurso usam injeção no primeiro progresso disponível. Remove
  a espera por load + 300 ms, que permitia a criação precoce de uma conexão real
  no navegador. Isso elimina uma corrida; o vídeo não prova que houve duas
  decodificações simultâneas no dispositivo.
- **Encerramento:** conexões antigas não podem enviar ou fechar a seguinte;
  callbacks SDP nativos atrasados são vinculados à sua conexão. Tracks auxiliares
  e contextos de áudio são encerrados. A ponte de uma página destruída ignora
  chamadas enfileiradas. A superfície é solicitada de novo para a próxima sessão.
- **Visibilidade:** terminar o carregamento da página não torna a WebView visível
  por cima da sessão nativa já ativa. O catálogo espera o serviço ficar pronto.
- **Baixa latência:** a ativação automática da build anterior foi retirada.
  Agora há uma opção experimental, desligada por padrão. Isso reduz uma variável
  do teste; não estabelece que o modo da TV causou a regressão.
- **Diagnóstico:** o painel mostra canal selecionado, eventos tratados e pacotes
  aceitos/recusados localmente pelo DataChannel. Envio aceito não significa
  confirmação de recebimento ou execução pelo servidor. Nenhuma tecla ou valor
  de eixo é registrado no painel.

## Verificação

Validação local: 28 testes Java e 11 testes JavaScript aprovados; Debug e Release
compilados; lint sem erros, com 95 avisos.

Os testes Java cobrem a recuperação de OPEN perdido por retratos dos canais e
frames neutros enviados após registro, sem eventos físicos.
Os testes Node agora executam o arquivo JavaScript completo, inclusive sua
instalação: injeção antes de load e sem duplicação, atualização de configuração,
autorização antes do registro, fechamento repetido, liberação de tracks/áudio,
isolamento da sessão seguinte e eventos OPEN repetidos. Permanecem os testes de
protocolos de input, mensagens, offsets binários e keepalive.

O teste na TV continua indispensável:

1. Deixar a nova opção de baixa latência desligada. Para uma comparação simples,
   selecionar Baseline e manter a mesma resolução/bitrate do teste anterior.
2. Entrar num jogo, esperar 60 segundos sem tocar no controle e então usar A e
   os analógicos. Se falhar, registrar as linhas “Controle”, “Eventos tratados”,
   “Controle enviado” e “Recusado localmente”.
3. Sair pelo menu: deve aparecer o catálogo, não a página preta do jogo. Entrar
   novamente e repetir o teste. Confirmar também que o áudio anterior terminou.
4. Comparar FPS recebido/decodificado, média de decodificação, descartes e perdas
   na mesma cena. Não concluir que travamentos acabaram apenas pelo build verde.

Estas mudanças substituem a ativação automática de baixa latência descrita no
documento anterior. Não alteram a main nem a branch da Multilaser.
