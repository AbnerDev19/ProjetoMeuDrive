# MeuDrive

Sua nuvem pessoal na rede de casa: um Galaxy A06 guarda os arquivos; PCs e outros celulares enviam e baixam pela rede local. Android 11 ou superior, sem root. Interface inspirada no design CloudVault fornecido, com fonte Plus Jakarta Sans incluída no APK e funcionamento sem CDNs ou internet.

## Novidades da versão 1.2

- Fotos agora têm miniaturas autenticadas e visualizador com correção de orientação, cache e tratamento de formatos não suportados.
- Envio mostra o destino e a fila; pausa/retomada e tentativa apenas dos pendentes evitam duplicar o que já concluiu. Há recuperação de conexão e navegação entre pastas.
- A06 tem **Modo dedicado** com Não perturbe reversível, fixação confirmada pelo Android e condições de bateria/rede/temperatura. Um utilitário USB opcional permite suspender uma seleção revisada de apps de terceiros e restaurar depois. O APK não desativa outros apps sem privilégios.
- Acesso externo tem tela própria, endereços Wi-Fi/remoto separados e suporte correto a HTTPS. Escolha VPN privada Tailscale (A06 sem PC intermediário) ou link HTTPS temporário Cloudflare Quick Tunnel (PC ligado em casa, sem conta/domínio).

Guias completos: [Servidor dedicado e suspensão opcional por USB](docs/SERVIDOR-DEDICADO.md) e [Acesso remoto gratuito e site de upload](docs/ACESSO-REMOTO.md).

## Instalar

Compile com Android Studio ou baixe o artefato **MeuDrive-APK** na aba **Actions → Android APK → execução concluída**. Extraia o ZIP e abra o APK em cada celular. O Android pedirá autorização para instalar a partir do navegador ou gerenciador de arquivos. A instalação exige confirmação em cada aparelho.

Para instalar em vários celulares por USB, com Platform-Tools no PATH, depuração USB ligada e cada aparelho autorizado:

```powershell
.\scripts\install-all.ps1 -Apk .\MeuDrive.apk
```

Linux/macOS:

```sh
./scripts/install-all.sh ./MeuDrive.apk
```

Os scripts executam `adb install -r` somente nos aparelhos conectados e autorizados. Não apagam dados. Uma instalação existente precisa ter a mesma assinatura. Este APK é uma compilação de depuração; para atualizações permanentes, use e guarde seu próprio keystore de produção. Builds de máquinas diferentes podem ter assinaturas de depuração diferentes. Não desinstale uma versão existente sem copiar os arquivos e configurações necessários.

## Configurar o Galaxy A06

1. Abra **Servidor**, marque **Este é o servidor principal**, dê um nome (por exemplo, “A06 da casa”) e defina a senha da casa.
2. Autorize acesso aos arquivos e notificações. Se quiser navegar também por DCIM, Download e outras pastas internas, ative **Compartilhar armazenamento interno** com o servidor desligado. Desativado, o compartilhamento fica na pasta `MeuDrive`.
3. Permita bateria sem restrição. Na Samsung, adicione MeuDrive à lista de aplicativos nunca suspensos. Toque em **Ligar servidor** e anote `http://IP-DO-A06:8080`.
4. Mantenha o A06 em Wi-Fi e em local ventilado. Um carregador pode ajudar no uso contínuo; monitore temperatura e bateria. A tela pode ficar apagada durante o funcionamento do servidor.
5. Opcional: no roteador, reserve um IP DHCP para o A06, evitando mudança de endereço.

“Principal” identifica o papel deste celular na interface; não copia automaticamente os dados dos outros aparelhos. O serviço tenta voltar após reinicialização quando estava ligado. Restrições de bateria da Samsung, parada forçada e versões do Android podem exigir abrir o app e ligar novamente.

## Outros celulares e computador

- **Outros celulares:** instale o mesmo APK, abra **Arquivos → Encontrar servidores no Wi-Fi** ou digite o IP do A06. O endereço escolhido fica salvo. Entre com a senha da casa. Para usar **Compartilhar → MeuDrive**, salve a mesma senha em **Servidor** nesse celular; não é necessário ligar seu servidor só para enviar.
- **PC:** no navegador, abra o endereço do A06 e entre com a senha. Arraste arquivos ou use **+**. Abra um arquivo para ver detalhes e baixar. A seleção no navegador autoriza apenas os arquivos escolhidos do PC.
- **Ver arquivos de outro celular:** ligue o servidor também nele, permita acesso e abra **Minha rede** no navegador ou use a descoberta do app. Cada celular mantém seu armazenamento; escolha o A06 como destino para centralizar cópias.
- **Enviar texto/link:** use Compartilhar no Android ou a tela Compartilhados do navegador.

Os aparelhos precisam estar na mesma rede, com comunicação entre clientes liberada. Uma rede de convidados ou isolamento de clientes no roteador pode impedir a descoberta e as conexões. Se mDNS não funcionar, use o IP manualmente. O app não enumera arquivos privados dos aplicativos, `Android/data` nem cartão SD. O PC compartilha apenas a pasta que você autorizar ao iniciar o servidor opcional abaixo. Não há sincronização automática ou montagem SMB; os arquivos são enviados por escolha do usuário.

## Compartilhar uma pasta do PC com os celulares

Se quiser navegar diretamente pelos arquivos de uma pasta do PC, baixe e extraia o artefato **MeuDrive-PC** da mesma execução do GitHub Actions (ou baixe o código desta branch). Instale Python 3.10 ou superior e execute, dentro da pasta extraída:

```powershell
python -m pip install -r scripts/requirements-pc.txt
python scripts/pc-server.py --root "D:\ArquivosCompartilhados" --name "Meu PC"
```

No Linux/macOS, use `python3` e um caminho como `--root "$PWD/Compartilhados"`. A pasta precisa existir. O programa solicita a senha da casa sem exibi-la e mostra o endereço do PC. Autorize a porta TCP 8080 no firewall **apenas na rede privada**. A biblioteca opcional zeroconf anuncia o PC na descoberta; para mDNS, a rede deve permitir UDP 5353. Sem ela, digite o IP manualmente.

No celular: **Arquivos → Encontrar servidores no Wi-Fi → Meu PC**. No A06, abra **Minha rede → Meu PC**. A pasta escolhida permite leitura, criação, envio e exclusão para quem tem a senha; não indique a raiz do disco se só deseja compartilhar uma pasta. O PC precisa permanecer ligado e com o programa aberto. Pressione Ctrl+C para parar. Transferências para o PC usam o mesmo protocolo em blocos e downloads com retomada. A pasta interna `.meudrive-transfers` fica protegida e os uploads incompletos expiram em 24 horas; a caixa de entrada do PC é mantida em memória até encerrar o programa.

Para centralizar no A06, abra o endereço do A06 no navegador do PC e envie os arquivos desejados. Compartilhar a pasta do PC não cria uma segunda cópia automática no A06.

## Transferências e desempenho

- Uploads em blocos de até **8 MiB**, buffer de **256 KiB**, sem carregar o arquivo inteiro em RAM.
- Blocos são gravados diretamente na área de preparação no armazenamento do servidor e movidos para o destino ao concluir, sem uma segunda cópia completa. Uma queda de conexão desfaz apenas o bloco incompleto.
- Progresso no disco por **24 horas**. No navegador, use **Retomar**; após recarregar, selecione o mesmo arquivo original, na mesma pasta e no mesmo navegador. No Android, tente novamente ou selecione o mesmo documento. Não altere o arquivo original durante o envio. Mantenha a tela de envio aberta; o envio dos clientes ainda não é um serviço em primeiro plano.
- Até duas requisições de blocos admitidas; gravação dos blocos serializada para manter o disco e as confirmações consistentes. Não há paralelismo ilimitado.
- Download com `Range`, `ETag` e `If-Range` para permitir retomada por clientes compatíveis. O DownloadManager do Android pode retomar downloads.
- Arquivos com mesmo nome recebem sufixo em vez de substituir o existente. Arquivos incompletos não aparecem na pasta de destino. A caixa de entrada registra os recebidos.
- Reserva de **128 MiB livres** para o sistema. O espaço útil do aparelho pode ser utilizado sem cota comercial, mas o aplicativo não ocupa 100% de CPU/RAM, não remove limites térmicos e não preenche deliberadamente todo o disco.
- Provedores de documentos Android que não informam tamanho exigem uma cópia no cache do celular de origem antes do envio.

A velocidade real depende do Wi-Fi, roteador, distância, concorrência e armazenamento dos aparelhos. Use o PC por cabo Ethernet e o melhor Wi-Fi que cada celular suporta. Não há medição em aparelhos físicos nesta entrega, portanto não há promessa de MB/s.

O acesso local usa HTTP com senha/cookie ou chave da casa: **não é criptografado no Wi-Fi**. Use rede privada confiável; não redirecione a porta 8080 para a internet. Para acesso fora de casa, use uma VPN como Tailscale nos aparelhos envolvidos.

## Compilar e verificar

JDK 17, Android SDK 34 e Build-Tools 34.0.0:

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
python3 -m pip install -r scripts/requirements-pc.txt
python3 scripts/test-pc-server.py
python3 scripts/test-remote-access.py
python3 scripts/test-dedicate-device.py
```

O teste web opcional `python3 scripts/test-ui.py` usa Playwright com Chromium instalado. Os testes não aplicam mudanças em celulares físicos.

APK: `app/build/outputs/apk/debug/app-debug.apk`. O wrapper Gradle 8.7 está incluído. A integração contínua repete testes, lint e geração do APK.

O servidor opcional do PC tem quatro testes de integração (`python3 scripts/test-pc-server.py`) que cobrem autenticação, cookies, retomada, rollback, nomes repetidos, intervalos e isolamento de pastas.

Os testes de JVM cobrem retomada após reinício, bloco interrompido, colisões, arquivo vazio, validação de nomes e offsets, mudança da raiz e intervalos HTTP. Testes HTTP iniciam o servidor real com contexto Android simulado e verificam envio/download, autenticação, isolamento de caminhos, métodos e origem. A interface web foi exercitada com Playwright em resoluções de celular e PC, incluindo falha e retomada de upload; isso não substitui o teste físico no A06.

A fonte incluída usa a licença SIL Open Font License, em `app/src/main/assets/fonts/OFL.txt`.
