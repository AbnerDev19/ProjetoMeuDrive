# Transformar o A06 em servidor dedicado

Abra a tela **Modo dedicado** do MeuDrive no A06. Ela organiza os controles do servidor, acesso a arquivos, bateria, rede, temperatura e acesso remoto. O serviço do servidor continua funcionando com a tela apagada; não há necessidade de manter brilho ou processador no máximo.

## O que o aplicativo consegue fazer

- Iniciar o servidor em primeiro plano, anunciar na rede e manter os recursos de funcionamento enquanto o Android permitir.
- Ativar **Não perturbe** depois que você conceder o acesso solicitado pelo Android. Isso silencia notificações, chamadas e alarmes conforme o filtro aplicado. Ao sair pelo controle do MeuDrive, o filtro anterior é restaurado. Se revogar a permissão antes de sair, ajuste Não perturbe manualmente nas configurações do Android.
- Solicitar **fixação de tela**, com confirmação do Android, para manter o MeuDrive em evidência. Isso não suspende outros aplicativos. O sistema mostra como desafixar; normalmente exige o gesto ou a combinação de botões indicada pelo próprio Android.
- Mostrar condições que afetam o servidor, sem declarar “24h garantido” só porque um botão foi ligado.

Um APK comum não tem permissão para desativar todos os outros apps, congelar processos de terceiros, remover o sistema Samsung nem tornar o aparelho um servidor exclusivo inviolável. O bloqueio completo de apps exige aparelho gerenciado por Device Owner/DPC ou privilégios especiais. Esta entrega não executa root, não formata o aparelho e não provisiona administração empresarial automaticamente.

## Suspender aplicativos de terceiros por USB (opcional)

O pacote PC também inclui um utilitário ADB para quem quer desativar aplicativos instalados de uso pessoal enquanto o A06 fica dedicado. Ele exige depuração USB já autorizada pelo dono, apresenta candidatos e exige uma seleção explícita antes de aplicar. Não é uma ação silenciosa do APK. Consulte `python scripts/dedicate-device.py --help` e siga a sequência de plano, seleção e aplicação; o mesmo utilitário restaura apenas o que foi desativado por ele.

Exemplo no PC, com Android Platform-Tools no PATH:

```powershell
adb devices
python scripts/dedicate-device.py --serial SERIAL-DO-A06 --plan dedicated-plan.json
```

Esse comando só lê o celular e cria `dedicated-plan.json`, com `selected` vazio. Abra o arquivo e copie de `candidates` para `selected` apenas os nomes dos pacotes que reconhece e deseja suspender. Em seguida:

```powershell
python scripts/dedicate-device.py --serial SERIAL-DO-A06 --apply dedicated-plan.json --state dedicated-state.json
```

O programa revalida o aparelho e a lista, mostra a seleção e só desativa depois que você digitar `DESATIVAR`. Para desfazer:

```powershell
python scripts/dedicate-device.py --serial SERIAL-DO-A06 --restore dedicated-state.json
```

O estado é gravado antes de cada mudança para permitir restauração mesmo após interrupção. Apps que já estavam desativados antes não são reativados por esse fluxo.

Ele não deve ser usado para remover sistema, launcher, teclado, MeuDrive ou Tailscale. A seleção depende dos pacotes encontrados no aparelho e não foi aplicada em nenhum celular nesta entrega. Mantenha o arquivo de restauração no PC.

## Configuração da Samsung

1. Defina uma senha no MeuDrive e permita acesso ao armazenamento.
2. Em Bateria, escolha uso **sem restrições** e inclua o MeuDrive em **aplicativos nunca suspensos**. Faça o mesmo com Tailscale caso use acesso privado fora de casa.
3. Mantenha Wi-Fi ligado. Evite economia de energia que corte a rede ou suspenda o aplicativo.
4. Ligue o servidor e confira o endereço exibido. Reserve o IP no DHCP do roteador, se puder.
5. Deixe o A06 em local ventilado e com alimentação adequada. Tela apagada ajuda a economizar energia e reduzir calor.

Sair do modo dedicado desfaz o controle de distrações solicitado pelo MeuDrive; confira na tela se o servidor permanece ligado. Para encerrar o acesso aos arquivos, use o botão de desligar servidor. Reinicializações, parada forçada, temperatura, rede ou decisões do Android ainda podem interromper o serviço. O aplicativo não promete 100% de uptime ou força uso de 100% de CPU/RAM.

## Atualizar o APK sem perder seus dados

Os pacotes fornecidos pela CI são de depuração. Android só permite atualizar uma instalação com a mesma assinatura; builds antigos feitos em máquinas diferentes podem ter outra chave. Não desinstale para resolver isso sem primeiro copiar os arquivos necessários e anotar as configurações/senha. Arquivos guardados na área privada do app podem ser apagados na desinstalação.

Para distribuir atualizações permanentes, gere e mantenha um keystore de produção e assine todas as versões com a mesma chave. Não publique essa chave no repositório nem em artefatos públicos. A chave privada de uma versão antiga não pode ser recuperada do APK. Esta entrega não altera o applicationId nem desinstala a versão existente automaticamente.
