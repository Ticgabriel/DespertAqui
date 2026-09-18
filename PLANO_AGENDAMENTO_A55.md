# DespertAqui — plano para iniciar agendamentos com o app fechado

## Objetivo e limites

No Samsung Galaxy A55, o usuário agenda uma janela futura (exemplo: 17h), usa **Fechar tudo** na lista de aplicativos recentes e o acompanhamento não inicia no horário. O objetivo é iniciar o serviço de localização e sua notificação sem precisar reabrir a interface.

Esta entrega é uma investigação e um plano. **Não foi implementada uma correção do agendamento.** A versão do Android/One UI e os registros de um disparo no aparelho ainda precisam ser coletados. Fechar tudo foi confirmado pelo usuário; não se trata de Forçar parada nos ajustes.

## Base verificada

- Repositório: [Ticgabriel/DespertAqui](https://github.com/Ticgabriel/DespertAqui).
- `main` verificada: `620a0344524f224ff10a629790a22dfd15adcaeb`.
- Essa atualização de outro agente corrige confirmação de chegada no raio do destino em `MonitoringEngineImpl` e `MonitoringCoordinatorImpl`. Preservá-la.
- Há também a [PR #1](https://github.com/Ticgabriel/DespertAqui/pull/1), com navegação e aviso de permissões. Conferir seu estado antes de iniciar; não sobrescrever nem refazer essas mudanças.
- O diagnóstico abaixo considera os caminhos de agendamento, que não foram modificados pelo commit de correção da chegada.
- Não houve reprodução física no A55. Build/testes de interface e lógica não demonstram entrega de alarmes pelo Android com o processo encerrado.

## O que o código já faz

Fluxo atual:

`Salvar → Room → reconcileSchedules → AlarmManager → ScheduleReceiver → reconcileSchedules → StartOccurrence → MonitoringService → startForeground → localização`

O projeto já registra um `PendingIntent` de broadcast com `RTC_WAKEUP`; o agendamento não é apenas um temporizador dentro da tela. Ao abrir o app, `MainActivity.onResume()` também executa a reconciliação. Isso fornece um caminho de recuperação ao reabrir, mas não substitui o disparo com o app fechado.

## Achados e hipóteses

### 1. Agendamento salvo não significa início automático autorizado — confirmado

[AlarmEditorViewModel.saveOrStart](https://github.com/Ticgabriel/DespertAqui/blob/620a0344524f224ff10a629790a22dfd15adcaeb/app/src/main/java/com/destino/app/feature/alarms/AlarmEditorViewModel.kt#L534) salva a programação e chama a reconciliação sem exigir os requisitos de execução automática.

[SystemScheduler, linha 134](https://github.com/Ticgabriel/DespertAqui/blob/620a0344524f224ff10a629790a22dfd15adcaeb/app/src/main/java/com/destino/app/platform/schedule/SystemScheduler.kt#L134) recusa o início automático quando falta localização em segundo plano e grava `AWAITING_PREREQUISITES`. Isso é necessário para o fluxo em segundo plano, mas hoje o usuário pode considerar o alarme pronto antes de autorizar.

**Hipótese prioritária:** “Localização precisa” pode estar permitida apenas durante o uso, sem “Permitir o tempo todo”. Não foi possível verificar a configuração do A55.

### 2. O horário exato é substituído silenciosamente — confirmado

[SystemScheduler, linhas 190–212](https://github.com/Ticgabriel/DespertAqui/blob/620a0344524f224ff10a629790a22dfd15adcaeb/app/src/main/java/com/destino/app/platform/schedule/SystemScheduler.kt#L190) utiliza `setExactAndAllowWhileIdle` se autorizado; caso contrário usa `setAndAllowWhileIdle`. Ao capturar qualquer exceção, tenta `set`, sem registrar o erro ou informar a degradação.

Alarmes inexatos podem atrasar. O Android também distingue disparos exatos solicitados pelo usuário ao permitir iniciar um serviço em segundo plano; um disparo inexato não recebe automaticamente essa mesma exceção. Outras exceções podem existir, portanto não é correto afirmar que todo disparo inexato necessariamente falhará. Fontes: [agendamento Android](https://developer.android.com/develop/background-work/services/alarms) e [restrições de serviços](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start).

**Hipótese prioritária:** o acesso “Alarmes e lembretes” está ausente, provocando atraso ou rejeição do início em segundo plano.

### 3. Falhas são pouco observáveis e não têm recuperação dentro da janela — confirmado

- [ScheduleReceiver, linha 23](https://github.com/Ticgabriel/DespertAqui/blob/620a0344524f224ff10a629790a22dfd15adcaeb/app/src/main/java/com/destino/app/platform/schedule/ScheduleReceiver.kt#L23) captura exceções sem registrar nada. `RecoveryReceiver` faz o mesmo.
- A reconciliação substitui erros do tipo `CommandResult.Error` por uma mensagem genérica; o motivo técnico se perde.
- Ao falhar no início, a ocorrência pode ficar aguardando requisitos. O próximo disparo continua sendo uma fronteira de horário calculada, frequentemente o término da janela; não existe uma política explícita de nova tentativa durante ela.
- [AlarmListViewModel, linha 86](https://github.com/Ticgabriel/DespertAqui/blob/620a0344524f224ff10a629790a22dfd15adcaeb/app/src/main/java/com/destino/app/feature/alarms/AlarmListViewModel.kt#L86) mostra “Agendado” ou “Aguardando início” sem explicar o bloqueio da ocorrência.

Esses achados explicam por que pode parecer que nada aconteceu, mesmo se o Android entregou o broadcast e o início foi recusado.

### 4. Restrição Samsung — possível, não verificada

O Galaxy pode colocar apps em suspensão/suspensão profunda. A verificação de `PowerManager.isIgnoringBatteryOptimizations` no projeto não comprova a situação nas listas próprias da Samsung. Conferir o DespertAqui em **Bateria → Limites de uso em segundo plano** e nas listas de apps suspensos, em suspensão profunda e que nunca suspendem; o caminho/nome varia com a One UI. [Documentação Samsung](https://developer.samsung.com/mobile/app-management.html).

### 5. Pontos de recuperação a revisar — não são causa comprovada do caso relatado

- Falta tratamento de `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` no receiver/manifesto para rearmar quando o acesso for concedido.
- `MonitoringService` usa `START_NOT_STICKY`; isso afeta recuperação depois de uma interrupção de serviço, mas mudar essa constante sozinho não resolve um serviço que ainda não começou.
- O serviço solicita `location | mediaPlayback` já ao iniciar. Rever separadamente a recuperação após boot: Android 15+ restringe iniciar `mediaPlayback` a partir de `BOOT_COMPLETED`. [Tipos de serviço](https://developer.android.com/develop/background-work/services/fgs/service-types#media).
- A reconciliação processa/materializa vários dias antes de iniciar o serviço. Medir sua duração no processo frio e investigar concorrência entre receivers, abertura do app e edição antes de propor uma reestruturação.

## Plano de execução para o próximo agente

### Etapa 1 — identificar a etapa que falha no A55

1. Começar da `main` atualizada, preservando a correção de chegada e verificando o estado da PR #1.
2. Registrar versão Android, One UI, versão/commit do APK instalado, permissões de localização precisa/em segundo plano, alarmes exatos, notificações/canais e restrições Samsung.
3. Reproduzir o mesmo horário futuro em duas situações: app aberto e app fechado com **Fechar tudo**. Não reabrir para verificar até coletar os dados.
4. Conferir registro do pacote em `adb shell dumpsys alarm` antes/depois de Fechar tudo e no horário. Coletar `adb shell dumpsys package com.destino.app`, `adb shell dumpsys activity services com.destino.app` e Logcat com timestamps.
5. Na implementação posterior, adicionar eventos de diagnóstico duráveis: programação registrada, horário previsto/real, receiver recebido, requisitos, início solicitado, serviço confirmado e falha com classe/motivo. Usar identificador da ocorrência; não registrar coordenadas, chaves ou dados desnecessários.

**Saída:** localizar a falha entre registro, entrega, autorização, início do serviço ou localização. Se o alarme nem chega ao receiver, não começar alterando o cálculo de distância.

### Etapa 2 — impedir a falsa indicação de prontidão

- Permitir salvar a configuração, mas distinguir “Salvo — faltam autorizações” de “Agendado para iniciar automaticamente”.
- Validar localização em segundo plano, localização precisa e acesso a alarmes exatos para o compromisso de horário. Notificações/canais e GPS devem aparecer como impedimentos específicos quando afetarem o funcionamento.
- Reutilizar o fluxo de permissões existente. Diferenciar autorizações obrigatórias e recomendações de bateria/Não Perturbe.
- Tornar explícita qualquer degradação para disparo inexato; não prometer início pontual nesse estado.

**Aceitação:** sem um requisito essencial, a tela informa exatamente o impedimento e como resolver; não declara prontidão indevida.

### Etapa 3 — tornar o disparo e o início verificáveis

- Preservar `AlarmManager` com broadcast persistente e disparo exato autorizado para a fronteira relevante.
- Substituir captura silenciosa por tratamento explícito e motivo persistido.
- Garantir que o receiver faça o mínimo necessário antes de solicitar o serviço e que a notificação seja publicada dentro do prazo do Android. Reduzir trabalho prévio se a medição demonstrar risco.
- Confirmar início após `startForeground` e registrar separadamente a primeira leitura de localização. Sucesso ao enviar a solicitação não equivale a serviço ativo.
- Serializar reconciliações se os testes mostrarem risco de um cálculo antigo sobrescrever a próxima fronteira. Manter operações idempotentes: o mesmo disparo não pode duplicar sessões.

**Aceitação:** com requisitos válidos, Fechar tudo não impede o início. Se houver rejeição, o motivo fica identificável e persistido.

### Etapa 4 — recuperação e compatibilidade

- Rearmar ao conceder alarmes exatos, após reboot/atualização e mudanças de relógio/fuso; rever o que já existe antes de duplicar receivers.
- Tratar a concessão/revogação de requisitos durante uma janela aberta.
- Para falhas transitórias, propor tentativa limitada dentro da janela, respeitando as restrições do Android. Não criar reinício infinito nem iniciar depois do término/cancelamento.
- Separar recuperação de serviço interrompido de início futuro. Não adotar `START_STICKY`, alarmes repetitivos ou WorkManager como solução automática para pontualidade.
- Orientar ajustes Samsung com caminhos suportados ou instrução manual. Não indicar que todas as restrições Samsung foram detectadas apenas porque o Android informou isenção de bateria.

### Etapa 5 — testes e entrega

| Cenário | Resultado a verificar |
|---|---|
| App aberto, horário futuro | Controle positivo: início e primeira localização registrados |
| Fechar tudo antes do horário | Mesmo início sem abrir a interface |
| Tela bloqueada e economia/Doze | Medir atraso real e identificar eventuais restrições |
| Sem localização em segundo plano | Bloqueio específico e orientação |
| Sem alarmes exatos | Estado degradado/bloqueado explícito |
| Notificação/canal bloqueado | Motivo correto, sem sucesso falso |
| Conceder requisito durante a janela | Reconciliação conforme a política definida |
| Reboot ou atualização | Próxima fronteira restaurada conforme regras do sistema |
| Vários alarmes, duas janelas, madrugada | Sem duplicação, perda ou início fora da janela |
| Serviço interrompido depois de começar | Estado e recuperação consistentes |

Adicionar testes unitários para a política de requisitos/retentativas e testes de integração do agendador/receiver. Manter os testes de chegada existentes. Rodar build e testes, **e validar fisicamente no A55**.

Para testes rápidos em repouso, considerar a limitação de frequência dos alarmes allow-while-idle; a documentação de Doze descreve um intervalo mínimo de nove minutos por app. Espaçar os ensaios e registrar condições para não confundir limitação do sistema com defeito. [Doze e App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby).

## Ordem para reduzir custo de implementação

1. Diagnóstico no aparelho e registro de falhas.
2. Correção mínima comprovada + status de requisitos.
3. Repetir o caso Fechar tudo no A55.
4. Somente então ampliar recuperação e testes secundários conforme evidências.

Não reescrever o motor de proximidade, não prometer funcionamento após Forçar parada e não adicionar um serviço permanente desde a abertura apenas para manter o processo vivo.

## Prompt para repassar

> Implemente o plano deste arquivo no Ticgabriel/DespertAqui. O caso confirmado é Samsung Galaxy A55, agendamento futuro e Fechar tudo nos recentes, sem Forçar parada. Primeiro valide a versão atual do repositório e preserve a correção de chegada do outro agente e as mudanças de permissões/navegação existentes. Identifique por registros se a falha ocorre ao registrar, receber ou iniciar o agendamento. Faça a menor correção comprovada, testes e build, e apresente evidências do teste no A55 ou declare claramente se esse teste ficou pendente. Não trate hipótese como causa confirmada nem amplie o escopo para reescrever o motor de proximidade.
