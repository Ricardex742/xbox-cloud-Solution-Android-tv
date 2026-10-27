# Plano de correção CloudX

Base auditada: 5757b61519839a817c7aa0ede64cd2056a8a48f9.
Objetivo: controles consistentes, negociação de vídeo coerente, build reproduzível e APKs ARM verificáveis. Fortnite permanece em espera.

## Etapas e critérios

1. Consolidar nas fontes as customizações úteis do antigo workflow. Eliminar patches textuais silenciosos; nenhuma mutação de fonte durante CI.
2. Unificar cálculo radial dos analógicos: esquerda com mínimo 0,15, direita sem deadzone configurável; sanitizar NaN/limites. Consumir eventos secundários L2/R2/Z/C preservando gatilhos digitais de controles sem eixo analógico.
3. Corrigir estado: limpar teclas/eixos na troca de dispositivo, troca de transporte e entrada/saída do teste. Validar índices antes de acessar arrays; não deixar controle remoto reconfigurar gamepad. No legado, não apagar D-pad digital a cada MotionEvent sem hat axes.
4. Serializar pequenos movimentos e neutro; alinhar physicality com quantização do protocolo. Repetir a transição de eixos ativos para neutro sem criar fila de snapshots antigos. Isso aumenta redundância, sem garantir entrega UDP nem latência zero.
5. Vídeo: priorizar Baseline nos transceivers antes da oferta, preservar perfil/nível anunciados. Aplicar limite de bitrate no SDP nativo antes de setLocalDescription e devolver exatamente essa oferta ao JS. Transmitir bitrate por AIDL para evitar preferências desatualizadas no processo :stream. Não adicionar atributos de vídeo a áudio/RTX.
6. Defaults e interface: Brasil/1080HQ/20 Mbps antes de executar o script, preservando escolhas explícitas do usuário; pt-BR nos menus; filtro de toast antes de enfileirar na UI. Corrigir recursos XML inválidos e atributo API31 em pasta qualificada.
7. CI: Java17/Gradle8.13/SDK34/Build Tools35, wrapper com checksum; testes, lint e builds debug/release explícitos; release unsigned seguida de assinatura persistente; inspeção ABI/assinatura/alinhamento obrigatória. Falhas impedem upload.
8. Validar em host e preparar teste em TV. FPS, codec efetivo, atraso físico e comportamento por jogo precisam do dispositivo.

## Problemas adicionais encontrados

- Código de tecla acessado antes de verificar limites no listener moderno.
- Modo de teste permanecia ativo ao fechar o diálogo, bloqueando input posterior.
- Estado anterior podia permanecer ao trocar dispositivo; legado recusava novos IDs.
- Legado aplicava deadzone axial, divergindo do cálculo radial moderno e da prévia.
- Movimento sem hat axes apagava D-pad recebido digitalmente no legado.
- Prévia visual usava deadzone compartilhada, diferente do pipeline efetivo.
- Input de controle remoto podia reconfigurar o mapeamento do gamepad.
- Configuração do JS chegava após execução do script; risco de usar fallback errado.
- Preferências do processo de streaming não devem ser lidas implicitamente como se fossem atuais no processo da interface.
- Retorno de DataChannel.send era ignorado: agora recusas são registradas.

## Assinatura no GitHub

O repositório deve ter os secrets:

- CLOUDX_KEYSTORE_BASE64: conteúdo base64 da keystore persistente.
- CLOUDX_KEY_ALIAS.
- CLOUDX_STORE_PASSWORD.
- CLOUDX_KEY_PASSWORD.

Inserir valores nas configurações seguras do GitHub, nunca em commits ou chat. O workflow falha explicitamente se faltarem. Nenhum valor foi criado no GitHub nesta tarefa. Builds anteriores usavam chaves aleatórias; se a chave da instalação atual não foi preservada, a nova assinatura não atualiza essa instalação diretamente. Não desinstalar sem considerar dados locais.

PRs executam build/test/lint sem secrets. Pushes executam assinatura e publicação do artefato após validação. Não há fallback silencioso de release para debug.

## Validação local

```sh
source /workspace/.cloudx-tools/env.sh
./gradlew --no-daemon --no-configuration-cache --max-workers=2 testDebugUnitTest lintDebug assembleDebug assembleRelease
node --check app/src/main/assets/index.js
```

Os testes cobrem resposta radial, drift, entrada não finita, micromovimento, serialização de neutro, contadores de botões e SDP idempotente preservando áudio, RTX e profiles. Não substituem eventos Android reais nem testes na TV.

Para empacotar release, definir as variáveis CLOUDX_* documentadas e executar `bash scripts/package-apks.sh`. O script exige APK unsigned, valida arquitetura durante filtragem e verifica assinatura/alinhamento. Os APKs ficam em dist_apks com SHA256SUMS; o CI inclui COMMIT.

## Roteiro TCL 55P755 / GameSir

1. Registrar commit, certificado, ABI suportada pela TV, modo USB/Bluetooth, bitrate, resolução e região. Não inferir ABI do nome comercial da TV.
2. Forza: acionar RT/LT isolados e simultâneos 30 vezes, mantendo Start real funcional. Verificar que C/Z não acionam menu e que gatilhos digitais também funcionam em controle sem eixos analógicos.
3. Gears: mover/soltar esquerdo em oito direções; confirmar repouso por 30 segundos. Repetir em ambos transportes e após reconexão do controle.
4. Câmera: micro-movimentos, diagonais e retorno ao centro; verificar drift físico com deadzone direita zero. A ausência de deadzone pode expor ruído do hardware.
5. Abrir teste, pressionar botões, fechar por Cancelar, Aplicar e Back; confirmar que jogo retoma sem botões presos. Trocar moderno/legado.
6. Vídeo: 20 minutos em cenas complexas, registrar offer/answer, codec/profile efetivo, frames decoded/dropped, congelamentos e áudio. Comparar 15/20 Mbps sem alterar múltiplas opções ao mesmo tempo.
7. Desconectar rede/controle e recuperar sessão; conferir logs de envio recusado e estado neutro. A redundância de pacotes não comprova recuperação após perda.
8. Validar atualização APK com a mesma chave. Testar instalação em dispositivo limpo separadamente de preferências antigas.

## Limites

A assinatura persistente requer configuração externa. Não foram feitos push, execução remota do workflow ou testes físicos nesta tarefa. Os avisos de lint precisam ser avaliados por impacto; mudanças de SDK/dependências e supressão global de avisos não são usadas para mascarar resultados. Não prometer estabilidade de 60 FPS nem “zero delay” apenas pela compilação.

## Resultado desta implementação

- 7 testes unitários aprovados (6 de regressão e o exemplo original).
- Builds debug e release aprovadas com wrapper Gradle 8.13/JDK17.
- Lint: zero erros, 87 avisos ainda registrados, sem baseline de supressão.
- JavaScript passou na checagem de sintaxe.
- Empacotamento interrompeu com erro quando faltaram configurações de assinatura e quando o assinador simulado retornou 42.
- Três APKs ARM locais passaram em apksigner verify e zipalign; assinados com a chave de desenvolvimento deste ambiente, não com uma chave de distribuição do GitHub.
- Versão local 1.1.2; no CI versionCode segue GITHUB_RUN_NUMBER + 1. COMMIT e SHA256SUMS identificam artefatos.
- O mapa de DataChannels agora aceita acesso concorrente; envio é sincronizado com fechamento e avisos de recusa são limitados a um por segundo.
- Testes na TV e execução remota do workflow não foram realizados. A validação de handlers com eventos reais e a medição de latência continuam necessárias.
